package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.EtfMarketOverviewDailyCacheMapper;
import com.zoutrankil.data.mapper.MarketBarometerCacheCoverageMapper;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** Explicit investigation and SELECT-only settlement of the original failed HIT; never invokes a publisher. */
class EtfMarketOverviewCacheHitReadOnlyRecoveryTest {
    private static final String CACHE = "etf_market_overview_daily_cache";
    private static final String COVERAGE = "market_barometer_cache_coverage";
    private static final String RUN = "d101-1be2292a-c0b0-4810-a22b-9747bef68071";
    private static final String INVOCATION = "00f2f51a-06ff-4624-870a-903f717ae4d3";
    private static final LocalDate START = LocalDate.of(2026, 9, 17);
    private static final LocalDate SECOND = START.plusDays(1);
    private static final List<String> TABLES = List.of("etf_share", "etf_daily", "etf_basic", CACHE, COVERAGE);
    private static final Path COMMANDS = Path.of("artifacts/java-migration/D101/commands").toAbsolutePath().normalize();
    private static final Path FIRST = COMMANDS.resolve("java-readthrough-first-20261006.json");
    private static final Path FAILED = COMMANDS.resolve("java-readthrough-hit-failed-cc76bb4f-3b56-4597-bdd3-013826157690.json");

    @Test void investigateActualStoppedTreeAndReconcileOriginalHitWithoutPublicationOrReplay() throws Exception {
        assumeTrue("readonly".equals(System.getenv("D101_HIT_RECOVERY")), "Enable only admitted D101_HIT_RECOVERY=readonly");
        var json = JobDefinitionJson.mapper();
        Path output = COMMANDS.resolve("java-hit-readonly-recovery-20261006.json");
        assertFalse(Files.exists(output), "Recovery evidence is new-only and cannot overwrite a prior result");
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("task_id", "D101"); evidence.put("stage", "hit_readonly_recovery"); evidence.put("status", "IN_PROGRESS");
        evidence.put("checked_at", Instant.now()); evidence.put("original_run_id", RUN);
        evidence.put("questdb_mutations", 0); evidence.put("publisher_invocations", 0); evidence.put("formal_written_rows", 0);
        evidence.put("automatic_replays", 0); evidence.put("ddl_operations", 0);
        evidence.put("does_not_certify_two_owner_hits", true);
        try (var pool = pool()) {
            var first = read(FIRST); var failed = read(FAILED);
            Path nativeCheckPath = COMMANDS.resolve("java-hit-failed-native-check-20261006.json");
            Path sqliteCheckPath = COMMANDS.resolve("java-hit-failed-sqlite-readonly-20261006.json");
            var nativeCheck = read(nativeCheckPath); var sqliteCheck = read(sqliteCheckPath);
            assertEquals("VERIFIED_FAILED_HIT_PROCESS_IDS_ABSENT_PRIVATE_LISTENERS_UNCHANGED", nativeCheck.required("status").asText());
            assertEquals("VERIFIED_READONLY_FAILED_HIT_SQLITE_STATE", sqliteCheck.required("status").asText());
            assertEquals(RUN, nativeCheck.required("run_id").asText()); assertEquals(RUN, sqliteCheck.required("run_id").asText());
            assertEquals("6ac89914f04956f2f2784581d46cf15c997329f3d591efd1814c62506ef8e262", sha(sqliteCheckPath));
            assertTrue(nativeCheck.required("current_matches").isEmpty()); assertEquals(7, nativeCheck.required("observed_process_ids").size());
            assertEquals(3, sqliteCheck.required("entries").size()); assertEquals(12, sqliteCheck.required("events").size());
            assertEquals("VERIFIED_CANONICAL_FIRST_MISSES", first.required("status").asText());
            assertEquals("FAILED", failed.required("status").asText());
            assertEquals(RUN, failed.required("materialization").required("result").required("runId").asText());
            assertEquals("IN_DOUBT", failed.required("materialization").required("result").required("state").asText());
            assertEquals("IllegalStateException", failed.required("materialization").required("result").required("errorCode").asText());
            assertEquals(sha(FIRST), failed.required("first_artifact_sha256").asText());
            Path ledgerPath = Path.of(first.required("ledger_path").asText()).toAbsolutePath().normalize();
            assertTrue(Files.isRegularFile(ledgerPath));
            assertEquals(ledgerPath, Path.of(failed.required("ledger_path").asText()).toAbsolutePath().normalize());
            var config = json.treeToValue(first.required("bridge_config"), EtfMarketOverviewCacheOwnerGateway.Config.class);
            assertEquals(23388, config.expectedPid());
            assertEquals(Path.of("var/d101-isolated-questdb").toAbsolutePath().normalize(), config.privateRoot());
            var gateway = new EtfMarketOverviewCacheOwnerGateway(config);
            Path responsePath = config.artifactRoot().resolve("java-owner-publish-" + INVOCATION + ".response.json");
            Path stoppedPath = responsePath.resolveSibling(responsePath.getFileName().toString().replace(".response.json", ".process-stopped.json"));
            var response = read(responsePath); var stopped = read(stoppedPath);
            assertEquals("VERIFIED_READTHROUGH", response.required("status").asText());
            assertEquals("publish", response.required("operation").asText());
            assertTrue(response.required("owner_invoked").booleanValue());
            assertEquals(1, response.required("actual_owner_hits").longValue());
            assertEquals(0, response.required("actual_owner_misses").longValue());
            assertEquals(0, response.required("cache_submitted_rows").longValue());
            assertEquals(0, response.required("coverage_submitted_rows").longValue());
            assertTrue(response.required("owner_submissions").isEmpty());
            assertEquals(response.required("target_snapshots"), response.required("target_snapshots_after"));
            assertEquals(response.required("source_snapshots"), response.required("source_snapshots_after"));
            assertFalse(stopped.required("process_tree_stopped").booleanValue());
            assertFalse(stopped.required("bridge_identity_proved").booleanValue());
            assertTrue(stopped.required("exit_observed").booleanValue());
            assertEquals(nativeCheck.required("old_stop_sha256").asText(), sha(stoppedPath));
            var known = new LinkedHashMap<Long,JsonNode>(); var unknown = new ArrayList<JsonNode>();
            for (var child : stopped.required("observed_children")) {
                Path proof = Path.of(child.required("evidence_path").asText()).toAbsolutePath().normalize();
                assertEquals(config.artifactRoot(), proof.getParent());
                assertEquals(child.required("evidence_sha256").asText(), sha(proof));
                var observed = read(proof);
                assertEquals(INVOCATION, observed.required("invocation_id").asText());
                assertEquals(child.required("pid").longValue(), observed.required("child_pid").longValue());
                if ("UNKNOWN".equals(child.required("process_start").asText())) {
                    assertFalse(child.required("exit_observed").booleanValue()); unknown.add(child);
                } else {
                    assertTrue(child.required("exit_observed").booleanValue());
                    assertNull(known.put(child.required("pid").longValue(), child));
                }
            }
            assertEquals(6, known.size()); assertEquals(2, unknown.size());
            assertEquals(Set.of(1960L, 27172L), new HashSet<>(unknown.stream().map(n -> n.required("pid").longValue()).toList()));
            for (var child : unknown) assertTrue(known.containsKey(child.required("pid").longValue()));
            assertEquals(27172, response.required("bridge_process").required("pid").longValue());
            assertEquals(27172, stopped.required("actual_bridge_pid").longValue());
            assertNativeAbsent(stopped.required("pid").longValue());
            for (long pid : known.keySet()) assertNativeAbsent(pid);
            var immutableBefore = historicalHashes(config, response);
            var publisherBefore = publisherInventory(config, ledgerPath);
            evidence.put("first_artifact", FIRST.toString()); evidence.put("first_artifact_sha256", sha(FIRST));
            evidence.put("failed_hit_artifact", FAILED.toString()); evidence.put("failed_hit_artifact_sha256", sha(FAILED));
            evidence.put("old_publish_response", response); evidence.put("old_publish_response_path", responsePath.toString());
            evidence.put("old_stopped_tree", stopped); evidence.put("old_stopped_tree_path", stoppedPath.toString());
            evidence.put("historical_immutable_hashes_before", immutableBefore);
            evidence.put("publisher_inventory_before", publisherBefore); evidence.put("ledger_path", ledgerPath.toString());
            evidence.put("bridge_config", config);
            evidence.put("native_preflight_artifact", nativeCheckPath.toString());
            evidence.put("native_preflight_sha256", sha(nativeCheckPath));
            evidence.put("readonly_sqlite_preflight_artifact", sqliteCheckPath.toString());
            evidence.put("readonly_sqlite_preflight_sha256", sha(sqliteCheckPath));

            var ledger = SyncRunLedger.openReadOnly(ledgerPath); var original = ledger.getRun(RUN);
            assertEquals(sqliteCheck.required("run").required("frozen_json").asText(), original.frozenJson());
            var request = json.readTree(original.frozenJson());
            assertEquals("INCREMENTAL", request.required("mode").asText());
            assertEquals(START.toString(), request.required("from").asText());
            assertEquals(SECOND.toString(), request.required("to").asText());
            assertEquals("2026-09-18", request.required("parameters").required("checkpoint_before").asText());
            assertEquals(first.required("source_version_after").asText(), request.required("parameters").required("source_version").asText());
            assertEquals(first.required("target_id").asText(), original.targetId());
            var entriesBefore = ledger.entries(RUN, null, 100);
            assertEquals(3, entriesBefore.size());
            assertTrue(entriesBefore.stream().allMatch(entry -> entry.state() == SyncRunState.IN_DOUBT));
            assertEquals(1, entriesBefore.stream().filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).count());
            var slice = entriesBefore.stream().filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).findFirst().orElseThrow();
            assertEquals(5, slice.revision());
            var eventsBefore = ledger.events(slice.id(), -1, 100);
            assertEquals(List.of(SyncRunState.PENDING, SyncRunState.RUNNING, SyncRunState.FETCHED,
                    SyncRunState.VALIDATED, SyncRunState.SUBMITTED, SyncRunState.IN_DOUBT),
                    eventsBefore.stream().map(SyncRunLedger.Event::state).toList());
            var submitted = eventsBefore.get(4); assertEquals(4, submitted.revision());
            var payload = json.readTree(submitted.payloadJson());
            assertEquals(START.toString(), payload.required("cursor").asText());
            assertEquals(response.required("source_fingerprint").asText(), payload.required("sourceFingerprint").asText());
            var originalPreview = json.readTree(payload.required("responseEvidence").asText());
            assertEquals(response.required("preview_path").asText(), originalPreview.required("preview_path").asText());
            assertEquals(response.required("preview_sha256").asText(), originalPreview.required("preview_sha256").asText());
            var lock = new DatasetIntervalLock(ledgerPath); var scope = DatasetIntervalLock.Scope.allDates(CACHE);
            var retained = lock.findOwned(RUN, scope); assertNotNull(retained); assertTrue(retained.inDoubt());
            assertEquals(scope, retained.scope()); assertFalse(ledger.cancellationRequested(RUN));
            evidence.put("entries_before", entriesBefore); evidence.put("slice_events_before", eventsBefore);
            evidence.put("retained_lease_before", retained); evidence.put("frozen_json", request);

