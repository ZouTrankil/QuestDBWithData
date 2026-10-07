package com.zoutrankil.data.config;

import com.zoutrankil.data.derived.application.EtfMarketOverviewCacheOwnerGateway;
import com.zoutrankil.data.derived.application.EtfMarketOverviewDailyCacheJobService;
import com.zoutrankil.data.derived.storage.EtfMarketOverviewDailyCacheReadRepository;
import com.zoutrankil.data.derived.storage.MarketBarometerCacheCoverageReadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.EtfMarketOverviewDailyCacheMapper;
import com.zoutrankil.data.derived.mapper.MarketBarometerCacheCoverageMapper;
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

/** Staged canonical owner acceptance. Java never seeds, revises or resets any source table. */
class EtfMarketOverviewDailyCacheLiveAcceptanceTest {
    private static final String CACHE = "etf_market_overview_daily_cache";
    private static final String COVERAGE = "market_barometer_cache_coverage";
    private static final List<String> SOURCES = List.of("etf_share", "etf_daily", "etf_basic");
    private static final List<String> TABLES = List.of("etf_share", "etf_daily", "etf_basic", CACHE, COVERAGE);
    private static final List<String> CACHE_FIELDS = EtfMarketOverviewDailyCacheDataset.DEFINITION.storageColumns();
    private static final List<String> RECEIPT_FIELDS = MarketBarometerCacheCoverageDataset.DEFINITION.storageColumns();
    private static final Comparator<EtfMarketOverviewDailyCache> KEYS = Comparator
            .comparing(EtfMarketOverviewDailyCache::tradeDate).thenComparing(EtfMarketOverviewDailyCache::sourceVersion);
    private static final LocalDate START = LocalDate.of(2026, 9, 17);
    private static final LocalDate SECOND = LocalDate.of(2026, 9, 18);
    private static final LocalDate THIRD = LocalDate.of(2026, 9, 21);
    private static final LocalDate RANGE_END = THIRD.plusDays(1);
    private static final Path COMMANDS = Path.of("artifacts/java-migration/D101/commands").toAbsolutePath().normalize();
    private static final Path FIRST = COMMANDS.resolve("java-readthrough-first-20261006.json");
    private static final Path HIT = COMMANDS.resolve("java-readthrough-hit-20261006.json");
    private static final Path INCREMENT = COMMANDS.resolve("java-readthrough-increment-20261006.json");
    private static final Path FIXTURE = COMMANDS.resolve("source-fixture-initial-20261006.json");
    private static final String SOURCE_SQL = "SELECT s.timestamp AS trade_date,count_distinct(s.ts_code) AS etf_count,"
            + "sum(s.fd_share) AS total_share,sum(s.fd_share*d.close)/10000.0 AS total_size_yi "
            + "FROM etf_share s JOIN etf_daily d ON s.ts_code=d.ts_code AND s.timestamp=d.timestamp "
            + "WHERE s.timestamp>=? AND s.timestamp<=? SAMPLE BY 1d ALIGN TO CALENDAR ORDER BY trade_date";

    @Test void firstCanonicalMissesPublishTwoVerifiedGenerationsAndCaptureActualCursor() throws Exception {
        assumeStage("first");
        executeStage("first");
    }

    @Test void repeatedCanonicalReadThroughHasTwoActualHitsAndZeroCacheOrReceiptSubmissions() throws Exception {
        assumeStage("hit");
        executeStage("hit");
    }

    @Test void newRealSourceDayRevalidatesTheWholePrefixAndInvalidatesTheOldPhysicalCursor() throws Exception {
        assumeStage("increment");
        executeStage("increment");
    }

