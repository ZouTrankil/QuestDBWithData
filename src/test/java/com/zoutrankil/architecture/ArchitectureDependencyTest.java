package com.zoutrankil.architecture;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureDependencyTest {
    @TempDir Path temporary;

    @Test void productionDependenciesMatchOnlyTheExplicitRemainingDebt() throws Exception {
        var edges = new HashSet<ArchitectureRules.Edge>();
        var classes = new HashSet<String>();
        for (String root : System.getProperty("architecture.mainClasses", "build/classes/java/main").split(File.pathSeparator)) {
            try (var files = Files.walk(Path.of(root))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    var found = ArchitectureRules.bytecodeEdges(Files.readAllBytes(file));
                    edges.addAll(found);
                    found.forEach(edge -> classes.add(ArchitectureRules.topLevel(edge.origin())));
                }
            }
        }
        assertTrue(classes.size() > 900, "Main production classes must be present; never pass on an empty scan");
        for (String root : System.getProperty("architecture.sources", "src/main/java").split(File.pathSeparator)) {
            try (var files = Files.walk(Path.of(root))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String relative = Path.of(root).relativize(file).toString().replace(File.separatorChar, '.');
                    edges.addAll(ArchitectureRules.sourceEdges(relative.substring(0, relative.length() - 5),
                            Files.readString(file), classes));
                }
            }
        }
        var actual = ArchitectureRules.violations(edges);
        Path report = Path.of("build/reports/architecture/current-violations.tsv");
        Files.createDirectories(report.getParent());
        Files.write(report, actual.stream().map(ArchitectureRules.Violation::key).toList());
        var allowed = ArchitectureRules.readExceptions(Path.of("docs/architecture-exceptions.tsv")).keySet();
        assertMatches(actual, allowed);
    }

    private static void assertMatches(Set<ArchitectureRules.Violation> actual, Set<ArchitectureRules.Violation> allowed) {
        var added = new TreeSet<>(actual); added.removeAll(allowed);
        var stale = new TreeSet<>(allowed); stale.removeAll(actual);
        assertTrue(added.isEmpty() && stale.isEmpty(), () -> "New violations (" + added.size() + "): "
                + added.stream().limit(10).toList() + "; stale exceptions (" + stale.size() + "): "
                + stale.stream().limit(10).toList() + "; see build/reports/architecture/current-violations.tsv");
    }

    @Test void newEdgesAndStaleExceptionsBothFail() {
        var violation = new ArchitectureRules.Violation("domain-outward", "com.zoutrankil.data.domain.Bad", "com.zoutrankil.data.service.Owner");
        assertThrows(AssertionError.class, () -> assertMatches(Set.of(violation), Set.of()));
        assertThrows(AssertionError.class, () -> assertMatches(Set.of(), Set.of(violation)));
        assertMatches(Set.of(violation), Set.of(violation));
    }

    @Test void exactExceptionsRequireAnOwnerTaskAndRejectWildcardsOrDuplicates() throws Exception {
        Path file = temporary.resolve("exceptions.tsv");
        String row = "domain-outward\tcom.zoutrankil.data.domain.Bad\tcom.zoutrankil.data.service.Owner\tT05\tMove pure policy";
        Files.writeString(file, row + "\n");
        assertEquals(1, ArchitectureRules.readExceptions(file).size());
        for (String bad : List.of(row + "\n" + row, row.replace(".Owner", ".*"), row.replace("T05", "someday"))) {
            Files.writeString(file, bad);
            assertThrows(IllegalArgumentException.class, () -> ArchitectureRules.readExceptions(file));
        }
    }

    @Test void scannerIncludesGenericsArraysAnnotationsAndLambdaReferencesButNotStringLiterals() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("ArchitectureDependencyTest$BytecodeFixture.class")) {
            assertNotNull(input); bytes = input.readAllBytes();
        }
        var targets = ArchitectureRules.bytecodeEdges(bytes).stream().map(ArchitectureRules.Edge::target).collect(java.util.stream.Collectors.toSet());
        assertTrue(targets.contains("com.zoutrankil.data.repository.SyncRunLedger$Run"));
        assertTrue(targets.contains("java.sql.Connection"));
        assertTrue(targets.contains("org.springframework.beans.factory.annotation.Autowired"));
        assertTrue(targets.contains("java.sql.DriverManager"));
        assertFalse(targets.contains("com.zoutrankil.data.service.StringOnly"));
    }

    static class BytecodeFixture {
        List<com.zoutrankil.data.repository.SyncRunLedger.Run> generic;
        java.sql.Connection[] connections;
        @org.springframework.beans.factory.annotation.Autowired Object annotated;
        String literal = "Lcom/zoutrankil/data/service/StringOnly;";
        String annotationLiteral = "Lorg/springframework/beans/factory/annotation/Autowired;";
        Runnable lambda = () -> java.sql.DriverManager.getLoginTimeout();
    }

    @Test void sourceSupplementFindsInlinedConstantsAndIgnoresCommentsAndStrings() {
        String type = "com.zoutrankil.data.service.Owner";
        String source = "class Example { int cap = " + type + ".LIMIT; String text=\"" + type + ".OTHER\"; } // " + type;
        assertEquals(Set.of(new ArchitectureRules.Edge("com.zoutrankil.data.repository.Example", type, false)),
                ArchitectureRules.sourceEdges("com.zoutrankil.data.repository.Example", source, Set.of(type)));
        assertTrue(ArchitectureRules.sourceEdges("example.Empty", "// " + type + "\n/* " + type + " */ String s=\"" + type + "\";", Set.of(type)).isEmpty());
    }

    @Test void portTypesAreAllowedButCallingTheirExecutionContainerIsNot() {
        String origin = "com.zoutrankil.data.repository.Writer";
        String runner = "com.zoutrankil.data.service.VerifiedBatchExecutor";
        assertTrue(ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge(origin, runner + "$Port", false),
                new ArchitectureRules.Edge(origin, runner, false), new ArchitectureRules.Edge(origin, runner + "$Port", true))).isEmpty());
        assertEquals("repository-executes-runner", ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge(origin, runner, true))).first().rule());
    }

    @Test void wildcardImportsCannotHideInlinedConstantsAndExplicitImportsTakePrecedence() {
        String type = "com.zoutrankil.data.service.Owner", other = "com.zoutrankil.data.domain.Owner";
        String origin = "com.zoutrankil.data.repository.Reader";
        String source = "import com.zoutrankil.data.service.*; class Reader { int n = Owner.LIMIT; }";
        assertEquals(Set.of(new ArchitectureRules.Edge(origin, type, false)), ArchitectureRules.sourceEdges(origin, source, Set.of(type, other)));
        assertEquals(Set.of(new ArchitectureRules.Edge(origin, other, false)), ArchitectureRules.sourceEdges(origin,
                "import " + other + "; " + source, Set.of(type, other)));
        assertTrue(ArchitectureRules.sourceEdges(origin, "import com.zoutrankil.data.service.*; class Reader { String s=\"Owner.LIMIT\"; }", Set.of(type)).isEmpty());
    }

    @Test void storageImplementationsHaveTheSameBoundaryAsRepositories() {
        String store = "com.zoutrankil.data.storage.Store";
        for (String role : List.of("domain", "mapper", "cli", "web"))
            assertFalse(ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge("com.zoutrankil.data." + role + ".Bad", store, false))).isEmpty());
        assertFalse(ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge(store, "com.zoutrankil.data.service.Owner", false))).isEmpty());
    }

    @Test void familyPortsAreSharedBoundariesWithoutInfrastructureOrConcreteApplicationDependencies() {
        String port = "com.zoutrankil.data.etf.port.EtfTarget";
        for (String target : List.of("com.zoutrankil.data.etf.application.Owner",
                "com.zoutrankil.data.etf.storage.Writer", "com.zoutrankil.data.client.Client",
                "com.zoutrankil.data.config.Configuration", "org.springframework.stereotype.Service",
                "org.springframework.jdbc.core.JdbcTemplate", "java.sql.Connection"))
            assertEquals("port-outward", ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge(port, target, false))).first().rule());
        for (String origin : List.of("com.zoutrankil.data.etf.application.Owner", "com.zoutrankil.data.etf.storage.Writer"))
            assertTrue(ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge(origin, port, false))).isEmpty());
        for (String origin : List.of("com.zoutrankil.data.etf.domain.Value", "com.zoutrankil.data.etf.mapper.Mapper"))
            assertFalse(ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge(origin, port, false))).isEmpty());
    }

    @Test void familyPortsCanUseExistingCodecContractsButCannotExecuteTheirContainer() {
        String port = "com.zoutrankil.data.etf.port.EtfWriteSession";
        String executor = "com.zoutrankil.data.service.VerifiedBatchExecutor";
        assertTrue(ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge(port, executor, false),
                new ArchitectureRules.Edge(port, executor + "$Port", false),
                new ArchitectureRules.Edge(port, executor + "$Codec", true))).isEmpty());
        assertEquals("port-executes-runner", ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge(port, executor, true))).first().rule());
    }

    @Test void rulesCoverEntrypointsDomainsMappersAndStorageAccess() {
        for (String origin : List.of("domain.Bad", "mapper.Bad", "service.Bad", "cli.Bad", "web.Bad"))
            assertFalse(ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge("com.zoutrankil.data." + origin,
                    "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate", false))).isEmpty(), origin);
        assertFalse(ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge("com.zoutrankil.data.domain.Bad", "com.zoutrankil.batch.SourceCollector", false))).isEmpty());
        assertTrue(ArchitectureRules.violations(Set.of(new ArchitectureRules.Edge("com.zoutrankil.data.service.Owner", "java.sql.SQLException", false))).isEmpty());
    }
}