            var jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(20);
            var before = metadata(jdbc); assertEquals(savedMetadata(first.required("tables_after")), before);
            assertEquals(savedMetadata(failed.required("tables_before")), before);
            var reader = new QuestDbBoundedReader(jdbc);
            var cache = new EtfMarketOverviewDailyCacheReadRepository(reader);
            var coverage = new MarketBarometerCacheCoverageReadRepository(reader);
            var expectedCache = new ArrayList<EtfMarketOverviewDailyCache>();
            var expectedReceipts = new ArrayList<MarketBarometerCacheCoverage>();
            for (var row : first.required("stored_cache_rows")) expectedCache.add(cache(row));
            for (var row : first.required("stored_receipts")) expectedReceipts.add(receipt(row));
            assertEquals(2, expectedCache.size()); assertEquals(2, expectedReceipts.size());
            assertTypedState(jdbc, cache, coverage, expectedCache, expectedReceipts);
            assertEquals(before, metadata(jdbc), "SELECT-only initial verification must retain all five frontiers");
            evidence.put("tables_before", before);

            // This explicit investigation only adds new process evidence; it never edits old UNKNOWN proof or sends.
            var investigations = gateway.investigateStoppedTree(ledgerPath, RUN);
            assertEquals(1, investigations.size(), "Only the original failed HIT invocation needs an investigation");
            var newProofs = new ArrayList<Map<String,Object>>();
            for (var file : investigations) {
                assertEquals(config.artifactRoot(), file.toAbsolutePath().normalize().getParent());
                assertTrue(file.getFileName().toString().contains(".process-reconciled-"));
                assertTrue(file.getFileName().toString().endsWith(".json"));
                var proof = read(file);
                assertInvestigation(proof, ledgerPath, response, stopped, config);
                newProofs.add(Map.of("path", file.toString(), "sha256", sha(file), "proof", proof));
            }
            assertEquals(immutableBefore, historicalHashes(config, response));
            assertEquals(publisherBefore, publisherInventory(config, ledgerPath));
            assertTrue(gateway.writerStopped(ledgerPath, RUN), "Original native writer tree must be independently proven stopped");
            evidence.put("new_process_investigations", newProofs);
            evidence.put("original_writer_stopped_after_explicit_investigation", true);