    @Test void configuredTypedWriteGroupDelegatesMatchingAssertionsAndRejectsSpoofOrOldGenerations() throws Exception {
        assumeStage("typed");
        var json = JobDefinitionJson.mapper();
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("task_id", "D101"); evidence.put("stage", "typed"); evidence.put("status", "IN_PROGRESS");
        evidence.put("checked_at", Instant.now()); evidence.put("source_mutations", 0); evidence.put("formal_written_rows", 0);
        try (var pool = pool("d101-typed")) {
            var increment = read(INCREMENT); var first = read(FIRST);
            assertEquals("VERIFIED_CANONICAL_FULL_PREFIX_INCREMENT", increment.required("status").asText());
            Path output = COMMANDS.resolve("java-readthrough-typed-write-20261006.json"); assertFalse(Files.exists(output));
            var config = json.treeToValue(increment.required("bridge_config"), EtfMarketOverviewCacheOwnerGateway.Config.class);
            var gateway = new EtfMarketOverviewCacheOwnerGateway(config, new com.zoutrankil.data.derived.storage.EtfMarketOverviewOwnerProcess(config.pythonExecutable(), config.bridgeScript(), config.timeout()));
            var jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(20);
            Path ledgerPath = Path.of(increment.required("ledger_path").asText()).toAbsolutePath().normalize();
            var owner = new EtfMarketOverviewDailyCacheJobService(gateway, initial -> new com.zoutrankil.data.derived.application.DefaultEtfMarketOverviewPublicationSession(gateway, gateway, new com.zoutrankil.data.derived.storage.QuestDbEtfMarketOverviewPublicationTarget(jdbc), initial), ledgerPath.toString());
            var reader = new QuestDbBoundedReader(jdbc);
            var repository = new EtfMarketOverviewDailyCacheReadRepository(reader);
            var coverage = new MarketBarometerCacheCoverageReadRepository(reader);
            var registry = registry(repository, coverage);
            var configured = new StockBasicWriteGroupService(registry, null, jdbc, null, ledgerPath.toString());
            // The production application's optional owner injection is reproduced with the actual D101 service.
            var binding = StockBasicWriteGroupService.class.getDeclaredField("etfMarketOverviewCacheTarget");
            binding.setAccessible(true); binding.set(configured, owner);
            var current = new ArrayList<EtfMarketOverviewDailyCache>();
            for (var row : increment.required("current_cache_rows")) current.add(cache(row));
            assertEquals(3, current.size()); current.sort(KEYS);
            var before = metadata(jdbc); assertEquals(5, before.get(CACHE).actualRows()); assertEquals(5, before.get(COVERAGE).actualRows());
            assertEquals(savedMetadata(increment.required("tables_after")), before);
            assertEquals(increment.required("source_version_after").asText(), gateway.preview(START).sourcesFingerprint());
            evidence.put("tables_before", before); evidence.put("ledger_path", ledgerPath.toString()); evidence.put("bridge_config", config);
            evidence.put("increment_artifact", INCREMENT); evidence.put("increment_artifact_sha256", sha(Files.readAllBytes(INCREMENT)));
            var request = writeRequest("d101-typed-" + UUID.randomUUID(), current);
            Path requestPath = COMMANDS.resolve("strictwrite-group-request-D101-typed-20261006.json"); saveNew(requestPath, request);
            var parsed = new WriteGroupJson(registry).read(requestPath);
            assertEquals(3, parsed.members().getFirst().rows().size());
            var result = configured.run(requestPath, null); evidence.put("write_group_result", result);
            assertEquals(SyncRunState.VERIFIED, result.state()); assertEquals(1, result.members().size());
            var member = result.members().getFirst(); assertEquals("write.etf_market_overview_daily_cache", member.jobId());
            assertEquals(SyncRunState.VERIFIED, member.state()); assertEquals(3, member.verifiedRows()); assertFalse(member.reused());
            var ledger = SyncRunLedger.openReadOnly(ledgerPath); var child = ledger.getRun(member.childRunId());
            assertEquals(result.runId(), child.parentRunId()); assertEquals(owner.targetId(), child.targetId());
            var frozen = json.readTree(child.frozenJson());
            assertEquals("INGEST", frozen.required("mode").asText());
            for (String key : List.of("groupBatch", "memberBatch", "planFingerprint", "payloadFingerprint"))
                assertTrue(frozen.required("parameters").required(key).isTextual());
            assertNull(new DatasetIntervalLock(ledgerPath).findOwned(member.childRunId(), DatasetIntervalLock.Scope.allDates(CACHE)));
            assertTrue(gateway.writerStopped(ledgerPath, member.childRunId()));
            var publications = publications(config.artifactRoot(), member.childRunId()); assertEquals(3, publications.size());
            int hits = 0;
            for (var publication : publications) {
                var response = publication.response(); assertEquals("VERIFIED_READTHROUGH", response.required("status").asText());
                assertTrue(response.required("owner_invoked").asBoolean()); assertTrue(response.required("owner_sender_stopped").asBoolean());
                assertEquals(0, response.required("actual_owner_misses").asInt()); hits += response.required("actual_owner_hits").asInt();
                assertEquals(0, response.required("cache_submitted_rows").asInt()); assertEquals(0, response.required("coverage_submitted_rows").asInt());
                assertTrue(response.required("owner_submissions").isEmpty()); assertTrue(response.required("exact_owner_digest_verified").asBoolean());
                assertDurableSubmission(ledger, publication, member.childRunId());
                var intent = read(Path.of(response.required("java_intent").required("path").asText()));
                assertTrue(intent.has("prepared_input_path") && intent.has("prepared_input_sha256"), "Original bridge must bind the actual frozen typed caller artifact");
            }
            assertEquals(3, hits); assertEquals(before, metadata(jdbc));
            evidence.put("matching_actual_owner_hits", hits); evidence.put("matching_cache_submitted_rows", 0);
            evidence.put("matching_coverage_submitted_rows", 0); evidence.put("actual_owner_publications", publications);
            evidence.put("child_run", child); evidence.put("frozen_child", frozen); evidence.put("retained_interval_leases", 0);

            long publishBeforeRejections = publicationFileCount(config.artifactRoot());
            long runsBeforeRejections = runCount(ledgerPath);
            var sample = current.getFirst();
            var spoof = new EtfMarketOverviewDailyCache(sample.tradeDate(), sample.etfCount() + 1,
                    sample.totalShare(), sample.totalSizeYi(), sample.sourceVersion());
            Path spoofPath = COMMANDS.resolve("strictwrite-group-request-D101-spoof-20261006.json");
            saveNew(spoofPath, writeRequest("d101-spoof-" + UUID.randomUUID(), List.of(spoof)));
            assertThrows(IllegalArgumentException.class, () -> configured.run(spoofPath, null));
            var old = cache(first.required("stored_cache_rows").get(0));
            assertNotEquals(old.sourceVersion(), sample.sourceVersion());
            Path historicalPath = COMMANDS.resolve("strictwrite-group-request-D101-historical-20261006.json");
            saveNew(historicalPath, writeRequest("d101-historical-" + UUID.randomUUID(), List.of(old)));
            assertThrows(IllegalArgumentException.class, () -> configured.run(historicalPath, null));
            var invalid = writeRequest("d101-invalid-" + UUID.randomUUID(), List.of(sample));
            ((ObjectNode) invalid.required("members").get(0).required("rows").get(0)).put("source_version", "bad-sha");
            Path invalidPath = COMMANDS.resolve("strictwrite-group-request-D101-invalid-sha-20261006.json"); saveNew(invalidPath, invalid);
            assertThrows(IllegalArgumentException.class, () -> configured.run(invalidPath, null));
            var standaloneReceipt = writeRequest("d101-receipt-reject-" + UUID.randomUUID(), List.of(sample));
            ((ObjectNode) standaloneReceipt.required("members").get(0)).put("datasetId", COVERAGE);
            Path receiptPath = COMMANDS.resolve("strictwrite-group-request-D101-receipt-reject-20261006.json"); saveNew(receiptPath, standaloneReceipt);
            assertThrows(IllegalArgumentException.class, () -> configured.run(receiptPath, null));
            assertEquals(publishBeforeRejections, publicationFileCount(config.artifactRoot()), "Rejected inputs cannot start a publisher");
            assertEquals(runsBeforeRejections, runCount(ledgerPath), "Prepared mismatches are refused before shared run creation");
            var after = metadata(jdbc); assertEquals(before, after);
            var rows = repository.findRange(START, RANGE_END, 5, null); assertEquals(5, rows.rows().size()); assertNull(rows.nextCursor());
            var expected = new ArrayList<EtfMarketOverviewDailyCache>(); for (var row : increment.required("stored_cache_rows")) expected.add(cache(row)); expected.sort(KEYS);
            var comparisons = new Comparisons();
            for (int index = 0; index < expected.size(); index++) compare(expected.get(index), rows.rows().get(index), comparisons);
            evidence.put("tables_after", after); evidence.put("stored_cache_rows", values(rows.rows()));
            evidence.put("spoof_rejected_without_publication", true); evidence.put("historical_generation_rejected_without_publication", true);
            evidence.put("malformed_generation_rejected_without_publication", true); evidence.put("standalone_d094_writer_rejected", true);
            evidence.put("rejection_database_effects", 0); evidence.put("java_independent_cache_or_receipt_writes", 0);
            evidence.put("status", "VERIFIED_TYPED_OWNER_ASSERTIONS_ZERO_PUBLISH"); saveNew(output, evidence);
        } catch (Exception | AssertionError failure) {
            evidence.put("status", "FAILED"); evidence.put("error_class", failure.getClass().getSimpleName());
            saveNew(COMMANDS.resolve("java-readthrough-typed-failed-" + UUID.randomUUID() + ".json"), evidence); throw failure;
        }
    }

