package com.zoutrankil.data.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.derived.application.EtfMarketOverviewCacheOwnerGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual private SELECT-only process proof, admitted separately before any canonical publication. */
class EtfMarketOverviewCacheProcessLivePreviewTest {
    private static final Path COMMANDS = Path.of("artifacts/java-migration/D101/commands").toAbsolutePath().normalize();
    private static final List<String> TARGETS = List.of("etf_market_overview_daily_cache", "market_barometer_cache_coverage");
    private static final Map<String,Long> SOURCE_ROWS = Map.of("etf_share", 1532L, "etf_daily", 4274L, "etf_basic", 2958L);

    @Test void actualReadOnlyPreviewBindsThePythonBridgeToAnObservedExitedProcessTree() throws Exception {
        assumeTrue("readonly".equals(System.getenv("D101_PROCESS_PREVIEW")), "Enable reviewed D101_PROCESS_PREVIEW=readonly");
        Files.createDirectories(COMMANDS);
        Path output = COMMANDS.resolve("java-process-preview-20261006.json");
        assertFalse(Files.exists(output), "New-only process proof cannot replace an existing acceptance");
        Path invocationRoot = COMMANDS.resolve("java-process-preview-" + UUID.randomUUID());
        assertFalse(Files.exists(invocationRoot));
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("task_id", "D101"); evidence.put("status", "IN_PROGRESS");
        evidence.put("checked_at", Instant.now()); evidence.put("operation", "preview");
        evidence.put("publisher_invocations", 0); evidence.put("database_mutations", 0);
        evidence.put("ledger_creations", 0); evidence.put("ddl_operations", 0);
        evidence.put("invocation_artifact_root", invocationRoot.toString());
        try {
            String configuredPython = System.getenv("D101_PYTHON_EXECUTABLE");
            Path python = Path.of(configuredPython == null || configuredPython.isBlank()
                    ? "D:/work/fund_2/back-monitor/.venv/Scripts/python.exe" : configuredPython);
            var config = new EtfMarketOverviewCacheOwnerGateway.Config(python,
                    Path.of("tools/d101_etf_cache_owner_bridge.py"), invocationRoot,
                    Path.of("var/d101-isolated-questdb"), 23388, Duration.ofSeconds(90), 50000);
            evidence.put("bridge_config_native_paths", Map.of("python_executable", config.pythonExecutable().toString(),
                    "bridge_script", config.bridgeScript().toString(), "artifact_root", config.artifactRoot().toString(),
                    "private_root", config.privateRoot().toString(), "expected_pid", config.expectedPid(),
                    "timeout", config.timeout().toString(), "max_source_rows", config.maxSourceRows()));
            var gateway = new EtfMarketOverviewCacheOwnerGateway(config, new com.zoutrankil.data.derived.storage.EtfMarketOverviewOwnerProcess(config.pythonExecutable(), config.bridgeScript(), config.timeout()));
            var envelope = gateway.preview(LocalDate.of(2026, 9, 17));
            JsonNode preview = envelope.previewResponse();
            evidence.put("preview", preview); evidence.put("preview_path", envelope.previewPath().toString());
            evidence.put("preview_sha256", envelope.previewSha256());
            assertEquals("PREVIEW_VERIFIED", preview.required("status").asText());
            assertEquals("preview", preview.required("operation").asText());
            assertFalse(flag(preview, "owner_invoked")); assertTrue(flag(preview, "owner_sender_stopped"));
            assertFalse(flag(preview, "formal_mutated")); assertFalse(flag(preview, "source_mutated"));
            assertEquals(0, number(preview, "ddl_operations"));
            assertTrue(preview.required("owner_submissions").isArray()); assertTrue(preview.required("owner_submissions").isEmpty());
            assertEquals(23388, number(preview.required("process_attestation"), "pid"));
            assertTrue(envelope.knownSourceDate()); assertNotNull(envelope.cache());
            assertTrue(envelope.sourceRows() > 0 && envelope.sourceRows() <= config.maxSourceRows());
            for (String table : TARGETS) {
                var snapshot = preview.required("target_snapshots").required(table);
                assertTrue(flag(snapshot, "settled")); var physical = snapshot.required("physical"); var wal = snapshot.required("wal");
                assertTrue(physical.required("table_txn").isNull(), "Original uninitialized physical txn must remain raw null");
                var rows = physical.required("table_row_count");
                assertTrue(rows.isNull() || rows.isIntegralNumber() && rows.canConvertToLong() && rows.longValue() == 0L);
                assertEquals(0, number(wal, "sequencerTxn")); assertEquals(0, number(wal, "writerTxn"));
                assertEquals(0, number(wal, "bufferedTxnSize")); assertFalse(flag(wal, "suspended"));
                assertEquals(0, number(physical, "wal_pending_row_count")); assertFalse(flag(physical, "table_suspended"));
                assertEquals(0, number(preview.required("target_actual_row_counts"), table));
                assertEquals(0, number(preview.required("target_actual_row_counts_after"), table));
            }
            assertEquals(preview.required("target_actual_row_counts"), preview.required("target_actual_row_counts_after"));
            for (var expected : SOURCE_ROWS.entrySet()) {
                var snapshot = preview.required("source_snapshots").required(expected.getKey());
                assertTrue(flag(snapshot, "settled")); var physical = snapshot.required("physical"); var wal = snapshot.required("wal");
                assertEquals(expected.getValue().longValue(), number(physical, "table_row_count"));
                number(physical, "table_txn");
                assertEquals(number(wal, "sequencerTxn"), number(wal, "writerTxn"));
                assertEquals(0, number(physical, "wal_pending_row_count")); assertEquals(0, number(wal, "bufferedTxnSize"));
                assertFalse(flag(physical, "table_suspended")); assertFalse(flag(wal, "suspended"));
            }
            evidence.put("source_full_table_rows", SOURCE_ROWS);
            assertEquals(envelope.previewSha256(), sha(Files.readAllBytes(envelope.previewPath())));
            String responseName = envelope.previewPath().getFileName().toString();
            assertTrue(responseName.endsWith(".response.json"));
            String prefix = responseName.substring(0, responseName.length() - ".response.json".length());
            Path startedPath = invocationRoot.resolve(prefix + ".process-started.json");
            Path stoppedPath = invocationRoot.resolve(prefix + ".process-stopped.json");
            Path requestPath = invocationRoot.resolve(prefix + ".request.json");
            var started = read(startedPath); var stopped = read(stoppedPath);
            evidence.put("process_started", proof(startedPath, started)); evidence.put("process_stopped", proof(stoppedPath, stopped));
            evidence.put("request", proof(requestPath, read(requestPath)));
            String invocation = preview.required("invocation_id").asText();
            assertEquals(invocation, started.required("invocation_id").asText()); assertEquals(invocation, stopped.required("invocation_id").asText());
            assertEquals(1, number(started, "process_tree_version")); assertEquals(1, number(stopped, "process_tree_version"));
            long rootPid = number(started, "pid"); assertTrue(rootPid > 0); assertEquals(rootPid, number(stopped, "pid"));
            String rootBirth = started.required("process_start").asText(); assertNotEquals("UNKNOWN", rootBirth); Instant.parse(rootBirth);
            assertEquals(rootBirth, stopped.required("process_start").asText());
            assertEquals(sha(Files.readAllBytes(requestPath)), started.required("request_sha256").asText());
            assertEquals(started.required("request_sha256"), stopped.required("request_sha256"));
            assertEquals("", started.required("intent_sha256").asText()); assertEquals(started.required("intent_sha256"), stopped.required("intent_sha256"));
            assertTrue(flag(stopped, "exit_observed")); assertTrue(flag(stopped, "process_tree_stopped"));
            assertTrue(flag(stopped, "bridge_identity_proved")); assertFalse(flag(stopped, "forced")); assertEquals(0, number(stopped, "exit_code"));
            endedIdentity(rootPid, rootBirth);
            var children = stopped.required("observed_children"); assertTrue(children.isArray() && children.size() <= 64);
            var childProofs = new ArrayList<Map<String,Object>>(); var identities = new HashSet<String>();
            var parents = new LinkedHashMap<Long,Long>();
            long bridgePid = number(preview.required("bridge_process"), "pid"); assertTrue(bridgePid > 0);
            assertTrue(flag(preview.required("bridge_process"), "terminal_response_written"));
            assertEquals(0, number(preview.required("bridge_process"), "exit_code"));
            assertEquals(bridgePid, number(stopped, "actual_bridge_pid"));
            int bridgeMatches = bridgePid == rootPid ? 1 : 0;
            for (var child : children) {
                long childPid = number(child, "pid"); String childBirth = child.required("process_start").asText();
                assertTrue(childPid > 0); assertNotEquals("UNKNOWN", childBirth); Instant.parse(childBirth);
                assertTrue(identities.add(childPid + "@" + childBirth)); assertTrue(flag(child, "exit_observed")); endedIdentity(childPid, childBirth);
                Path childPath = Path.of(child.required("evidence_path").asText()).toAbsolutePath().normalize();
                assertTrue(childPath.startsWith(invocationRoot)); var observed = read(childPath);
                assertEquals(sha(Files.readAllBytes(childPath)), child.required("evidence_sha256").asText());
                assertEquals(invocation, observed.required("invocation_id").asText()); assertEquals(1, number(observed, "process_tree_version"));
                assertTrue(flag(observed, "observed_descendant")); assertEquals(rootPid, number(observed, "pid"));
                assertEquals(rootBirth, observed.required("process_start").asText());
                assertEquals(started.required("request_sha256"), observed.required("request_sha256"));
                assertEquals(started.required("intent_sha256"), observed.required("intent_sha256"));
                assertEquals(childPid, number(observed, "child_pid")); assertEquals(childBirth, observed.required("child_process_start").asText());
                assertEquals(child.required("parent_pid"), observed.required("parent_pid"));
                var parent = child.required("parent_pid");
                assertTrue(parent.isNull() || parent.isIntegralNumber() && parent.canConvertToLong() && parent.longValue() > 0);
                assertFalse(parents.containsKey(childPid), "An observed PID with multiple OS births is ambiguous");
                parents.put(childPid, parent.isNull() ? null : parent.longValue());
                if (childPid == bridgePid) bridgeMatches++;
                childProofs.add(proof(childPath, observed));
            }
            assertEquals(1, bridgeMatches, "Response PID must identify exactly one actual root or observed child birth identity");
            var lineage = new HashSet<Long>(); long ancestor = bridgePid;
            while (ancestor != rootPid) {
                assertTrue(lineage.size() < 64 && lineage.add(ancestor), "Bridge parent lineage must be finite and acyclic");
                Long parent = parents.get(ancestor); assertNotNull(parent, "Actual bridge parent lineage must reach the observed launcher");
                ancestor = parent;
            }
            evidence.put("process_observed_children", childProofs); evidence.put("actual_bridge_pid", bridgePid);
            evidence.put("launcher_pid", rootPid); evidence.put("bridge_is_launcher", bridgePid == rootPid);
            evidence.put("actual_process_tree_exit_proved", true);
            try (var files = Files.list(invocationRoot)) {
                var names = files.map(path -> path.getFileName().toString()).toList();
                assertTrue(names.stream().noneMatch(name -> name.contains("publish") || name.endsWith(".intent.json")));
            }
            evidence.put("status", "VERIFIED_READONLY_PROCESS_TREE_PREVIEW"); saveNew(output, evidence);
        } catch (Exception | AssertionError failure) {
            evidence.put("status", "FAILED"); evidence.put("error_class", failure.getClass().getSimpleName());
            saveNew(COMMANDS.resolve("java-process-preview-failed-" + UUID.randomUUID() + ".json"), evidence); throw failure;
        }
    }

