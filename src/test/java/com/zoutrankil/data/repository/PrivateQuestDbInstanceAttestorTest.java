package com.zoutrankil.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Admission checks run against temporary files and a fake process, without inspecting host listeners. */
class PrivateQuestDbInstanceAttestorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void nonWindowsRejectionPrecedesAllFilesystemAndProcessWork(String code) {
        var spec = new PrivateQuestDbInstanceAttestor.Spec(code, temporary.resolve("missing-var"),
                temporary.resolve("missing-root"), 18812, 19000);
        var attestor = new PrivateQuestDbInstanceAttestor(spec,
                root -> fail("Root admission must not run on a non-Windows host"), () -> false,
                script -> fail("A non-Windows host must not start a process"));

        failure(attestor, code + " implicit private-instance admission requires Windows process attestation");
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void successfulAttestationUsesEachDatasetsPortsAndAcceptsEitherListenerOrder(String code) throws Exception {
        Fixture fixture = fixture(code);
        var first = record(fixture.spec.pgPort(), 51, "JAVA.EXE", command(fixture.root));
        var second = record(fixture.spec.qwpPort(), 51, "QuestDB.EXE", command(fixture.root));
        FakeProcess process = process(List.of(first, second));
        var events = new ArrayList<String>();
        var attestor = new PrivateQuestDbInstanceAttestor(fixture.spec, realRoot -> {
            assertEquals(fixture.root.toRealPath(), realRoot);
            events.add("root");
        }, () -> true, script -> {
            events.add("process");
            assertTrue(script.contains("Get-NetTCPConnection -LocalPort " + fixture.spec.qwpPort()
                    + "," + fixture.spec.pgPort() + " -State Listen -ErrorAction Stop"));
            assertTrue(script.contains("foreach($port in @(" + fixture.spec.qwpPort()
                    + "," + fixture.spec.pgPort() + "))"));
            assertTrue(script.contains("$matches.Count -ne 1"));
            assertTrue(script.contains("Get-CimInstance Win32_Process"));
            return process;
        });

        assertDoesNotThrow(attestor::attest);
        assertEquals(List.of("root", "process"), events);
        assertEquals(10, process.timeout);
        assertEquals(TimeUnit.SECONDS, process.unit);
        assertEquals(32_769, process.readLimit);
        assertFalse(process.destroyed);
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    @ResourceLock("java.util.Locale")
    void processScriptKeepsAsciiPortsUnderNonLatinDefaultFormatLocale(String code) throws Exception {
        Fixture fixture = fixture(code);
        var scripts = new ArrayList<String>();
        var attestor = new PrivateQuestDbInstanceAttestor(fixture.spec, null, () -> true, script -> {
            scripts.add(script);
            return process(validRecords(fixture, 31));
        });
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            assertDoesNotThrow(attestor::attest);
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
            assertDoesNotThrow(attestor::attest);
            assertEquals(scripts.getFirst(), scripts.getLast());
            assertTrue(scripts.getLast().contains("-LocalPort " + fixture.spec.qwpPort() + "," + fixture.spec.pgPort()));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void pathPrefixSiblingOutsideWorkspaceIsRejectedBeforeRootCheck(String code) throws Exception {
        Fixture fixture = fixture(code);
        Path outside = Files.createDirectories(fixture.spec.workspaceVar().resolveSibling("var-other"));
        var spec = new PrivateQuestDbInstanceAttestor.Spec(code, fixture.spec.workspaceVar(), outside,
                fixture.spec.pgPort(), fixture.spec.qwpPort());
        var attestor = new PrivateQuestDbInstanceAttestor(spec, root -> fail("Outside root must fail first"),
                () -> true, script -> fail("Outside root must not start a process"));

        failure(attestor, code + " private data root is outside workspace var");
    }

    @Test void missingDataRootWrapsFilesystemFailureWithoutStartingProcess() throws Exception {
        Fixture fixture = fixture("D095");
        var spec = new PrivateQuestDbInstanceAttestor.Spec("D095", fixture.spec.workspaceVar(),
                fixture.root.resolve("missing"), fixture.spec.pgPort(), fixture.spec.qwpPort());
        var attestor = new PrivateQuestDbInstanceAttestor(spec, root -> fail("Missing root must fail first"),
                () -> true, script -> fail("Missing root must not start a process"));

        var failure = failure(attestor, "Cannot attest D095 private QuestDB data root and process");
        assertInstanceOf(NoSuchFileException.class, failure.getCause());
    }

    @Test void rootCheckReceivesResolvedPathAndRunsBeforeConfigurationRead() throws Exception {
        Fixture fixture = fixture("D098");
        Path child = Files.createDirectories(fixture.root.resolve("nested"));
        var spec = new PrivateQuestDbInstanceAttestor.Spec("D098", fixture.spec.workspaceVar(),
                child.resolve(".."), fixture.spec.pgPort(), fixture.spec.qwpPort());
        Files.writeString(fixture.config, "invalid-config");
        var rejection = new IllegalStateException("D098 private fixture marker differs from the admitted source and MV");
        var attestor = new PrivateQuestDbInstanceAttestor(spec, root -> {
            assertEquals(fixture.root.toRealPath(), root);
            throw rejection;
        }, () -> true, script -> fail("Root policy rejection must precede process creation"));

        assertSame(rejection, assertThrowsExactly(IllegalStateException.class, attestor::attest));
    }

    @Test void rootCheckIOExceptionKeepsOriginalCauseAndPrecedesConfigurationRead() throws Exception {
        Fixture fixture = fixture("D098");
        Files.writeString(fixture.config, "invalid-config");
        var cause = new IOException("fixture is unreadable");
        var attestor = new PrivateQuestDbInstanceAttestor(fixture.spec, root -> { throw cause; },
                () -> true, script -> fail("Root policy failure must not start a process"));

        assertSame(cause, failure(attestor, "Cannot attest D098 private QuestDB data root and process").getCause());
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void configRequiresBothExactLoopbackBindingsBeforeStartingProcess(String code) throws Exception {
        Fixture fixture = fixture(code);
        for (String config : List.of(
                "http.net.bind.to=0.0.0.0:" + fixture.spec.qwpPort() + "\npg.net.bind.to=127.0.0.1:" + fixture.spec.pgPort(),
                "http.net.bind.to=127.0.0.1:" + fixture.spec.qwpPort() + "\npg.net.bind.to=localhost:" + fixture.spec.pgPort(),
                "http.net.bind.to=127.0.0.1:" + fixture.spec.pgPort() + "\npg.net.bind.to=127.0.0.1:" + fixture.spec.qwpPort(),
                "pg.net.bind.to=127.0.0.1:" + fixture.spec.pgPort(),
                "http.net.bind.to=127.0.0.1:" + fixture.spec.qwpPort())) {
            Files.writeString(fixture.config, config);
            var checked = new AtomicInteger();
            var attestor = new PrivateQuestDbInstanceAttestor(fixture.spec, root -> checked.incrementAndGet(),
                    () -> true, script -> fail("Invalid config must not start a process"));
            failure(attestor, code + " private data root does not bind the expected loopback listeners");
            assertEquals(1, checked.get());
        }
    }

    @Test void configKeepsBomCommentsWhitespaceAndLastAssignmentSemantics() throws Exception {
        Fixture fixture = fixture("D095");
        Files.writeString(fixture.config, """
                # http.net.bind.to=wrong
                http.net.bind.to=wrong
                ignored line
                =ignored
                \uFEFF http.net.bind.to = 127.0.0.1:19000
                 pg.net.bind.to = 127.0.0.1:18812
                """);

        assertDoesNotThrow(attestor(fixture, process(validRecords(fixture, 31)))::attest);
    }

    @Test void missingConfigWrapsIOExceptionAfterRootPolicyRuns() throws Exception {
        Fixture fixture = fixture("D095");
        Files.delete(fixture.config);
        var checked = new AtomicInteger();
        var attestor = new PrivateQuestDbInstanceAttestor(fixture.spec, root -> checked.incrementAndGet(),
                () -> true, script -> fail("Missing config must not start a process"));

        assertInstanceOf(NoSuchFileException.class,
                failure(attestor, "Cannot attest D095 private QuestDB data root and process").getCause());
        assertEquals(1, checked.get());
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void recordSetRequiresExactlyTwoArrayEntries(String code) throws Exception {
        Fixture fixture = fixture(code);
        var valid = validRecords(fixture, 31);
        for (Object records : List.of(Map.of("port", fixture.spec.pgPort()), List.of(),
                List.of(valid.getFirst()), List.of(valid.getFirst(), valid.getLast(), valid.getFirst()))) {
            failure(attestor(fixture, process(records)), code + " private listeners differ");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void emptyWhitespaceAndNullJsonKeepOriginalListenersRejection(String code) throws Exception {
        Fixture fixture = fixture(code);
        for (String output : List.of("", " \r\n\t", "null")) {
            failure(attestor(fixture, new FakeProcess(output.getBytes(StandardCharsets.UTF_8))),
                    code + " private listeners differ");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void listenerIdentityRequiresLoopbackPositivePidAndAllowedProcessName(String code) throws Exception {
        Fixture fixture = fixture(code);
        List<Map<String, Object>> changes = List.of(Map.of("address", "0.0.0.0"), Map.of("address", "::1"),
                Map.of("address", "localhost"), Map.of("pid", 0), Map.of("pid", -1),
                Map.of("name", "python.exe"), Map.of("name", "java"));
        for (var change : changes) {
            var records = validRecords(fixture, 31);
            records.getFirst().putAll(change);
            failure(attestor(fixture, process(records)),
                    code + " private listeners do not belong to one loopback QuestDB process");
        }
        for (String missing : List.of("address", "pid", "name")) {
            var records = validRecords(fixture, 31);
            records.getFirst().remove(missing);
            failure(attestor(fixture, process(records)),
                    code + " private listeners do not belong to one loopback QuestDB process");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void listenersMustBelongToSamePidAndHaveDistinctPorts(String code) throws Exception {
        Fixture fixture = fixture(code);
        var differentPid = validRecords(fixture, 31);
        differentPid.getLast().put("pid", 32);
        failure(attestor(fixture, process(differentPid)),
                code + " private listeners do not belong to one loopback QuestDB process");
        var duplicatePort = validRecords(fixture, 31);
        duplicatePort.getLast().put("port", fixture.spec.qwpPort());
        failure(attestor(fixture, process(duplicatePort)),
                code + " private listeners do not belong to one loopback QuestDB process");
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void wrongOrMissingListenerPortKeepsProcessChangedFailure(String code) throws Exception {
        Fixture fixture = fixture(code);
        var wrongPort = validRecords(fixture, 31);
        wrongPort.getLast().put("port", fixture.spec.pgPort() + 1);
        failure(attestor(fixture, process(wrongPort)), code + " private QuestDB process changed during this operation");
        var missingPort = validRecords(fixture, 31);
        missingPort.getLast().remove("port");
        failure(attestor(fixture, process(missingPort)), code + " private QuestDB process changed during this operation");
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void processMustDeclareAnExplicitDataRootArgument(String code) throws Exception {
        Fixture fixture = fixture(code);
        for (String command : List.of("questdb.exe", "questdb.exe --data-root " + fixture.root,
                "questdb.exe --d \"" + fixture.root + "\"", "questdb.exe -d")) {
            var records = validRecords(fixture, 31);
            records.getFirst().put("command", command);
            failure(attestor(fixture, process(records)), code + " private process has no explicit data root");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void wrongRootOrRepeatedDataRootArgumentIsRejected(String code) throws Exception {
        Fixture fixture = fixture(code);
        Path other = Files.createDirectories(temporary.resolve("other-root"));
        for (String command : List.of(command(other),
                command(fixture.root) + " -d \"" + fixture.root + "\"",
                command(fixture.root) + " -d \"" + other + "\"")) {
            var records = validRecords(fixture, 31);
            records.getLast().put("command", command);
            failure(attestor(fixture, process(records)), code + " private process belongs to a different data root");
        }
    }

    @Test void processDataRootWithNormalizedExistingSegmentsIsAccepted() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("var"));
        Path root = Files.createDirectories(workspace.resolve("root"));
        Path child = Files.createDirectories(root.resolve("child"));
        var fixture = fixture("D095", workspace, root);
        // This command form is portable even when the system temporary directory contains spaces.
        String directory = child.resolve("..").toString();
        String argument = directory.chars().anyMatch(Character::isWhitespace) ? "\"" + directory + "\"" : directory;
        var records = validRecords(fixture, 31);
        records.getFirst().put("command", "questdb.exe -d " + argument + " -n");

        assertDoesNotThrow(attestor(fixture, process(records))::attest);
    }

    @Test void nonexistentProcessDataRootWrapsOriginalFilesystemCause() throws Exception {
        Fixture fixture = fixture("D098");
        var records = validRecords(fixture, 31);
        records.getFirst().put("command", command(temporary.resolve("missing-root")));

        assertInstanceOf(NoSuchFileException.class,
                failure(attestor(fixture, process(records)),
                        "Cannot attest D098 private QuestDB data root and process").getCause());
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void pidIsStickyAcrossCallsAndRejectedChangeDoesNotReplaceIt(String code) throws Exception {
        Fixture fixture = fixture(code);
        var attestor = attestor(fixture, process(validRecords(fixture, 31)), process(validRecords(fixture, 32)),
                process(validRecords(fixture, 31)));

        assertDoesNotThrow(attestor::attest);
        failure(attestor, code + " private QuestDB process changed during this operation");
        assertDoesNotThrow(attestor::attest);
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void failedInitialAttestationDoesNotPinAnObservedPid(String code) throws Exception {
        Fixture fixture = fixture(code);
        var rejected = validRecords(fixture, 31);
        rejected.getLast().put("port", fixture.spec.pgPort() + 1);
        var attestor = attestor(fixture, process(rejected), process(validRecords(fixture, 32)));

        failure(attestor, code + " private QuestDB process changed during this operation");
        assertDoesNotThrow(attestor::attest);
    }

    @Test void independentlyCreatedInstancesDoNotSharePidStateEvenForTheSameSpec() throws Exception {
        Fixture fixture = fixture("D095");
        var first = attestor(fixture, process(validRecords(fixture, 31)), process(validRecords(fixture, 31)));
        var second = attestor(fixture, process(validRecords(fixture, 32)), process(validRecords(fixture, 32)));
        Fixture otherDataset = fixture("D098");
        var third = attestor(otherDataset, process(validRecords(otherDataset, 33)));

        assertDoesNotThrow(first::attest);
        assertDoesNotThrow(second::attest);
        assertDoesNotThrow(third::attest);
        assertDoesNotThrow(first::attest);
        assertDoesNotThrow(second::attest);
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void everyCallRechecksRootPolicyAndConfiguration(String code) throws Exception {
        Fixture fixture = fixture(code);
        var rootCalls = new AtomicInteger();
        var starts = new AtomicInteger();
        var attestor = new PrivateQuestDbInstanceAttestor(fixture.spec, root -> rootCalls.incrementAndGet(),
                () -> true, script -> {
            starts.incrementAndGet();
            return process(validRecords(fixture, 31));
        });
        assertDoesNotThrow(attestor::attest);
        Files.writeString(fixture.config, "http.net.bind.to=0.0.0.0:19000");

        failure(attestor, code + " private data root does not bind the expected loopback listeners");
        assertEquals(2, rootCalls.get());
        assertEquals(1, starts.get());
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void timedOutProcessIsDestroyedAfterExactlyTenSecondsWithoutReadingOutput(String code) throws Exception {
        Fixture fixture = fixture(code);
        FakeProcess process = process(validRecords(fixture, 31));
        process.finished = false;

        failure(attestor(fixture, process), code + " private listener attestation timed out");
        assertEquals(10, process.timeout);
        assertEquals(TimeUnit.SECONDS, process.unit);
        assertTrue(process.destroyed);
        assertEquals(-1, process.readLimit);
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void interruptedWaitRestoresInterruptFlagAndReturnsOriginalCancellationMessage(String code) throws Exception {
        Fixture fixture = fixture(code);
        FakeProcess process = process(validRecords(fixture, 31));
        process.interrupted = true;
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            var failure = assertThrowsExactly(CancellationException.class, attestor(fixture, process)::attest);
            assertEquals(code + " private listener attestation cancelled", failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            assertNull(failure.getCause());
            assertEquals(-1, process.readLimit);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void nonzeroExitAndOversizedOutputRetainAttestationFailure(String code) throws Exception {
        Fixture fixture = fixture(code);
        FakeProcess exited = process(validRecords(fixture, 31));
        exited.exit = 7;
        failure(attestor(fixture, exited), code + " private listener attestation failed");
        FakeProcess oversized = new FakeProcess(new byte[100_000]);
        failure(attestor(fixture, oversized), code + " private listener attestation failed");
        assertEquals(32_769, oversized.readLimit);
        assertEquals(32_769, oversized.bytesRead);
    }

    @Test void exactly32768OutputBytesAreAccepted() throws Exception {
        Fixture fixture = fixture("D098");
        byte[] output = Arrays.copyOf(JSON.writeValueAsBytes(validRecords(fixture, 31)), 32_768);
        int jsonLength = JSON.writeValueAsBytes(validRecords(fixture, 31)).length;
        Arrays.fill(output, jsonLength, output.length, (byte) ' ');
        FakeProcess process = new FakeProcess(output);

        assertDoesNotThrow(attestor(fixture, process)::attest);
        assertEquals(32_768, process.bytesRead);
    }

    @ParameterizedTest @ValueSource(strings = {"D095", "D098"})
    void malformedJsonPreservesParserCause(String code) throws Exception {
        Fixture fixture = fixture(code);
        var process = new FakeProcess("[not-json".getBytes(StandardCharsets.UTF_8));

        assertInstanceOf(IOException.class,
                failure(attestor(fixture, process), "Cannot attest " + code + " private QuestDB data root and process").getCause());
    }

    @Test void starterIOExceptionAndOutputIOExceptionKeepExactCause() throws Exception {
        Fixture fixture = fixture("D095");
        var launchCause = new IOException("launch failed");
        var attestor = new PrivateQuestDbInstanceAttestor(fixture.spec, null, () -> true,
                script -> { throw launchCause; });
        assertSame(launchCause, failure(attestor, "Cannot attest D095 private QuestDB data root and process").getCause());

        FakeProcess process = process(validRecords(fixture, 31));
        process.readFailure = new IOException("output failed");
        assertSame(process.readFailure, failure(attestor(fixture, process),
                "Cannot attest D095 private QuestDB data root and process").getCause());
    }

    private Fixture fixture(String code) throws IOException {
        Path workspace = Files.createDirectories(temporary.resolve(code).resolve("var"));
        Path root = Files.createDirectories(workspace.resolve("root with spaces"));
        return fixture(code, workspace, root);
    }

    private Fixture fixture(String code, Path workspace, Path root) throws IOException {
        var spec = new PrivateQuestDbInstanceAttestor.Spec(code, workspace, root,
                code.equals("D095") ? 18812 : 18822, code.equals("D095") ? 19000 : 19010);
        Path config = Files.createDirectories(root.resolve("conf")).resolve("server.conf");
        Files.writeString(config, "http.net.bind.to=127.0.0.1:" + spec.qwpPort()
                + "\npg.net.bind.to=127.0.0.1:" + spec.pgPort() + "\n", StandardCharsets.UTF_8);
        return new Fixture(spec, root, config);
    }

    private record Fixture(PrivateQuestDbInstanceAttestor.Spec spec, Path root, Path config) {}

    private static List<Map<String, Object>> validRecords(Fixture fixture, long pid) {
        return List.of(record(fixture.spec.qwpPort(), pid, "questdb.exe", command(fixture.root)),
                record(fixture.spec.pgPort(), pid, "java.exe", command(fixture.root)));
    }

    private static Map<String, Object> record(int port, long pid, String name, String command) {
        var record = new LinkedHashMap<String, Object>();
        record.put("port", port);
        record.put("address", "127.0.0.1");
        record.put("pid", pid);
        record.put("name", name);
        record.put("command", command);
        return record;
    }

    private static String command(Path root) { return "questdb.exe -d \"" + root + "\" -n"; }

    private static FakeProcess process(Object records) throws IOException {
        return new FakeProcess(JSON.writeValueAsBytes(records));
    }

    private static PrivateQuestDbInstanceAttestor attestor(Fixture fixture, FakeProcess... processes) {
        var pending = new ArrayDeque<>(List.of(processes));
        return new PrivateQuestDbInstanceAttestor(fixture.spec, null, () -> true,
                script -> pending.removeFirst());
    }

    private static IllegalStateException failure(PrivateQuestDbInstanceAttestor attestor, String message) {
        var failure = assertThrowsExactly(IllegalStateException.class, attestor::attest);
        assertEquals(message, failure.getMessage());
        return failure;
    }

    private static final class FakeProcess extends Process {
        private final byte[] output;
        boolean finished = true;
        boolean interrupted;
        boolean destroyed;
        int exit;
        long timeout = -1;
        TimeUnit unit;
        int readLimit = -1;
        int bytesRead;
        IOException readFailure;

        private FakeProcess(byte[] output) { this.output = output; }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                private final InputStream delegate = new ByteArrayInputStream(output);
                @Override public int read() throws IOException { return delegate.read(); }
                @Override public byte[] readNBytes(int length) throws IOException {
                    readLimit = length;
                    if (readFailure != null) throw readFailure;
                    byte[] read = delegate.readNBytes(length);
                    bytesRead = read.length;
                    return read;
                }
            };
        }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { throw new AssertionError("Attestation must use the bounded wait"); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            this.timeout = timeout;
            this.unit = unit;
            if (interrupted) throw new InterruptedException("test interruption");
            return finished;
        }
        @Override public int exitValue() { return exit; }
        @Override public void destroy() { destroyed = true; }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
    }
}