    private void executeStage(String stage) throws Exception {
        Files.createDirectories(COMMANDS);
        Path output = switch (stage) { case "first" -> FIRST; case "hit" -> HIT; default -> INCREMENT; };
        assertFalse(Files.exists(output), "Acceptance artifacts are immutable; use explicit reviewed recovery after failure");
        var json = JobDefinitionJson.mapper();
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("task_id", "D101"); evidence.put("stage", stage); evidence.put("status", "IN_PROGRESS");
        evidence.put("checked_at", Instant.now()); evidence.put("source_mutations", 0);
        evidence.put("formal_written_rows", 0); evidence.put("java_independent_cache_or_receipt_writes", 0);
        evidence.put("publication_owner", "python.MarketBarometerReadThroughCache.read");
        try (var pool = pool("d101-" + stage)) {
            var jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(20);
            JsonNode first = "first".equals(stage) ? null : read(FIRST);
            JsonNode hit = "increment".equals(stage) ? read(HIT) : null;
            if (first != null) assertEquals("VERIFIED_CANONICAL_FIRST_MISSES", first.required("status").asText());
            if (hit != null) assertEquals("VERIFIED_CANONICAL_HITS_ZERO_PUBLISH", hit.required("status").asText());
            var config = first == null ? initialConfig() : json.treeToValue(first.required("bridge_config"), EtfMarketOverviewCacheOwnerGateway.Config.class);
            var gateway = new EtfMarketOverviewCacheOwnerGateway(config, new com.zoutrankil.data.derived.storage.EtfMarketOverviewOwnerProcess(config.pythonExecutable(), config.bridgeScript(), config.timeout()));
            Path ledgerPath = first == null ? Path.of("var/d101-java-" + UUID.randomUUID() + ".sqlite3").toAbsolutePath().normalize()
                    : Path.of(first.required("ledger_path").asText()).toAbsolutePath().normalize();
            if (first != null) assertTrue(Files.isRegularFile(ledgerPath), "Original SQLite authority is required");
            var owner = new EtfMarketOverviewDailyCacheJobService(gateway, initial -> new com.zoutrankil.data.derived.application.DefaultEtfMarketOverviewPublicationSession(gateway, gateway, new com.zoutrankil.data.derived.storage.QuestDbEtfMarketOverviewPublicationTarget(jdbc), initial), ledgerPath.toString());
            evidence.put("ledger_path", ledgerPath.toString()); evidence.put("bridge_config", config);
            if (first != null) { evidence.put("first_artifact", FIRST); evidence.put("first_artifact_sha256", sha(Files.readAllBytes(FIRST))); }
            if (hit != null) { evidence.put("hit_artifact", HIT); evidence.put("hit_artifact_sha256", sha(Files.readAllBytes(HIT))); }

            var fixture = read(FIXTURE);
            assertEquals("VERIFIED_COMPLETE_REAL_SOURCE_FIXTURE", fixture.required("status").asText());
            assertFalse(fixture.required("formal_mutated").asBoolean());
            evidence.put("source_fixture_artifact", FIXTURE); evidence.put("source_fixture_sha256", sha(Files.readAllBytes(FIXTURE)));
            var before = metadata(jdbc);
            evidence.put("tables_before", before);
            long beforeRows = "first".equals(stage) ? 0 : 2;
            assertEquals(beforeRows, before.get(CACHE).actualRows(), "reset_required: unexpected cache state before this explicit stage");
            assertEquals(beforeRows, before.get(COVERAGE).actualRows(), "reset_required: unexpected receipt state before this explicit stage");
            assertSourceCounts(before, "increment".equals(stage));
            if ("hit".equals(stage)) assertEquals(savedMetadata(first.required("tables_after")), before, "Hit stage must start at the unchanged first frontier");
            // Increment has intentional source changes; only target tables must remain at the hit frontier.
            if (hit != null) for (String target : List.of(CACHE, COVERAGE))
                assertEquals(json.treeToValue(hit.required("tables_after").required(target), Snapshot.class), before.get(target));
            var initial = gateway.preview(START); // Actual process/root/schema attestation and SELECT-only source census.
            evidence.put("attested_source_preview", initial.previewResponse());
            evidence.put("source_version_before", initial.sourcesFingerprint());
            assertTrue(initial.knownSourceDate()); assertNotNull(initial.cache());
            if (first != null) assertEquals(first.required("target_id").asText(), initial.targetId());

            boolean incremental = "increment".equals(stage);
            LocalDate through = incremental ? THIRD : SECOND;
            LocalDate logical = through.plusDays(1);
            int expectedUnits = incremental ? 3 : 2;
            int expectedHits = "hit".equals(stage) ? 2 : 0;
            int expectedMisses = incremental ? 3 : "first".equals(stage) ? 2 : 0;
            var plan = owner.plan(START, through, logical, SyncJobDefinition.Mode.INCREMENTAL);
            assertEquals(START, plan.request().from()); assertEquals(START, plan.request().parameters().get("bootstrap_from"));
            assertEquals(through, plan.request().to());
            assertEquals(initial.sourcesFingerprint(), plan.request().parameters().get("source_version"));
            if ("first".equals(stage)) assertFalse(plan.request().parameters().containsKey("checkpoint_before"));
            if ("hit".equals(stage)) assertEquals(SECOND, plan.request().parameters().get("checkpoint_before"));
            if (incremental) {
                assertNotEquals(first.required("source_version_before").asText(), initial.sourcesFingerprint(), "Real YEAR source revision must change the frozen vector");
                assertFalse(plan.request().parameters().containsKey("checkpoint_before"), "Old source generation cannot certify the new prefix");
                for (String source : SOURCES) {
                    var old = json.treeToValue(first.required("tables_after").required(source), Snapshot.class);
                    assertEquals(old.id(), before.get(source).id()); assertEquals(old.directory(), before.get(source).directory());
                    if (!"etf_basic".equals(source)) assertNotEquals(old.sequenceTxn(), before.get(source).sequenceTxn());
                    else assertEquals(old, before.get(source));
                }
            }
            evidence.put("plan", json.readTree(SyncRequestIdentity.snapshotJson(plan.request())));
            evidence.put("target_id", plan.targetId());
            var result = owner.run(plan);
            evidence.put("materialization", result);
            assertEquals(SyncRunState.VERIFIED, result.result().state(), result.toString());
            assertEquals(expectedUnits, result.result().sourceRows()); assertEquals(expectedUnits, result.result().verifiedRows());
            assertEquals(0, result.result().reusedRows());
            var ledger = SyncRunLedger.openReadOnly(ledgerPath);
            var persisted = ledger.getRun(result.result().runId());
            assertEquals(SyncRequestIdentity.fingerprint(plan.request(), plan.targetId()), SyncRequestIdentity.fingerprint(persisted.frozenJson(), persisted.targetId()));
            evidence.put("run", persisted); evidence.put("frozen_json", json.readTree(persisted.frozenJson()));
            var status = owner.status(result.result().runId()); evidence.put("management_status", status);
            assertEquals(SyncRunState.VERIFIED, status.state()); assertEquals(expectedUnits, status.verifiedPublicationUnits());
            assertEquals(0, status.unresolvedSlices()); assertFalse(status.cancellationRequested());
            assertFalse(owner.cancel(result.result().runId()));
            assertFalse(ledger.cancellationRequested(result.result().runId()));
            var lease = new DatasetIntervalLock(ledgerPath).findOwned(result.result().runId(), DatasetIntervalLock.Scope.allDates(CACHE));
            assertNull(lease); evidence.put("retained_interval_leases", 0);
            assertTrue(gateway.writerStopped(ledgerPath, result.result().runId()), "Actual Java process exit evidence is required");
            evidence.put("actual_java_bridge_processes_stopped", true);

            var publications = publications(config.artifactRoot(), result.result().runId());
            assertEquals(expectedUnits, publications.size());
            var current = new ArrayList<EtfMarketOverviewDailyCache>();
            var currentReceipts = new ArrayList<MarketBarometerCacheCoverage>();
            int hits = 0, misses = 0, cacheSubmitted = 0, receiptSubmitted = 0;
            for (var publication : publications) {
                var node = publication.response();
                assertEquals("VERIFIED_READTHROUGH", node.required("status").asText());
                assertTrue(node.required("owner_invoked").asBoolean()); assertTrue(node.required("owner_sender_stopped").asBoolean());
                assertTrue(node.required("exact_owner_digest_verified").asBoolean()); assertTrue(node.required("source_unchanged").asBoolean());
                hits += node.required("actual_owner_hits").asInt(); misses += node.required("actual_owner_misses").asInt();
                cacheSubmitted += node.required("cache_submitted_rows").asInt(); receiptSubmitted += node.required("coverage_submitted_rows").asInt();
                assertEquals(1, node.required("expected_cache_records").size()); assertEquals(1, node.required("actual_cache_records").size());
                var expected = cache(node.required("expected_cache_records").get(0));
                var returned = cache(node.required("actual_cache_records").get(0)); compare(expected, returned, new Comparisons());
                var expectedReceipt = receipt(node.required("expected_receipt"));
                assertEquals(expectedReceipt, receipt(node.required("actual_receipt")));
                assertEquals(expected.key(), new EtfMarketOverviewDailyCacheKey(expectedReceipt.tradeDate(), expectedReceipt.sourceVersion()));
                assertEquals(1L, expectedReceipt.rowCount());
                assertDurableSubmission(ledger, publication, result.result().runId());
                current.add(expected); currentReceipts.add(expectedReceipt);
                for (var submission : node.required("owner_submissions")) {
                    assertTrue(Set.of(CACHE, COVERAGE).contains(submission.required("table").asText()));
                    assertEquals("ACKNOWLEDGED", submission.required("ack").asText());
                    assertEquals(1, submission.required("attempted_rows").asInt()); assertEquals(1, submission.required("acknowledged_rows").asInt());
                }
            }
            current.sort(KEYS); assertEquals(expectedUnits, new HashSet<>(current.stream().map(EtfMarketOverviewDailyCache::key).toList()).size());
            assertEquals(expectedHits, hits); assertEquals(expectedMisses, misses);
            assertEquals(expectedMisses, cacheSubmitted); assertEquals(expectedMisses, receiptSubmitted);
            evidence.put("actual_owner_hits", hits); evidence.put("actual_owner_misses", misses);
            evidence.put("cache_submitted_rows", cacheSubmitted); evidence.put("coverage_submitted_rows", receiptSubmitted);
            evidence.put("actual_owner_publications", publications);
            evidence.put("current_cache_rows", values(current)); evidence.put("current_receipts", receiptValues(currentReceipts));

            var expectedRows = new ArrayList<EtfMarketOverviewDailyCache>();
            var expectedReceipts = new ArrayList<MarketBarometerCacheCoverage>();
            if (incremental) {
                for (var row : first.required("stored_cache_rows")) expectedRows.add(cache(row));
                for (var row : first.required("stored_receipts")) expectedReceipts.add(receipt(row));
            }
            expectedRows.addAll(current); expectedReceipts.addAll(currentReceipts); expectedRows.sort(KEYS);
            assertEquals(incremental ? 5 : 2, expectedRows.size());
            assertEquals(expectedRows.size(), new HashSet<>(expectedRows.stream().map(EtfMarketOverviewDailyCache::key).toList()).size());
            var afterPublish = metadata(jdbc);
            for (String source : SOURCES) assertEquals(before.get(source), afterPublish.get(source));
            assertEquals(expectedRows.size(), afterPublish.get(CACHE).actualRows());
            assertEquals(expectedRows.size(), afterPublish.get(COVERAGE).actualRows());
            if ("hit".equals(stage)) assertEquals(before, afterPublish, "Actual hits must submit zero rows and preserve both physical/WAL frontiers");
            else for (String target : List.of(CACHE, COVERAGE)) assertTrue(afterPublish.get(target).sequenceTxn() > before.get(target).sequenceTxn());

            var guardedJdbc = spy(jdbc);
            var reader = new QuestDbBoundedReader(guardedJdbc);
            var repository = new EtfMarketOverviewDailyCacheReadRepository(reader);
            var coverage = new MarketBarometerCacheCoverageReadRepository(reader);
            var rangeQuery = rangeQuery(1);
            if (incremental) rejectOldActualCursor(first, reader, repository, guardedJdbc, rangeQuery, evidence);
            var comparisons = new Comparisons();
            for (var expected : expectedRows) {
                var key = repository.findKey(expected.key()); assertEquals(1, key.rows().size()); assertNull(key.nextCursor());
                compare(expected, key.rows().getFirst(), comparisons); storageBits(jdbc, key.rows().getFirst(), comparisons);
                assertEquals(key.rows(), repository.findVersion(expected.tradeDate(), expected.sourceVersion()).rows());
            }
            var actualRange = new ArrayList<EtfMarketOverviewDailyCache>();
            DatasetReadCursor cursor = null, capturedCursor = null; String physicalVersion = null; int pages = 0;
            do {
                var page = repository.findRange(START, RANGE_END, 1, cursor);
                assertEquals(1, page.rows().size());
                if (physicalVersion == null) physicalVersion = page.sourceVersion(); else assertEquals(physicalVersion, page.sourceVersion());
                assertNotNull(physicalVersion); actualRange.addAll(page.rows());
                cursor = page.nextCursor(); if (++pages == 1) capturedCursor = cursor;
                assertTrue(pages <= expectedRows.size());
            } while (cursor != null);
            assertEquals(expectedRows.size(), pages); assertNotNull(capturedCursor);
            assertEquals(expectedRows.size(), actualRange.size()); assertEquals(actualRange.stream().sorted(KEYS).toList(), actualRange);
            for (int index = 0; index < expectedRows.size(); index++) {
                compare(expectedRows.get(index), actualRange.get(index), comparisons); storageBits(jdbc, actualRange.get(index), comparisons);
            }
            for (var expected : current) {
                var direct = aggregate(jdbc, expected.tradeDate(), expected.sourceVersion());
                compare(expected, direct, new Comparisons());
            }
            for (var date : current.stream().map(EtfMarketOverviewDailyCache::tradeDate).distinct().toList()) {
                var expected = expectedRows.stream().filter(row -> row.tradeDate().equals(date)).toList();
                assertEquals(expected, repository.findForDate(date, expected.size(), null).rows());
            }
            for (var version : current.stream().map(EtfMarketOverviewDailyCache::sourceVersion).distinct().toList()) {
                var expected = expectedRows.stream().filter(row -> row.sourceVersion().equals(version)).toList();
                assertEquals(expected, repository.findRange(START, RANGE_END, version, expected.size(), null).rows());
            }
            for (var expected : expectedReceipts) {
                var actual = coverage.findKey(expected.key()); assertEquals(List.of(expected), actual.rows()); assertNull(actual.nextCursor());
            }
            var receiptRange = coverage.findRange(START, RANGE_END, expectedReceipts.size(), null);
            assertNull(receiptRange.nextCursor());
            assertEquals(new HashSet<>(expectedReceipts), new HashSet<>(receiptRange.rows()));
            configuredReadGroup(stage, repository, coverage, reader, actualRange, expectedReceipts);
            assertEquals(Set.of(DatasetDefinition.Capability.READ), MarketBarometerCacheCoverageDataset.DEFINITION.capabilities());
            assertThrows(IllegalArgumentException.class, () -> MarketBarometerCacheCoverageDataset.DEFINITION.requireCapability(DatasetDefinition.Capability.WRITE));
            assertThrows(IllegalArgumentException.class, () -> repository.definition().requireCapability(DatasetDefinition.Capability.STATIC_REPLACE));
            assertThrows(IllegalArgumentException.class, () -> repository.definition().requireCapability(DatasetDefinition.Capability.WAL_REPLACE));
            int publisherResponsesBefore = publications(config.artifactRoot(), result.result().runId()).size();
            var spoof = new EtfMarketOverviewDailyCache(current.getFirst().tradeDate(), current.getFirst().etfCount() + 1,
                    current.getFirst().totalShare(), current.getFirst().totalSizeYi(), current.getFirst().sourceVersion());
            assertThrows(IllegalArgumentException.class, () -> owner.delegatedPort().requireExactEnvelope(spoof));
            assertEquals(publisherResponsesBefore, publications(config.artifactRoot(), result.result().runId()).size());

            var after = metadata(jdbc); assertEquals(afterPublish, after, "All post-publication verification is SELECT-only");
            var finalPreview = gateway.preview(START);
            assertEquals(initial.sourcesFingerprint(), finalPreview.sourcesFingerprint());
            assertEquals(initial.targetId(), finalPreview.targetId());
            assertTrue(gateway.writerStopped(ledgerPath, result.result().runId()));
            var nextPlan = owner.plan(START, through, logical, SyncJobDefinition.Mode.INCREMENTAL);
            assertEquals(through, nextPlan.request().parameters().get("checkpoint_before")); assertEquals(START, nextPlan.request().from());
            evidence.put("checkpoint_after", nextPlan.request().parameters().get("checkpoint_before"));
            evidence.put("tables_after_publication", afterPublish); evidence.put("tables_after", after);
            evidence.put("source_version_after", finalPreview.sourcesFingerprint());
            evidence.put("stored_cache_rows", values(actualRange)); evidence.put("stored_receipts", receiptValues(receiptRange.rows()));
            evidence.put("stored_cache_rows_count", actualRange.size()); evidence.put("stored_receipt_rows_count", receiptRange.rows().size());
            evidence.put("range_pages", pages); evidence.put("unique_cache_field_values", actualRange.size() * 5);
            evidence.put("unique_receipt_field_values", receiptRange.rows().size() * 5);
            evidence.put("key_and_range_cache_field_comparisons", comparisons.fields);
            evidence.put("pg_reference_double_raw_bit_comparisons", comparisons.referenceBits);
            evidence.put("independent_jdbc_double_raw_bit_comparisons", comparisons.storageBits);
            evidence.put("double_tolerance", 0); evidence.put("current_direct_source_aggregate_rows", current.size());
            evidence.put("range_query", rangeQuery); evidence.put("actual_first_cursor", capturedCursor);
            evidence.put("cache_physical_source_version", physicalVersion);
            evidence.put("query_fingerprint", capturedCursor.queryFingerprint());
            assertEquals(reader.prepare(repository.definition(), rangeQuery, physicalVersion).fingerprint(), capturedCursor.queryFingerprint());
            evidence.put("configured_typed_read_group_verified", true); evidence.put("configured_cancellation_verified", true);
            evidence.put("bad_generation_and_json_rejected_before_database_query", true);
            evidence.put("standalone_d094_writer_rejected", true); evidence.put("spoof_rejected_without_publication", true);
            evidence.put("scope", "Real frozen private Python-source snapshot and original active read-through owner; no formal freshness or provider universe claim");
            evidence.put("status", switch (stage) { case "first" -> "VERIFIED_CANONICAL_FIRST_MISSES";
                case "hit" -> "VERIFIED_CANONICAL_HITS_ZERO_PUBLISH"; default -> "VERIFIED_CANONICAL_FULL_PREFIX_INCREMENT"; });
            saveNew(output, evidence);
        } catch (Exception | AssertionError failure) {
            evidence.put("status", "FAILED"); evidence.put("error_class", failure.getClass().getSimpleName());
            saveNew(COMMANDS.resolve("java-readthrough-" + stage + "-failed-" + UUID.randomUUID() + ".json"), evidence);
            throw failure;
        }
    }

