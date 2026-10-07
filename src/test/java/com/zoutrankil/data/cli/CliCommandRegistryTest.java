package com.zoutrankil.data.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.*;

class CliCommandRegistryTest {
    @Test void twelveFamiliesRetainThe160OriginalNamesAndRegisterNineRemoteCommands() throws Exception {
        List<String> original;
        try (var stream = getClass().getResourceAsStream("/cli/commands-before-t10.txt")) {
            assertNotNull(stream, "The compatibility manifest was captured before extraction");
            original = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().toList();
        }
        assertEquals(160, original.size());
        assertEquals(160, new TreeSet<>(original).size());
        var families = allFamilies();
        assertEquals(12, families.size());
        var expected = new TreeSet<>(original);
        expected.addAll(Set.of("sync-run-status", "plan-margin-detail-job", "run-margin-detail-job",
                "plan-regime-features-monitor-daily-job", "run-regime-features-monitor-daily-job",
                "finish-regime-monitor-publication", "plan-market-sentiment-daily-job",
                "run-market-sentiment-daily-job", "finish-market-sentiment-publication"));
        assertEquals(169, families.stream().mapToInt(family -> family.commands().size()).sum());
        assertEquals(expected, new TreeSet<>(new CliCommandRegistry(families).commands()));
    }

    @Test void duplicateCommandRegistrationFailsBeforeAnyExecution() {
        var first = new RecordingFamily(Set.of("duplicate-command", "first-only"));
        var second = new RecordingFamily(Set.of("duplicate-command", "second-only"));

        var failure = assertThrowsExactly(IllegalArgumentException.class,
                () -> new CliCommandRegistry(List.of(first, second)));
        assertTrue(failure.getMessage().contains("duplicate-command"));
        assertTrue(first.calls.isEmpty());
        assertTrue(second.calls.isEmpty());
    }

    @Test void emptyAndBlankRegistrationsAreRejectedAndTheCommandIndexIsImmutable() {
        assertThrows(IllegalArgumentException.class,
                () -> new CliCommandRegistry(List.of(new RecordingFamily(Set.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> new CliCommandRegistry(List.of(new RecordingFamily(Set.of(" ")))));
        var family = new RecordingFamily(Set.of("sample"));
        var families = new ArrayList<CliCommandFamily>(List.of(family));
        var registry = new CliCommandRegistry(families);
        families.clear();
        assertEquals(Set.of("sample"), registry.commands());
        assertThrows(UnsupportedOperationException.class, () -> registry.commands().clear());
    }

    @Test void productionRunnerDependsOnlyOnTheCommandRegistry() {
        var fields = java.util.Arrays.stream(CommandLineRunner.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers())).toList();
        assertEquals(1, fields.size());
        assertEquals(CliCommandRegistry.class, fields.getFirst().getType());
        var injected = java.util.Arrays.stream(CommandLineRunner.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                .toList();
        assertEquals(1, injected.size());
        assertArrayEquals(new Class<?>[]{CliCommandRegistry.class}, injected.getFirst().getParameterTypes());
    }

    @Test void registryRoutesAliasesAndOptionsOnlyToTheirRegisteredFamily() throws Exception {
        var first = new RecordingFamily(Set.of("first", "first-alias"));
        var second = new RecordingFamily(Set.of("second"));
        var registry = new CliCommandRegistry(List.of(first, second));
        var options = Map.of("--file", "a file.json", "--mode", "backfill");

        registry.execute("first-alias", options);
        assertEquals(List.of(new Call("first-alias", options)), first.calls);
        assertTrue(second.calls.isEmpty());
        registry.execute("second", Map.of());
        assertEquals(List.of(new Call("second", Map.of())), second.calls);
    }

    @Test void runnerRemovesSpringOptionsThenParsesBothBusinessOptionForms() throws Exception {
        var family = new RecordingFamily(Set.of("sample-command"));
        var runner = new CommandLineRunner(new CliCommandRegistry(List.of(family)));

        runner.run(new DefaultApplicationArguments("--spring.profiles.active=offline", "sample-command",
                "--path", "a file.json", "--mode=backfill", "--app.web.enabled=false", "--logging.level.root=ERROR"));
        assertEquals(List.of(new Call("sample-command", Map.of("--path", "a file.json", "--mode", "backfill"))), family.calls);
    }

    @Test void unknownAndAbsentCommandsReturnTheSameUsageWithoutCallingAFamily() {
        var family = new RecordingFamily(Set.of("sample-command"));
        var runner = new CommandLineRunner(new CliCommandRegistry(List.of(family)));
        var absent = assertThrowsExactly(IllegalArgumentException.class,
                () -> runner.run(new DefaultApplicationArguments()));
        var unknown = assertThrowsExactly(IllegalArgumentException.class,
                () -> runner.run(new DefaultApplicationArguments("unknown-command")));
        var configurationOnly = assertThrowsExactly(IllegalArgumentException.class,
                () -> runner.run(new DefaultApplicationArguments("--spring.profiles.active=offline")));

        assertTrue(absent.getMessage().startsWith("Usage:"));
        assertEquals(absent.getMessage(), unknown.getMessage());
        assertEquals(absent.getMessage(), configurationOnly.getMessage());
        assertEquals(2, CliExitStatus.failureCode(absent));
        assertEquals(2, CliExitStatus.failureCode(unknown));
        assertTrue(family.calls.isEmpty());
    }

    @Test void malformedOptionsFailBeforeFamilyDispatch() {
        var family = new RecordingFamily(Set.of("sample-command"));
        var runner = new CommandLineRunner(new CliCommandRegistry(List.of(family)));
        for (String[] arguments : List.of(
                new String[]{"sample-command", "unexpected"},
                new String[]{"sample-command", "--path"},
                new String[]{"sample-command", "--path", "--mode=backfill"},
                new String[]{"sample-command", "--path="},
                new String[]{"sample-command", "--path", " "},
                new String[]{"sample-command", "--path=a", "--path", "b"},
                new String[]{"sample-command", "--Path", "a"},
                new String[]{"sample-command", "--=invalid"})) {
            var failure = assertThrowsExactly(IllegalArgumentException.class,
                    () -> runner.run(new DefaultApplicationArguments(arguments)));
            assertEquals(2, CliExitStatus.failureCode(failure));
        }
        assertTrue(family.calls.isEmpty());
    }

    private static List<CliCommandFamily> allFamilies() {
        return List.of(
                new LegacyQuestDbCommands(null),
                new CatalogCommands(null, null),
                new LedgerCommands(null),
                new ScheduleCommands(null),
                new GroupCommands(null, null, null, null),
                new StockCommands(null, null, null, null, null, null, null, null),
                new CalendarCommands(null),
                new EtfCommands(null, null, null, null, null, null),
                new IndexCommands(null, null, null, null, null, null, null, null, null),
                new FlowCommands(null, null, null, null),
                new L2Commands(null, null, null, null, null),
                new MaterializationCommands(null, null, null, null, null));
    }

    private record Call(String command, Map<String, String> options) {}

    private static final class RecordingFamily implements CliCommandFamily {
        private final Set<String> commands;
        private final List<Call> calls = new ArrayList<>();
        private RecordingFamily(Set<String> commands) { this.commands = commands; }
        @Override public Set<String> commands() { return commands; }
        @Override public void execute(String command, Map<String, String> options) {
            calls.add(new Call(command, Map.copyOf(options)));
        }
    }
}