            var owner = new EtfMarketOverviewDailyCacheJobService(gateway, jdbc, ledgerPath.toString());
            var status = owner.reconcile(RUN, true); // Actual fresh source/cache/receipt/WAL SELECT proof plus SQLite transitions.
            assertEquals(SyncRunState.VERIFIED, status.state());
            assertEquals(1, status.verifiedPublicationUnits(), "One real submitted SLICE; never invent the second owner hit");
            assertEquals(0, status.unresolvedSlices()); assertFalse(status.cancellationRequested());
            assertNull(lock.findOwned(RUN, scope)); assertTrue(gateway.writerStopped(ledgerPath, RUN));
            assertEquals(original, ledger.getRun(RUN), "Original run/frozen identity must not change");
            var entriesAfter = ledger.entries(RUN, null, 100);
            assertEquals(3, entriesAfter.size()); assertTrue(entriesAfter.stream().allMatch(entry -> entry.state() == SyncRunState.VERIFIED));
            assertEquals(new HashSet<>(entriesBefore.stream().map(SyncRunLedger.Entry::id).toList()),
                    new HashSet<>(entriesAfter.stream().map(SyncRunLedger.Entry::id).toList()));
            for (var entry : entriesAfter) {
                var proof = json.readTree(entry.payloadJson()).required("verification");
                long units = entry.kind() == SyncRunLedger.Kind.SLICE ? 1 : 2;
                assertTrue(proof.required("passed").booleanValue()); assertTrue(proof.required("writerStopped").booleanValue());
                assertEquals(units, proof.required("expectedRows").longValue());
                assertEquals(units, proof.required("matchedRows").longValue());
            }
            var eventsAfter = ledger.events(slice.id(), -1, 100);
            assertEquals(eventsBefore, eventsAfter.subList(0, eventsBefore.size()));
            assertEquals(7, eventsAfter.size()); assertEquals(SyncRunState.VERIFIED, eventsAfter.getLast().state());
            assertEquals(6, eventsAfter.getLast().revision());
            var verification = json.readTree(eventsAfter.getLast().payloadJson()).required("verification");
            assertTrue(verification.required("passed").booleanValue()); assertTrue(verification.required("writerStopped").booleanValue());
            assertEquals(1, verification.required("matchedRows").longValue());
            assertTypedState(jdbc, cache, coverage, expectedCache, expectedReceipts);
            var after = metadata(jdbc); assertEquals(before, after);
            assertEquals(immutableBefore, historicalHashes(config, response));
            assertEquals(publisherBefore, publisherInventory(config, ledgerPath));
            assertFalse(Files.exists(COMMANDS.resolve("java-readthrough-hit-20261006.json")), "Recovery is not HIT2 acceptance");
            evidence.put("management_status_after", status); evidence.put("entries_after", entriesAfter);
            evidence.put("slice_events_after", eventsAfter); evidence.put("retained_interval_leases_after", 0);
            evidence.put("tables_after", after); evidence.put("stored_cache_rows", cacheValues(expectedCache));
            evidence.put("stored_receipts", receiptValues(expectedReceipts));
            evidence.put("two_day_cache_and_receipt_field_comparisons", 80);
            evidence.put("pg_reference_double_rawbit_comparisons", 16);
            evidence.put("independent_jdbc_double_rawbit_comparisons", 8); evidence.put("double_tolerance", 0);
            evidence.put("historical_immutable_hashes_after", historicalHashes(config, response));
            evidence.put("publisher_inventory_after", publisherInventory(config, ledgerPath));
            evidence.put("further_two_hit_run_required", true); evidence.put("increment_may_start", false);
            evidence.put("status", "VERIFIED_READONLY_ORIGINAL_HIT_RECOVERY");
            saveNew(output, evidence);
        } catch (Exception | AssertionError failure) {
            evidence.put("status", "FAILED"); evidence.put("error_class", failure.getClass().getSimpleName());
            saveNew(COMMANDS.resolve("java-hit-readonly-recovery-failed-" + UUID.randomUUID() + ".json"), evidence);
            throw failure;
        }
    }

    private record Snapshot(String table, long id, String directory, Long physicalTxn, Long metadataRows,
            long actualRows, long sequenceTxn, long writerTxn, long pendingRows, long bufferedTxns,
            boolean tableSuspended, boolean walSuspended) {}

    private static Map<String,Snapshot> savedMetadata(JsonNode node) throws Exception {
        var result = new LinkedHashMap<String,Snapshot>();
        for (String table : TABLES) result.put(table, JobDefinitionJson.mapper().treeToValue(node.required(table), Snapshot.class));
        return result;
    }
    private static Map<String,Snapshot> metadata(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<String,Snapshot>();
        for (String table : TABLES) {
            long count = Objects.requireNonNull(jdbc.queryForObject("SELECT count() FROM " + table, Long.class));
            var value = jdbc.query("SELECT t.id,t.directoryName,t.table_txn,t.table_row_count,t.walEnabled,t.table_suspended,"
                    + "t.wal_pending_row_count,w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended "
                    + "FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='" + table + "'",
                    (ResultSetExtractor<Snapshot>) rs -> {
                        assertTrue(rs.next()); assertTrue(rs.getBoolean("walEnabled")); assertFalse(rs.wasNull());
                        var row = new Snapshot(table, number(rs, "id"), rs.getString("directoryName"),
                                number(rs, "table_txn"), number(rs, "table_row_count"), count,
                                number(rs, "sequencerTxn"), number(rs, "writerTxn"), number(rs, "wal_pending_row_count"),
                                number(rs, "bufferedTxnSize"), flag(rs, "table_suspended"), flag(rs, "suspended"));
                        assertFalse(rs.next()); assertEquals(count, row.metadataRows().longValue());
                        assertEquals(row.sequenceTxn(), row.writerTxn()); assertEquals(0, row.pendingRows());
                        assertEquals(0, row.bufferedTxns()); assertFalse(row.tableSuspended()); assertFalse(row.walSuspended());
                        return row;
                    });
            result.put(table, value);
        }
        return Collections.unmodifiableMap(result);
    }
    private static void assertTypedState(JdbcTemplate jdbc, EtfMarketOverviewDailyCacheReadRepository cache,
            MarketBarometerCacheCoverageReadRepository coverage, List<EtfMarketOverviewDailyCache> rows,
            List<MarketBarometerCacheCoverage> receipts) {
        var cachePage = cache.findRange(START, SECOND.plusDays(1), 3, null);
        var receiptPage = coverage.findRange(START, SECOND.plusDays(1), 3, null);
        assertEquals(2, cachePage.rows().size()); assertNull(cachePage.nextCursor());
        assertEquals(2, receiptPage.rows().size()); assertNull(receiptPage.nextCursor());
        for (int i = 0; i < rows.size(); i++) {
            var expected = rows.get(i); var expectedReceipt = receipts.get(i);
            compare(expected, cachePage.rows().get(i));
            var key = cache.findKey(expected.key()); assertEquals(1, key.rows().size()); assertNull(key.nextCursor());
            compare(expected, key.rows().getFirst());
            assertEquals(expectedReceipt, receiptPage.rows().get(i));
            var receiptKey = coverage.findKey(expectedReceipt.key());
            assertEquals(List.of(expectedReceipt), receiptKey.rows()); assertNull(receiptKey.nextCursor());
            var stored = jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT total_share,total_size_yi FROM " + CACHE
                        + " WHERE trade_date=? AND source_version=? LIMIT 2");
                statement.setQueryTimeout(20); statement.setMaxRows(2);
                statement.setTimestamp(1, Timestamp.from(expected.tradeDate().atStartOfDay().toInstant(ZoneOffset.UTC)), utc());
                statement.setString(2, expected.sourceVersion()); return statement;
            }, (rs, index) -> Arrays.asList(decimal(rs, "total_share"), decimal(rs, "total_size_yi")));
            assertEquals(1, stored.size()); bit(expected.totalShare(), stored.getFirst().get(0));
            bit(expected.totalSizeYi(), stored.getFirst().get(1));
        }
    }
    private static void compare(EtfMarketOverviewDailyCache a, EtfMarketOverviewDailyCache b) {
        assertEquals(a.key(), b.key()); assertEquals(a.etfCount(), b.etfCount());
        bit(a.totalShare(), b.totalShare()); bit(a.totalSizeYi(), b.totalSizeYi());
    }
    private static void bit(Double a, Double b) {
        if (a == null || b == null) assertEquals(a, b);
        else assertEquals(Double.doubleToRawLongBits(a), Double.doubleToRawLongBits(b));
    }
    private static EtfMarketOverviewDailyCache cache(JsonNode n) {
        assertEquals(5, n.size());
        return new EtfMarketOverviewDailyCache(LocalDate.parse(n.required("trade_date").asText()),
                integer(n.required("etf_count")), decimal(n.required("total_share")), decimal(n.required("total_size_yi")),
                n.required("source_version").asText());
    }
    private static MarketBarometerCacheCoverage receipt(JsonNode n) {
        assertEquals(5, n.size());
        return new MarketBarometerCacheCoverage(LocalDate.parse(n.required("trade_date").asText()), n.required("dataset_id").asText(),
                n.required("source_version").asText(), integer(n.required("row_count")), n.required("content_digest").asText());
    }
    private static List<Map<String,Object>> cacheValues(List<EtfMarketOverviewDailyCache> rows) {
        var mapper = new EtfMarketOverviewDailyCacheMapper(); return rows.stream().map(row -> mapper.values(row).asMap()).toList();
    }
    private static List<Map<String,Object>> receiptValues(List<MarketBarometerCacheCoverage> rows) {
        var mapper = new MarketBarometerCacheCoverageMapper(); return rows.stream().map(row -> mapper.values(row).asMap()).toList();
    }
    private static Map<String,String> historicalHashes(EtfMarketOverviewCacheOwnerGateway.Config config, JsonNode response) throws Exception {
        var result = new TreeMap<String,String>();
        for (Path path : List.of(FIRST, FAILED, Path.of(response.required("preview_path").asText()))) result.put(path.toString(), sha(path));
        String prefix = "java-owner-publish-" + INVOCATION;
        try (var paths = Files.list(config.artifactRoot())) {
            for (var path : paths.filter(p -> p.getFileName().toString().startsWith(prefix)
                    && !p.getFileName().toString().contains(".process-reconciled-")).toList()) result.put(path.toString(), sha(path));
        }
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,String> publisherInventory(EtfMarketOverviewCacheOwnerGateway.Config config, Path ledger) throws Exception {
        var result = new TreeMap<String,String>();
        for (Path directory : List.of(config.artifactRoot(), ledger.getParent().resolve("d101-owner-intent-claims"), COMMANDS)) {
            assertTrue(Files.isDirectory(directory));
            try (var paths = Files.list(directory)) {
                for (var path : paths.filter(Files::isRegularFile).filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".intent.json") || name.endsWith(".claim.json") || name.startsWith("owner-invocation-")
                            || name.startsWith("java-owner-publish-") && !name.contains(".process-reconciled-");
                }).toList()) result.put(path.toString(), sha(path));
            }
        }
        return Collections.unmodifiableMap(result);
    }
    private static void assertNativeAbsent(long pid) { assertTrue(ProcessHandle.of(pid).isEmpty(), "Native PID must be absent: " + pid); }
    private static void assertInvestigation(JsonNode proof, Path ledger, JsonNode response, JsonNode stopped,
            EtfMarketOverviewCacheOwnerGateway.Config config) throws Exception {
        assertEquals("VERIFIED_STOPPED_TREE_DUPLICATE_TOMBSTONES", proof.required("status").asText());
        assertEquals(1, proof.required("investigation_version").longValue());
        assertEquals(INVOCATION, proof.required("invocation_id").asText());
        assertEquals(RUN, proof.required("run_id").asText());
        assertEquals(ledger, Path.of(proof.required("ledger_path").asText()).toAbsolutePath().normalize());
        assertEquals(response.required("java_intent").required("slice_id").asText(), proof.required("slice_id").asText());
        assertEquals(stopped.required("pid").longValue(), proof.required("launcher_pid").longValue());
        assertEquals(response.required("bridge_process").required("pid").longValue(), proof.required("actual_bridge_pid").longValue());
        var originalFiles = new HashSet<Path>();
        assertTrue(proof.required("original_evidence").size() >= 14 && proof.required("original_evidence").size() <= 32);
        for (var item : proof.required("original_evidence")) {
            assertTrue(item.required("kind").isTextual());
            Path path = Path.of(item.required("path").asText()).toAbsolutePath().normalize();
            assertTrue(path.startsWith(config.artifactRoot()) || path.startsWith(ledger.getParent().resolve("d101-owner-intent-claims")));
            assertEquals(item.required("sha256").asText(), sha(path)); assertTrue(originalFiles.add(path));
        }
        Path intentPath = Path.of(response.required("java_intent").required("path").asText()).toAbsolutePath().normalize();
        assertTrue(originalFiles.contains(intentPath));
        assertTrue(originalFiles.contains(Path.of(response.required("request_path").asText()).toAbsolutePath().normalize()));
        Path responsePath = config.artifactRoot().resolve("java-owner-publish-" + INVOCATION + ".response.json");
        assertTrue(originalFiles.contains(responsePath));
        assertTrue(originalFiles.contains(config.artifactRoot().resolve("java-owner-publish-" + INVOCATION + ".process-stopped.json")));
        var instances = new LinkedHashMap<Long,JsonNode>();
        assertEquals(7, proof.required("known_instances").size());
        for (var instance : proof.required("known_instances")) {
            long pid = instance.required("pid").longValue(); assertNull(instances.put(pid, instance));
            assertTrue(instance.required("exit_observed").booleanValue());
            assertNotEquals("UNKNOWN", instance.required("process_start").asText());
            Instant.parse(instance.required("process_start").asText()); assertNativeAbsent(pid);
            if (pid == stopped.required("pid").longValue()) {
                assertEquals(stopped.required("process_start").asText(), instance.required("process_start").asText());
            } else {
                JsonNode original = null;
                for (var child : stopped.required("observed_children"))
                    if (child.required("pid").longValue() == pid && !"UNKNOWN".equals(child.required("process_start").asText())) {
                        assertNull(original); original = child;
                    }
                assertNotNull(original);
                assertEquals(original.required("process_start").asText(), instance.required("process_start").asText());
                assertEquals(original.required("parent_pid"), instance.required("parent_pid"));
                assertEquals(original.required("evidence_sha256").asText(), instance.required("evidence_sha256").asText());
            }
            Path path = Path.of(instance.required("evidence_path").asText()).toAbsolutePath().normalize();
            assertTrue(originalFiles.contains(path)); assertEquals(instance.required("evidence_sha256").asText(), sha(path));
        }
        assertTrue(instances.containsKey(proof.required("launcher_pid").longValue()));
        assertTrue(instances.containsKey(proof.required("actual_bridge_pid").longValue()));
        assertEquals(2, proof.required("duplicate_tombstones").size());
        var duplicates = new HashSet<Long>();
        for (var duplicate : proof.required("duplicate_tombstones")) {
            long pid = duplicate.required("pid").longValue(); assertTrue(duplicates.add(pid)); assertNativeAbsent(pid);
            assertTrue(instances.containsKey(pid));
            assertEquals(instances.get(pid).required("process_start").asText(), duplicate.required("matched_known_birth").asText());
            Instant.parse(duplicate.required("observed_at").asText());
            Path path = Path.of(duplicate.required("evidence_path").asText()).toAbsolutePath().normalize();
            assertTrue(originalFiles.contains(path)); assertEquals(duplicate.required("evidence_sha256").asText(), sha(path));
        }
        assertEquals(Set.of(1960L, 27172L), duplicates);
        var absence = proof.required("native_absence");
        assertEquals("Java ProcessHandle.of", absence.required("method").asText());
        assertTrue(absence.required("all_absent").booleanValue()); Instant.parse(absence.required("checked_at").asText());
        var checked = new TreeSet<Long>(); for (var pid : absence.required("checked_pids")) checked.add(pid.longValue());
        assertEquals(new TreeSet<>(instances.keySet()), checked);
    }
    private static long number(ResultSet rs, String field) throws SQLException {
        long value = rs.getLong(field); assertFalse(rs.wasNull()); assertTrue(value >= 0); return value;
    }
    private static boolean flag(ResultSet rs, String field) throws SQLException { boolean value = rs.getBoolean(field); assertFalse(rs.wasNull()); return value; }
    private static long integer(JsonNode n) { assertTrue(n.isIntegralNumber() && n.canConvertToLong()); long value = n.longValue(); assertTrue(value >= 0); return value; }
    private static Double decimal(JsonNode n) { if (n.isNull()) return null; assertTrue(n.isNumber() && Double.isFinite(n.doubleValue())); return n.doubleValue(); }
    private static Double decimal(ResultSet rs, String field) throws SQLException {
        Object value = rs.getObject(field); if (value == null) return null;
        assertInstanceOf(Double.class, value); assertTrue(Double.isFinite((Double) value)); return (Double) value;
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static HikariDataSource pool() {
        var value = new HikariDataSource(); value.setDriverClassName("org.postgresql.Driver");
        value.setJdbcUrl("jdbc:postgresql://127.0.0.1:18832/qdb?sslmode=disable"); value.setUsername("admin"); value.setPassword("quest");
        value.setPoolName("d101-hit-readonly-recovery"); value.setMaximumPoolSize(4); value.setMinimumIdle(0);
        value.setConnectionTimeout(5000); value.setValidationTimeout(3000); return value;
    }
    private static JsonNode read(Path p) throws Exception {
        byte[] bytes = Files.readAllBytes(p); assertTrue(bytes.length > 0 && bytes.length <= 2 * 1024 * 1024);
        return JobDefinitionJson.mapper().readTree(bytes);
    }
    private static String sha(Path p) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))); }
    private static void saveNew(Path path, Object value) throws Exception {
        Files.writeString(path, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