    private static void configuredReadGroup(String stage, EtfMarketOverviewDailyCacheReadRepository repository,
            MarketBarometerCacheCoverageReadRepository coverage, QuestDbBoundedReader reader,
            List<EtfMarketOverviewDailyCache> expected, List<MarketBarometerCacheCoverage> receipts) throws Exception {
        var registry = registry(repository, coverage);
        var configured = new ReadGroupConfiguration().readGroupReader(registry, reader);
        var json = JobDefinitionJson.mapper(); var root = json.createObjectNode(); root.put("timeoutMillis", 30_000);
        var members = root.putArray("members");
        for (var item : Map.of("cache", repository.definition(), "receipt", coverage.definition()).entrySet()) {
            var member = members.addObject(); member.put("memberId", item.getKey());
            member.put("datasetId", item.getValue().datasetId()); member.put("definitionVersion", 1);
            var query = member.putObject("query"); query.set("columns", json.valueToTree(item.getValue().storageColumns()));
            query.putObject("equalities"); query.put("rangeColumn", "trade_date"); query.put("fromInclusive", START.toString());
            query.put("toExclusive", RANGE_END.toString()); query.put("pageSize", expected.size()); query.putNull("cursor");
        }
        Path requestFile = COMMANDS.resolve("strictread-group-request-D101-" + stage + ".json"); saveNew(requestFile, root);
        var request = configured.readRequest(requestFile); var actual = configured.read(request, () -> false);
        assertTrue(actual.complete());
        assertEquals(expected, actual.require("cache").typedPage(EtfMarketOverviewDailyCache.class).rows());
        assertEquals(new HashSet<>(receipts), new HashSet<>(actual.require("receipt").typedPage(MarketBarometerCacheCoverage.class).rows()));
        var cancelled = configured.read(request, () -> true);
        assertEquals(ReadGroupReader.Status.CANCELLED, cancelled.require("cache").status()); assertNull(cancelled.require("cache").page());
        var untouched = mock(JdbcTemplate.class);
        var validation = new ReadGroupConfiguration().readGroupReader(registry, new QuestDbBoundedReader(untouched));
        var badSha = root.deepCopy();
        ObjectNode cacheMember = null;
        for (var member : badSha.required("members")) if ("cache".equals(member.required("memberId").asText())) cacheMember = (ObjectNode) member;
        assertNotNull(cacheMember); ((ObjectNode) cacheMember.required("query").required("equalities")).put("source_version", "bad-sha");
        Path badShaFile = COMMANDS.resolve("strictread-group-request-D101-" + stage + "-bad-sha.json"); saveNew(badShaFile, badSha);
        assertThrows(IllegalArgumentException.class, () -> validation.readRequest(badShaFile));
        var badProperty = root.deepCopy(); badProperty.put("unexpected", true);
        Path badPropertyFile = COMMANDS.resolve("strictread-group-request-D101-" + stage + "-bad-property.json"); saveNew(badPropertyFile, badProperty);
        assertThrows(IllegalArgumentException.class, () -> validation.readRequest(badPropertyFile));
        var badVersion = root.deepCopy(); ((ObjectNode) badVersion.required("members").get(0)).put("definitionVersion", 2);
        Path badVersionFile = COMMANDS.resolve("strictread-group-request-D101-" + stage + "-bad-version.json"); saveNew(badVersionFile, badVersion);
        assertThrows(IllegalArgumentException.class, () -> validation.readRequest(badVersionFile));
        assertEquals(ReadGroupReader.Status.CANCELLED, validation.read(request, () -> true).require("cache").status());
        verifyNoInteractions(untouched);
    }