    private static void endedIdentity(long pid, String birth) {
        var current = ProcessHandle.of(pid);
        if (current.isPresent() && current.get().isAlive()) {
            var actualBirth = current.get().info().startInstant();
            assertTrue(actualBirth.isPresent(), "A live reused PID needs an independently observed OS birth");
            assertNotEquals(Instant.parse(birth), actualBirth.get(), "The actual observed process is still alive");
        }
    }
    private static Map<String,Object> proof(Path path, JsonNode content) throws Exception {
        return Map.of("path", path.toAbsolutePath().normalize().toString(), "sha256", sha(Files.readAllBytes(path)), "content", content);
    }
    private static JsonNode read(Path path) throws Exception {
        assertTrue(Files.isRegularFile(path)); byte[] bytes = Files.readAllBytes(path);
        assertTrue(bytes.length > 0 && bytes.length <= 1024 * 1024); return JobDefinitionJson.mapper().readTree(bytes);
    }
    private static long number(JsonNode row, String field) {
        var value = row.required(field); assertTrue(value.isIntegralNumber() && value.canConvertToLong());
        long number = value.longValue(); assertTrue(number >= 0); return number;
    }
    private static boolean flag(JsonNode row, String field) { var value = row.required(field); assertTrue(value.isBoolean()); return value.booleanValue(); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void saveNew(Path path, Object value) throws Exception {
        Files.writeString(path, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