    private static void rejectOldActualCursor(JsonNode first, QuestDbBoundedReader reader,
            EtfMarketOverviewDailyCacheReadRepository repository, JdbcTemplate guardedJdbc, DatasetReadQuery query,
            Map<String,Object> evidence) throws Exception {
        var saved = first.required("actual_first_cursor");
        assertEquals(2, saved.required("keyValues").size());
        var cursor = new DatasetReadCursor(saved.required("queryFingerprint").asText(),
                List.of(LocalDate.parse(saved.required("keyValues").get(0).asText()), saved.required("keyValues").get(1).asText()),
                saved.required("sourceVersion").asText());
        assertEquals(first.required("range_query"), JobDefinitionJson.mapper().valueToTree(query));
        assertEquals(reader.prepare(repository.definition(), query, cursor.sourceVersion()).fingerprint(), cursor.queryFingerprint());
        clearInvocations(guardedJdbc);
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(START, RANGE_END, 1, cursor));
        verify(guardedJdbc, never()).query(any(org.springframework.jdbc.core.PreparedStatementCreator.class), any(org.springframework.jdbc.core.RowMapper.class));
        evidence.put("old_actual_cursor_rejected_before_row_query", true); evidence.put("old_actual_cursor", cursor);
    }

    private record Publication(Path responsePath, String responseSha256, JsonNode response) {}
    private static DatasetRegistry registry(EtfMarketOverviewDailyCacheReadRepository repository,
                                           MarketBarometerCacheCoverageReadRepository coverage) {
        return new DatasetRegistry(List.of(repository, coverage, () -> EtfShareDataset.DEFINITION,
                () -> EtfDailyDataset.DEFINITION, () -> EtfBasicDataset.DEFINITION,
                () -> ExchangeCalendarDataset.DEFINITION));
    }
    private static ObjectNode writeRequest(String batch, List<EtfMarketOverviewDailyCache> rows) {
        var json = JobDefinitionJson.mapper(); var root = json.createObjectNode();
        root.put("batchId", batch); root.put("logicalDate", RANGE_END.toString());
        var member = root.putArray("members").addObject(); member.put("memberId", "cache");
        member.put("datasetId", CACHE); member.put("definitionVersion", 1); member.put("batchId", batch + "-member");
        member.set("rows", json.valueToTree(values(rows))); return root;
    }
    private static long publicationFileCount(Path root) throws Exception {
        try (var files = Files.walk(root, 3)) {
            var paths = files.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().startsWith("java-owner-publish-")
                    && path.getFileName().toString().endsWith(".response.json")).limit(1001).toList();
            assertTrue(paths.size() <= 1000); return paths.size();
        }
    }
    private static long runCount(Path ledger) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + ledger.toUri().toASCIIString() + "?mode=ro");
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM sync_runs")) {
            assertTrue(rows.next()); long count = rows.getLong(1); assertFalse(rows.next()); return count;
        }
    }
    private static List<Publication> publications(Path root, String runId) throws Exception {
        assertTrue(Files.isDirectory(root));
        var result = new ArrayList<Publication>();
        try (var files = Files.walk(root, 3)) {
            var paths = files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".response.json")).limit(1001).toList();
            assertTrue(paths.size() <= 1000, "Finite complete invocation inventory required");
            for (var path : paths) {
                var node = read(path);
                if (!runId.equals(node.path("java_intent").path("run_id").asText())) continue;
                assertEquals("publish", node.required("operation").asText());
                result.add(new Publication(path.toAbsolutePath().normalize(), sha(Files.readAllBytes(path)), node));
            }
        }
        result.sort(Comparator.comparing(value -> value.response().required("trade_date").asText()));
        return List.copyOf(result);
    }

    private static void assertDurableSubmission(SyncRunLedger ledger, Publication publication, String runId) throws Exception {
        var actual = publication.response(); var intent = actual.required("java_intent");
        assertEquals(runId, intent.required("run_id").asText()); assertEquals(4, intent.required("revision").asLong());
        String sliceId = intent.required("slice_id").asText(); var slice = ledger.get(sliceId);
        assertEquals(SyncRunLedger.Kind.SLICE, slice.kind()); assertEquals(SyncRunState.VERIFIED, slice.state());
        var events = ledger.events(sliceId, -1, 100);
        var submitted = events.stream().filter(event -> event.state() == SyncRunState.SUBMITTED).toList();
        assertEquals(1, submitted.size()); assertEquals(4, submitted.getFirst().revision());
        var payload = JobDefinitionJson.mapper().readTree(submitted.getFirst().payloadJson());
        assertEquals(actual.required("source_fingerprint").asText(), payload.required("sourceFingerprint").asText());
        assertEquals(1, payload.required("returnedRows").asInt());
        var responseEvidence = JobDefinitionJson.mapper().readTree(payload.required("responseEvidence").asText());
        assertEquals(actual.required("preview_path").asText(), responseEvidence.required("preview_path").asText());
        assertEquals(actual.required("preview_sha256").asText(), responseEvidence.required("preview_sha256").asText());
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
            long rows = Objects.requireNonNull(jdbc.queryForObject("SELECT count() FROM " + table, Long.class));
            var snapshot = jdbc.query("SELECT t.id,t.directoryName,t.table_txn,t.table_row_count,t.walEnabled,t.table_suspended,"
                    + "t.wal_pending_row_count,w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended "
                    + "FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='" + table + "'",
                    (org.springframework.jdbc.core.ResultSetExtractor<Snapshot>) rs -> {
                        assertTrue(rs.next()); assertTrue(rs.getBoolean("walEnabled"));
                        Long count = nullableNumber(rs, "table_row_count");
                        var value = new Snapshot(table, number(rs, "id"), rs.getString("directoryName"), nullableNumber(rs, "table_txn"),
                                count, rows, number(rs, "sequencerTxn"), number(rs, "writerTxn"), number(rs, "wal_pending_row_count"),
                                number(rs, "bufferedTxnSize"), rs.getBoolean("table_suspended"), rs.getBoolean("suspended"));
                        assertNotNull(value.directory()); assertFalse(value.directory().isBlank()); assertFalse(rs.next());
                        assertEquals(value.sequenceTxn(), value.writerTxn()); assertEquals(0, value.pendingRows()); assertEquals(0, value.bufferedTxns());
                        assertFalse(value.tableSuspended()); assertFalse(value.walSuspended());
                        if (value.metadataRows() != null) assertEquals(value.actualRows(), value.metadataRows().longValue());
                        boolean emptyTarget = List.of(CACHE, COVERAGE).contains(table) && value.actualRows() == 0;
                        if (emptyTarget) {
                            // A newly installed WAL target can expose raw null txn/count. Preserve both nulls;
                            // independently prove emptiness and its zero WAL frontier before accepting them.
                            assertEquals(0, value.sequenceTxn()); assertEquals(0, value.writerTxn());
                            assertTrue(value.metadataRows() == null || value.metadataRows() == 0L);
                        } else assertNotNull(value.physicalTxn(), "Source and nonempty target physical txn must be present");
                        return value;
                    });
            result.put(table, snapshot);
        }
        return Collections.unmodifiableMap(result);
    }

    private static void assertSourceCounts(Map<String,Snapshot> snapshots, boolean increment) {
        assertEquals(increment ? 2297 : 1532, snapshots.get("etf_share").actualRows());
        assertEquals(increment ? 6412 : 4274, snapshots.get("etf_daily").actualRows());
        assertEquals(2958, snapshots.get("etf_basic").actualRows());
    }
    private static long number(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name); assertFalse(rs.wasNull()); assertTrue(value >= 0); return value;
    }
    private static Long nullableNumber(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name); if (rs.wasNull()) return null; assertTrue(value >= 0); return value;
    }
    private static EtfMarketOverviewDailyCache aggregate(JdbcTemplate jdbc, LocalDate date, String sourceVersion) {
        var rows = jdbc.query(connection -> {
            var statement = connection.prepareStatement(SOURCE_SQL); statement.setQueryTimeout(20); statement.setMaxRows(2);
            var carrier = Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC));
            statement.setTimestamp(1, carrier, utc()); statement.setTimestamp(2, carrier, utc()); return statement;
        }, (rs, index) -> new EtfMarketOverviewDailyCache(date(rs, "trade_date"), number(rs, "etf_count"),
                nullableDouble(rs, "total_share"), nullableDouble(rs, "total_size_yi"), sourceVersion));
        assertEquals(1, rows.size()); return rows.getFirst();
    }
    private static LocalDate date(ResultSet rs, String field) throws SQLException {
        var timestamp = rs.getTimestamp(field, utc()); assertNotNull(timestamp);
        var instant = timestamp.toInstant(); var date = instant.atOffset(ZoneOffset.UTC).toLocalDate();
        assertEquals(date.atStartOfDay().toInstant(ZoneOffset.UTC), instant); return date;
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static Double nullableDouble(ResultSet rs, String field) throws SQLException {
        double value = rs.getDouble(field); if (rs.wasNull()) return null; assertTrue(Double.isFinite(value)); return value;
    }
    private static void storageBits(JdbcTemplate jdbc, EtfMarketOverviewDailyCache row, Comparisons counts) {
        var actual = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT total_share,total_size_yi FROM " + CACHE + " WHERE trade_date=? AND source_version=? LIMIT 2");
            statement.setQueryTimeout(20); statement.setMaxRows(2);
            statement.setTimestamp(1, Timestamp.from(row.tradeDate().atStartOfDay().toInstant(ZoneOffset.UTC)), utc());
            statement.setString(2, row.sourceVersion()); return statement;
        }, (rs, index) -> Arrays.asList(nullableDouble(rs, "total_share"), nullableDouble(rs, "total_size_yi")));
        assertEquals(1, actual.size()); bit(row.totalShare(), actual.getFirst().get(0)); bit(row.totalSizeYi(), actual.getFirst().get(1));
        if (row.totalShare() != null) counts.storageBits++; if (row.totalSizeYi() != null) counts.storageBits++;
    }
    private static final class Comparisons { long fields, referenceBits, storageBits; }
    private static void compare(EtfMarketOverviewDailyCache expected, EtfMarketOverviewDailyCache actual, Comparisons counts) {
        assertEquals(expected.key(), actual.key()); assertEquals(expected.etfCount(), actual.etfCount());
        bit(expected.totalShare(), actual.totalShare()); bit(expected.totalSizeYi(), actual.totalSizeYi()); counts.fields += 5;
        if (expected.totalShare() != null) counts.referenceBits++; if (expected.totalSizeYi() != null) counts.referenceBits++;
    }
    private static void bit(Double expected, Double actual) {
        if (expected == null || actual == null) assertEquals(expected, actual);
        else { assertTrue(Double.isFinite(expected) && Double.isFinite(actual)); assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual)); }
    }
    private static EtfMarketOverviewDailyCache cache(JsonNode node) {
        assertFields(node, CACHE_FIELDS);
        return new EtfMarketOverviewDailyCache(date(node.required("trade_date")), integer(node.required("etf_count")),
                decimal(node.required("total_share")), decimal(node.required("total_size_yi")), node.required("source_version").asText());
    }
    private static MarketBarometerCacheCoverage receipt(JsonNode node) {
        assertFields(node, RECEIPT_FIELDS);
        return new MarketBarometerCacheCoverage(date(node.required("trade_date")), node.required("dataset_id").asText(),
                node.required("source_version").asText(), integer(node.required("row_count")), node.required("content_digest").asText());
    }
    private static void assertFields(JsonNode node, List<String> expected) {
        assertTrue(node.isObject()); var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        assertEquals(Set.copyOf(expected), actual);
    }
    private static LocalDate date(JsonNode node) {
        assertTrue(node.isTextual()); String value = node.asText(); if (value.length() == 10) return LocalDate.parse(value);
        var instant = Instant.parse(value); LocalDate date = instant.atOffset(ZoneOffset.UTC).toLocalDate();
        assertEquals(date.atStartOfDay().toInstant(ZoneOffset.UTC), instant); return date;
    }
    private static long integer(JsonNode node) { assertTrue(node.isIntegralNumber() && node.canConvertToLong()); long value = node.longValue(); assertTrue(value >= 0); return value; }
    private static Double decimal(JsonNode node) { if (node.isNull()) return null; assertTrue(node.isNumber() && Double.isFinite(node.doubleValue())); return node.doubleValue(); }
    private static List<Map<String,Object>> values(List<EtfMarketOverviewDailyCache> rows) {
        var mapper = new EtfMarketOverviewDailyCacheMapper(); return rows.stream().map(row -> mapper.values(row).asMap()).toList();
    }
    private static List<Map<String,Object>> receiptValues(List<MarketBarometerCacheCoverage> rows) {
        var mapper = new MarketBarometerCacheCoverageMapper(); return rows.stream().map(row -> mapper.values(row).asMap()).toList();
    }
    private static DatasetReadQuery rangeQuery(int size) { return new DatasetReadQuery(CACHE_FIELDS, Map.of(), "trade_date", START, RANGE_END, size, null); }
    private static EtfMarketOverviewCacheOwnerGateway.Config initialConfig() {
        String python = System.getenv("D101_PYTHON_EXECUTABLE");
        Path executable = Path.of(python == null || python.isBlank() ? "D:/work/fund_2/back-monitor/.venv/Scripts/python.exe" : python);
        assertTrue(Files.isRegularFile(executable));
        return new EtfMarketOverviewCacheOwnerGateway.Config(executable, Path.of("tools/d101_etf_cache_owner_bridge.py"),
                COMMANDS.resolve("java-owner-bridge"), Path.of("var/d101-isolated-questdb"), 23388, Duration.ofSeconds(90), 50000);
    }
    private static HikariDataSource pool(String name) {
        var pool = new HikariDataSource(); pool.setDriverClassName("org.postgresql.Driver");
        pool.setJdbcUrl("jdbc:postgresql://127.0.0.1:18832/qdb?sslmode=disable"); pool.setUsername("admin"); pool.setPassword("quest");
        pool.setPoolName(name); pool.setMaximumPoolSize(4); pool.setMinimumIdle(0); pool.setConnectionTimeout(5000); pool.setValidationTimeout(3000); return pool;
    }
    private static JsonNode read(Path path) throws Exception {
        assertTrue(Files.isRegularFile(path)); byte[] bytes = Files.readAllBytes(path);
        assertTrue(bytes.length > 0 && bytes.length <= 2 * 1024 * 1024, "Finite complete JSON evidence required");
        return JobDefinitionJson.mapper().readTree(bytes);
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void saveNew(Path path, Object value) throws Exception {
        Files.writeString(path, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
    private static void assumeStage(String value) { assumeTrue(value.equals(System.getenv("D101_LIVE_STAGE")), "Enable only the reviewed D101_LIVE_STAGE=" + value); }
}
