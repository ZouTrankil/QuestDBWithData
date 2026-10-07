package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.RetailSentimentDailyV1Mapper;
import com.zoutrankil.data.repository.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real bounded source slices on the attested private fixture, then the canonical Java native-MV job. */
class RetailSentimentDailyV1LiveMaterializeAcceptanceTest {
    private static final LocalDate START = LocalDate.of(2026, 9, 17);
    private static final LocalDate FIRST_END = LocalDate.of(2026, 9, 18);
    private static final LocalDate INCREMENT_DAY = LocalDate.of(2026, 9, 21);
    private static final LocalDate LOGICAL = LocalDate.of(2026, 10, 6);
    private static final List<String> SOURCE_FIELDS = List.of("ts", "symbol", "gmm_retail_ratio", "mean_retail_entropy",
            "retail_total_amount", "retail_funds_net_inflow", "mean_rel_aggro", "q1_count", "q3_count",
            "wash_trade_ratio", "spoof_count", "fake_support_count", "fake_pressure_count", "mfi_score", "main_net_inflow");
    private static final Set<String> SOURCE_LONGS = Set.of("q1_count", "q3_count", "spoof_count", "fake_support_count", "fake_pressure_count");
    private static final Path OUTPUT = Path.of("artifacts/java-migration/D098/commands/java-materialize-acceptance-20261006.json");
    private static final Path CONTINUE_ARTIFACT = Path.of("artifacts/java-migration/D098/commands/increment-preflight-failure-java-materialize-acceptance-20261006.json");
    private static final String CONTINUE_ARTIFACT_SHA = "dffa3d6d4712d14749bca29a4ee12cfe2c67611c68d5cb45f3c2fe99ac7ea02a";
    private static final Path CONTINUE_LEDGER = Path.of("var/d098-java-309eeca7-687f-406c-9a1c-850681d6f248.sqlite3");
    private static final String CONTINUE_FAILED_RUN = "d098-3ba8f639-5c8b-4d29-9b28-26e232228b2c";
    private static final Path CLI_RESUME = Path.of("artifacts/java-migration/D098/commands/java-cli-increment-resume-20261006.json");

    @Test void realSourceFirstReplayResumeIncrementEmptyInvalidationAndTrackedFullRecovery() throws Throwable {
        boolean initialMode = "true".equalsIgnoreCase(System.getenv("D098_LIVE_MATERIALIZE"));
        boolean continueMode = "true".equalsIgnoreCase(System.getenv("D098_LIVE_MATERIALIZE_CONTINUE"));
        assumeTrue(initialMode || continueMode);
        assertFalse(initialMode && continueMode, "Choose initial materialization or explicit pinned continuation, never both");
        JsonNode previous = continueMode ? pinnedContinuation() : null;
        var properties = new QuestDbProperties();
        properties.setHost("127.0.0.1"); properties.setPgPort(18822); properties.setQwpPort(19010);
        properties.setUsername("admin"); properties.setPassword("quest"); properties.setDatabase("qdb");
        try (var privatePool = pool("d098-private-live", "127.0.0.1", 18822, "admin", "quest");
             var formalPool = pool("d098-formal-select-only", required("APP_QUESTDB_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("APP_QUESTDB_PGPORT", "8812")),
                required("APP_QUESTDB_USERNAME"), required("APP_QUESTDB_PASSWORD"))) {
        var privateJdbc = jdbc(privatePool);
        var formal = jdbc(formalPool);
        var guard = new RetailSentimentDailyV1MaterializationPort(privateJdbc, properties, true);
        Path ledgerPath = continueMode ? CONTINUE_LEDGER : Path.of("var/d098-java-" + UUID.randomUUID() + ".sqlite3");
        var owner = new RetailSentimentDailyV1JobService(privateJdbc, properties, ledgerPath.toString(), true);
        LinkedHashMap<String, Object> evidence = continueMode
                ? JobDefinitionJson.mapper().convertValue(previous, new TypeReference<LinkedHashMap<String, Object>>() {})
                : new LinkedHashMap<>();
        if (continueMode) {
            evidence.put("previousAttemptCheckedAt", previous.path("checkedAt"));
            evidence.put("previousAttemptFailure", Map.of("status", "FAILED", "errorClass", previous.path("errorClass").asText(),
                    "incremental", previous.path("incremental"), "artifact", CONTINUE_ARTIFACT.toString(), "artifactSha256", CONTINUE_ARTIFACT_SHA));
            evidence.remove("errorClass");
            evidence.put("executionMode", "PINNED_CONTINUATION_AFTER_REAL_CLI_RESUME");
        }
        evidence.put("taskId", "D098"); evidence.put("checkedAt", Instant.now()); evidence.put("status", "IN_PROGRESS");
        evidence.put("target", "private-127.0.0.1:19010/18822");
        evidence.put("privateDataRoot", Path.of("var/d098-isolated-questdb").toAbsolutePath().normalize().toString());
        evidence.put("ledger", ledgerPath.toString()); evidence.put("formalMutated", false); evidence.put("formalWrittenRows", 0);
        evidence.put("sourceUniverseReadyCertified", false);
        var sourceSubmissions = new ArrayList<Map<String, Object>>();
        if (!continueMode) evidence.put("fixtureSourceSubmissions", sourceSubmissions);
        Raw restoreRow = null;
        boolean restoreNeeded = false;
        Throwable originalFailure = null;
        try {
            guard.verifyPrivateInstance();
            var formalBefore = formalState(formal);
            assertEquals("invalid", formalBefore.mv().status(), "Formal invalid MV is retained without repair");
            assertTrue(formalBefore.source().settled());
            evidence.put("formalBefore", formalBefore);
            await(() -> tableState(privateJdbc, "l2_daily_features").settled(), Duration.ofSeconds(90));
            long initialCount = count(privateJdbc, "l2_daily_features");
            evidence.put(continueMode ? "continuationInitialSourceRows" : "initialSourceRows", initialCount);
            if (!continueMode && initialCount != 15828L) {
                evidence.put("errorCode", "RESET_REQUIRED");
                fail("reset_required: initial private source must contain exactly the original two days/15828 real rows; this test never resets it");
            }
            assertEquals(15828L, boundedCount(privateJdbc, START, FIRST_END.plusDays(1)));
            assertEquals(continueMode ? 7945L : 0L, boundedCount(privateJdbc, INCREMENT_DAY, INCREMENT_DAY.plusDays(1)));
            var initialSourceState = tableState(privateJdbc, "l2_daily_features");
            var calendarBefore = tableState(privateJdbc, "exchange_calendar");
            assertTrue(calendarBefore.settled());
            var calendar = calendar(privateJdbc);
            assertEquals(20, calendar.size());
            assertEquals(0, calendar.stream().filter(row -> row.date().equals(LocalDate.of(2026, 9, 20))).findFirst().orElseThrow().open());
            assertEquals(1, calendar.stream().filter(row -> row.date().equals(LocalDate.of(2026, 9, 22))).findFirst().orElseThrow().open());
            evidence.put("calendarRows", calendar.size()); evidence.put("calendar", calendar);
            evidence.put("calendarSnapshot", calendarBefore);

            var port = new RetailSentimentDailyV1MaterializationPort(privateJdbc, properties, false);
            var repository = new RetailSentimentDailyV1ReadRepository(new QuestDbBoundedReader(privateJdbc));
            if (continueMode) {
                var incremental = verifyContinuation(previous, ledgerPath, privateJdbc, formal, port, owner,
                        formalBefore, calendarBefore, calendar, evidence);
                phase(evidence, "incremental", owner, ledgerPath, incremental, SyncRunState.VERIFIED, 23773L, 3);
                assertEquals(0, incremental.result().reusedRows(), "Preflight FAILED run had no verified slice to reuse");
            } else {
            guard.verifyPrivateInstance();
            owner.installIsolated();
            await(() -> ready(privateJdbc, properties, true), Duration.ofSeconds(90));
            assertEquals(initialSourceState, tableState(privateJdbc, "l2_daily_features"), "MV installation must not alter source rows");
            var firstPlan = owner.plan(START, FIRST_END, LOGICAL, null);
            assertEquals(SyncJobDefinition.Mode.INCREMENTAL, firstPlan.request().mode());
            assertFalse(firstPlan.request().parameters().containsKey("checkpoint_before"));
            guard.verifyPrivateInstance();
            var first = owner.run(firstPlan);
            phase(evidence, "first", owner, ledgerPath, first, SyncRunState.VERIFIED, 15828L, 2);
            var initialRows = repository.findRange(START, FIRST_END.plusDays(1), 31, null).rows();
            assertEquals(List.of(START, FIRST_END), initialRows.stream().map(RetailSentimentDailyV1::tradeDate).toList());
            assertRows(port.expected(START, FIRST_END), initialRows);

            var replayPlan = owner.plan(START, FIRST_END, LOGICAL, null);
            assertEquals(FIRST_END, replayPlan.request().parameters().get("checkpoint_before"));
            guard.verifyPrivateInstance();
            var replay = owner.run(replayPlan);
            phase(evidence, "replay", owner, ledgerPath, replay, SyncRunState.VERIFIED, 15828L, 2);
            assertEquals(initialRows, repository.findRange(START, FIRST_END.plusDays(1), 31, null).rows());
            var beforeResume = port.snapshot();
            assertThrows(IllegalStateException.class, () -> owner.resume(first.result().runId()),
                    "A VERIFIED run is complete and cannot enter the failed/cancelled/partial recovery path");
            assertEquals(beforeResume, port.snapshot(), "Rejected VERIFIED resume must issue no native refresh");
            evidence.put("verifiedResumeRejected", Map.of("runId", first.result().runId(),
                    "sourceAndMvSnapshotUnchanged", true, "nativeRefreshSubmitted", false));

            // Exercise canonical pre-submission cancellation without fabricating ledger states or sending SQL.
            var cancelledRunId = "d098-pre-submit-cancel-" + UUID.randomUUID();
            var cancelledLedger = new SyncRunLedger(ledgerPath);
            var cancelledLocks = new DatasetIntervalLock(ledgerPath);
            var cancelledPort = new RetailSentimentDailyV1MaterializationPort(privateJdbc, properties, true);
            var cancelledAdapter = new RetailSentimentDailyV1MaterializeAdapter(cancelledPort, firstPlan.source());
            var conflictScope = DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily_v1");
            assertEquals(conflictScope, cancelledAdapter.conflictScope(firstPlan.request()));
            var cancelledRunner = new SyncJobRunner<RetailSentimentDailyV1, LocalDate>(cancelledLedger, cancelledLocks);
            var cancelledResult = cancelledRunner.run(cancelledRunId, null, firstPlan.targetId(),
                    firstPlan.request(), cancelledAdapter, () -> true);
            assertEquals(SyncRunState.CANCELLED, cancelledResult.state());
            assertEquals(0, cancelledResult.sourceRows()); assertEquals(0, cancelledResult.verifiedRows());
            assertEquals(0, cancelledResult.reusedRows());
            var cancelledStatus = owner.status(cancelledRunId);
            assertEquals(SyncRunState.CANCELLED, cancelledStatus.state());
            assertEquals(0, cancelledStatus.verifiedRows()); assertEquals(0, cancelledStatus.unresolvedSlices());
            var cancelledEntries = cancelledLedger.entries(cancelledRunId, null, 100);
            assertTrue(cancelledEntries.stream().noneMatch(entry -> entry.kind() == SyncRunLedger.Kind.SLICE));
            assertNull(cancelledLocks.findOwned(cancelledRunId, conflictScope));
            assertEquals(beforeResume, port.snapshot(), "Cancellation before preflight must issue no source write or native refresh");
            evidence.put("preSubmissionCancelled", Map.of("result", cancelledResult, "status", cancelledStatus,
                    "ledgerEntries", cancelledEntries, "conflictScope", conflictScope,
                    "nativeRefreshSubmitted", false, "sourceWrittenRows", 0, "intervalLeaseReleased", true));
            save(evidence);
            guard.verifyPrivateInstance();
            var resumed = owner.resume(cancelledRunId);
            phase(evidence, "resume", owner, ledgerPath, resumed, SyncRunState.VERIFIED, 15828L, 2);
            assertEquals(0, resumed.result().reusedRows(), "A pre-submission cancelled run has no prior verified slice to reuse");
            assertEquals(initialRows, repository.findRange(START, FIRST_END.plusDays(1), 31, null).rows());
            evidence.put("checkpointBefore", FIRST_END);

            // Formal access is SELECT-only. Every one of the fifteen typed source fields is copied and compared.
            var extractionBefore = formalState(formal);
            assertEquals(formalBefore, extractionBefore);
            var increment = raw(formal, INCREMENT_DAY, INCREMENT_DAY.plusDays(1));
            assertEquals(7945, increment.size());
            assertEquals(extractionBefore, formalState(formal), "Formal identity/physical/WAL/MV state changed during extraction");
            evidence.put("formalExtractionBefore", extractionBefore);
            var insert = "INSERT INTO l2_daily_features (" + String.join(",", SOURCE_FIELDS) + ") VALUES ("
                    + String.join(",", Collections.nCopies(SOURCE_FIELDS.size(), "?")) + ")";
            for (int offset = 0; offset < increment.size(); offset += 500) {
                var batch = increment.subList(offset, Math.min(offset + 500, increment.size()));
                guard.verifyPrivateInstance();
                var submission = new LinkedHashMap<String, Object>();
                submission.put("offset", offset); submission.put("submittedRows", batch.size());
                submission.put("ack", "UNKNOWN"); submission.put("automaticRetry", false);
                sourceSubmissions.add(submission);
                save(evidence);
                privateJdbc.batchUpdate(insert, batch, 500, RetailSentimentDailyV1LiveMaterializeAcceptanceTest::bindRaw);
                submission.put("ack", "ACKNOWLEDGED");
                save(evidence);
            }
            await(() -> tableState(privateJdbc, "l2_daily_features").settled()
                    && boundedCount(privateJdbc, INCREMENT_DAY, INCREMENT_DAY.plusDays(1)) == 7945L,
                    Duration.ofSeconds(90));
            var returned = raw(privateJdbc, INCREMENT_DAY, INCREMENT_DAY.plusDays(1));
            assertEquals(increment.size(), returned.size());
            assertEquals(new HashSet<>(increment), new HashSet<>(returned), "All fifteen exact typed values, including nulls, must survive the source copy");
            assertEquals(sha(increment), sha(returned));
            assertEquals(23773L, count(privateJdbc, "l2_daily_features"));
            assertEquals(extractionBefore, formalState(formal));
            evidence.put("newRealSourceRows", increment.size());
            evidence.put("sourceFields", SOURCE_FIELDS); evidence.put("newRawFieldComparisons", increment.size() * SOURCE_FIELDS.size());
            evidence.put("sourceFieldComparisons", increment.size() * SOURCE_FIELDS.size());
            evidence.put("formalIncrementSha256", sha(increment)); evidence.put("privateIncrementSha256", sha(returned));
            evidence.put("newRawSample", increment.subList(0, Math.min(3, increment.size())));
            await(() -> ready(privateJdbc, properties, false), Duration.ofSeconds(90));
            var incrementalPlan = owner.plan(START, INCREMENT_DAY, LOGICAL, null);
            assertEquals(FIRST_END, incrementalPlan.request().parameters().get("checkpoint_before"));
            guard.verifyPrivateInstance();
            var incremental = owner.run(incrementalPlan);
            phase(evidence, "incremental", owner, ledgerPath, incremental, SyncRunState.VERIFIED, 23773L, 3);
            }
            var allExpected = port.expected(START, INCREMENT_DAY);
            var allActual = port.actual(START, INCREMENT_DAY);
            assertEquals(List.of(START, FIRST_END, INCREMENT_DAY), allActual.stream().map(RetailSentimentDailyV1::tradeDate).toList());
            assertRows(allExpected, allActual);
            assertEquals(3L, port.outputRowCount());

            var beforeMissing = port.snapshot();
            var missingPlan = owner.plan(START, LocalDate.of(2026, 9, 22), LOGICAL, null);
            assertEquals(INCREMENT_DAY, missingPlan.request().parameters().get("checkpoint_before"));
            assertEquals(LocalDate.of(2026, 9, 19), missingPlan.request().from());
            guard.verifyPrivateInstance();
            var missing = owner.run(missingPlan);
            phase(evidence, "missingOpenDateRejected", owner, ledgerPath, missing, SyncRunState.FAILED, 7945L, 0);
            assertEquals(beforeMissing, port.snapshot(), "Missing real open-session coverage must not submit a refresh");
            var checkpoint = owner.plan(START, INCREMENT_DAY, LOGICAL, null);
            assertEquals(INCREMENT_DAY, checkpoint.request().parameters().get("checkpoint_before"));
            assertEquals(LocalDate.of(2026, 9, 19), checkpoint.request().from());
            evidence.put("checkpointAfter", INCREMENT_DAY); evidence.put("revisionFrom", checkpoint.request().from());

            var sunday = LocalDate.of(2026, 9, 20);
            var beforeEmpty = port.snapshot();
            guard.verifyPrivateInstance();
            var empty = owner.run(owner.plan(sunday, sunday, LOGICAL, SyncJobDefinition.Mode.MATERIALIZE));
            phase(evidence, "empty", owner, ledgerPath, empty, SyncRunState.VERIFIED_EMPTY, 0L, 0);
            assertEquals(beforeEmpty, port.snapshot(), "Verified empty must issue no native refresh");
            assertEquals(INCREMENT_DAY, owner.plan(START, INCREMENT_DAY, LOGICAL, null).request().parameters().get("checkpoint_before"));

            restoreRow = raw(privateJdbc, START, START.plusDays(1)).getFirst();
            assertNotNull(restoreRow.retailTotalAmount(), "Revision fault requires the first real key's nonnull amount");
            assertTrue(Double.isFinite(restoreRow.retailTotalAmount() + 1.0));
            evidence.put("revisionRealKey", Map.of("ts", restoreRow.ts(), "symbol", restoreRow.symbol()));
            evidence.put("originalRetailTotalAmount", restoreRow.retailTotalAmount());
            var faultSubmission = new LinkedHashMap<String, Object>();
            faultSubmission.put("ack", "UNKNOWN"); faultSubmission.put("automaticRetry", false);
            faultSubmission.put("field", "retail_total_amount");
            faultSubmission.put("submittedValue", restoreRow.retailTotalAmount() + 1.0);
            evidence.put("revisionFaultSubmission", faultSubmission);
            save(evidence);
            guard.verifyPrivateInstance();
            restoreNeeded = true; // Set before submission: a lost ACK still requires restoration of this real value.
            updateAmount(privateJdbc, restoreRow, restoreRow.retailTotalAmount() + 1.0);
            faultSubmission.put("ack", "ACKNOWLEDGED");
            save(evidence);
            Raw selected = restoreRow;
            await(() -> Objects.equals(selected.retailTotalAmount() + 1.0, rawKey(privateJdbc, selected).retailTotalAmount())
                    && tableState(privateJdbc, "l2_daily_features").settled() && !port.snapshot().valid(), Duration.ofSeconds(90));
            assertThrows(IllegalStateException.class, () -> repository.findForDate(START));
            guard.verifyPrivateInstance();
            var invalid = owner.run(owner.plan(START, FIRST_END, LOGICAL, SyncJobDefinition.Mode.RECONCILE));
            phase(evidence, "invalidRejected", owner, ledgerPath, invalid, SyncRunState.FAILED, 0L, 0);
            guard.verifyPrivateInstance();
            updateAmount(privateJdbc, selected, selected.retailTotalAmount());
            await(() -> selected.equals(rawKey(privateJdbc, selected))
                    && tableState(privateJdbc, "l2_daily_features").settled(), Duration.ofSeconds(90));
            restoreNeeded = false;
            evidence.put("realSourceValueRestored", true);
            guard.verifyPrivateInstance();
            var full = owner.repairIsolated();
            phase(evidence, "fullRepair", owner, ledgerPath, full, SyncRunState.VERIFIED, 23773L, 3);
            var frozenFull = JobDefinitionJson.mapper().readTree(SyncRunLedger.openReadOnly(ledgerPath)
                    .getRun(full.result().runId()).frozenJson());
            assertEquals("FULL_ISOLATED", frozenFull.path("parameters").path("native_refresh").asText());
            assertEquals(SyncJobDefinition.Mode.MATERIALIZE.name(), frozenFull.path("mode").asText());
            await(() -> ready(privateJdbc, properties, true), Duration.ofSeconds(90));
            guard.verifyPrivateInstance();
            var recovery = owner.run(owner.plan(START, INCREMENT_DAY, LOGICAL, SyncJobDefinition.Mode.RECONCILE));
            phase(evidence, "recovery", owner, ledgerPath, recovery, SyncRunState.VERIFIED, 23773L, 3);
            assertRows(allExpected, port.expected(START, INCREMENT_DAY));
            var finalRows = port.actual(START, INCREMENT_DAY);
            assertRows(port.expected(START, INCREMENT_DAY), finalRows);
            assertEquals(3L, port.outputRowCount());
            assertEquals(23773L, count(privateJdbc, "l2_daily_features"));
            assertEquals(calendarBefore, tableState(privateJdbc, "exchange_calendar"));
            assertEquals(calendar, calendar(privateJdbc));
            assertEquals(formalBefore, formalState(formal), "Every formal source and MV physical/WAL version must remain unchanged");
            guard.verifyPrivateInstance();
            var mapper = new RetailSentimentDailyV1Mapper();
            evidence.put("expectedDirectBaseRows", port.expected(START, INCREMENT_DAY).stream().map(row -> mapper.values(row).asMap()).toList());
            evidence.put("actualMvRows", finalRows.stream().map(row -> mapper.values(row).asMap()).toList());
            evidence.put("outputFields", RetailSentimentDailyV1Dataset.DEFINITION.storageColumns());
            evidence.put("outputFieldComparisons", finalRows.size() * 13);
            evidence.put("allOutputFieldsMatched", true); evidence.put("completeDateKeys", finalRows.stream().map(RetailSentimentDailyV1::tradeDate).toList());
            evidence.put("targetId", port.targetId()); evidence.put("finalSnapshot", port.snapshot());
            evidence.put("finalSourceRows", 23773L); evidence.put("formalAfter", formalState(formal));
            evidence.put("physicalInsertedRows", "unknown; actual source/output full keys and values are verified");
            evidence.put("physicalUpdatedRows", "unknown; replay uses the native MV contract");
            evidence.put("status", "VERIFIED_ISOLATED_MATERIALIZATION");
        } catch (Throwable failure) {
            originalFailure = failure;
            evidence.put("status", "FAILED"); evidence.put("errorClass", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            if (restoreNeeded && restoreRow != null) {
                try {
                    guard.verifyPrivateInstance();
                    updateAmount(privateJdbc, restoreRow, restoreRow.retailTotalAmount());
                    Raw selected = restoreRow;
                    await(() -> selected.equals(rawKey(privateJdbc, selected))
                            && tableState(privateJdbc, "l2_daily_features").settled(), Duration.ofSeconds(90));
                    evidence.put("finallyRealSourceValueRestored", true);
                    evidence.put("failureRecoveryFullRefreshSubmitted", false);
                } catch (Throwable restoreFailure) {
                    evidence.put("finallyRestoreErrorClass", restoreFailure.getClass().getSimpleName());
                    if (originalFailure != null) originalFailure.addSuppressed(restoreFailure);
                    else throw restoreFailure;
                }
            }
            try { save(evidence); }
            catch (Throwable evidenceFailure) {
                if (originalFailure != null) originalFailure.addSuppressed(evidenceFailure);
                else throw evidenceFailure;
            }
        }
        }
    }

    private record Raw(Instant ts, String symbol, Double gmmRetailRatio, Double meanRetailEntropy,
                       Double retailTotalAmount, Double retailFundsNetInflow, Double meanRelAggro,
                       Long q1Count, Long q3Count, Double washTradeRatio, Long spoofCount,
                       Long fakeSupportCount, Long fakePressureCount, Double mfiScore, Double mainNetInflow) {
        private Raw {
            Objects.requireNonNull(ts);
            if (symbol == null || symbol.isBlank() || !ts.atOffset(ZoneOffset.UTC).toLocalTime().equals(LocalTime.MIDNIGHT))
                throw new IllegalStateException("D098 raw source needs an exact calendar timestamp and nonblank symbol");
            for (var value : new Double[]{gmmRetailRatio, meanRetailEntropy, retailTotalAmount, retailFundsNetInflow,
                    meanRelAggro, washTradeRatio, mfiScore, mainNetInflow}) {
                if (value != null && !Double.isFinite(value)) throw new IllegalStateException("Nonfinite real D098 source value");
            }
            for (var value : new Long[]{q1Count, q3Count, spoofCount, fakeSupportCount, fakePressureCount}) {
                if (value != null && value < 0) throw new IllegalStateException("Negative real D098 source count");
            }
        }
        private List<Object> values() {
            return Arrays.asList(ts, symbol, gmmRetailRatio, meanRetailEntropy, retailTotalAmount, retailFundsNetInflow,
                    meanRelAggro, q1Count, q3Count, washTradeRatio, spoofCount, fakeSupportCount, fakePressureCount, mfiScore, mainNetInflow);
        }
    }
    private record Key(Instant ts, String symbol) {}
    private record CalendarDay(LocalDate date, int open, String previousDate) {}
    private record TableState(long id, String directory, long physicalTxn, long rows, long pendingRows,
                              long sequencerTxn, long writerTxn, long bufferedTxnSize, boolean tableSuspended, boolean walSuspended) {
        boolean settled() { return !tableSuspended && !walSuspended && pendingRows == 0 && bufferedTxnSize == 0 && sequencerTxn == writerTxn; }
    }
    private record MvState(String status, String invalidationReason, String base, String sql,
                           Long refreshed, Long reportedBase) {}
    private record FormalState(TableState source, TableState mvPhysical, MvState mv) {}

    private static JsonNode pinnedContinuation() throws Exception {
        assertTrue(Files.isRegularFile(CONTINUE_ARTIFACT), "Pinned failed attempt evidence is required for continuation");
        byte[] bytes = Files.readAllBytes(CONTINUE_ARTIFACT);
        assertTrue(bytes.length > 0 && bytes.length < 2 * 1024 * 1024, "Finite pinned evidence required");
        assertEquals(CONTINUE_ARTIFACT_SHA, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "Continuation must consume the preserved original FAILED attempt, not regenerated evidence");
        var previous = JobDefinitionJson.mapper().readTree(bytes);
        assertEquals("D098", previous.path("taskId").asText());
        assertEquals("FAILED", previous.path("status").asText());
        assertFalse(previous.path("formalMutated").asBoolean(true));
        assertEquals(0, previous.path("formalWrittenRows").asInt(-1));
        assertEquals(CONTINUE_LEDGER.toAbsolutePath().normalize(),
                Path.of(previous.path("ledger").asText()).toAbsolutePath().normalize());
        assertTrue(Files.isRegularFile(CONTINUE_LEDGER), "Original real SQLite ledger must already exist");
        assertEquals(CONTINUE_FAILED_RUN, previous.path("incremental").path("result").path("result").path("runId").asText());
        assertEquals("FAILED", previous.path("incremental").path("result").path("result").path("state").asText());
        assertEquals("IllegalStateException", previous.path("incremental").path("result").path("result").path("errorCode").asText());
        assertEquals(0, previous.path("incremental").path("result").path("result").path("sourceRows").asInt(-1));
        assertEquals(0, previous.path("incremental").path("result").path("result").path("verifiedRows").asInt(-1));
        assertEquals(119175, previous.path("sourceFieldComparisons").asInt(-1));
        assertEquals(7945, previous.path("newRealSourceRows").asInt(-1));
        assertEquals(JobDefinitionJson.mapper().valueToTree(SOURCE_FIELDS), previous.path("sourceFields"));
        assertEquals(previous.path("formalIncrementSha256"), previous.path("privateIncrementSha256"));
        var submissions = previous.path("fixtureSourceSubmissions");
        assertTrue(submissions.isArray()); assertEquals(16, submissions.size());
        int acknowledgedRows = 0;
        for (int index = 0; index < submissions.size(); index++) {
            var submission = submissions.get(index);
            assertEquals(index * 500, submission.path("offset").asInt(-1));
            assertEquals(index == 15 ? 445 : 500, submission.path("submittedRows").asInt(-1));
            assertEquals("ACKNOWLEDGED", submission.path("ack").asText());
            assertFalse(submission.path("automaticRetry").asBoolean(true));
            acknowledgedRows += submission.path("submittedRows").asInt();
        }
        assertEquals(7945, acknowledgedRows);
        return previous;
    }

    /** Observes the already completed CLI resume; this path does not install or resubmit source/native SQL. */
    private static RetailSentimentDailyV1JobService.MaterializationResult verifyContinuation(
            JsonNode previous, Path ledgerPath, JdbcTemplate privateJdbc, JdbcTemplate formal,
            RetailSentimentDailyV1MaterializationPort port, RetailSentimentDailyV1JobService owner,
            FormalState formalBefore, TableState calendarBefore, List<CalendarDay> calendar,
            Map<String, Object> evidence) throws Exception {
        var json = JobDefinitionJson.mapper();
        assertEquals(previous.path("formalBefore"), asJson(formalBefore), "Original formal physical/WAL/MV identity must remain unchanged");
        assertEquals(previous.path("formalExtractionBefore"), asJson(formalBefore));
        assertEquals(previous.path("calendarSnapshot"), asJson(calendarBefore));
        assertEquals(previous.path("calendar"), asJson(calendar));
        assertEquals(23773L, count(privateJdbc, "l2_daily_features"));
        assertEquals(23773L, boundedCount(privateJdbc, START, INCREMENT_DAY.plusDays(1)), "No private source keys outside the three attested dates");
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var failed = ledger.getRun(CONTINUE_FAILED_RUN);
        assertEquals(RetailSentimentDailyV1JobService.JOB_ID, failed.jobId());
        assertEquals(RetailSentimentDailyV1JobService.definition().version(), failed.jobVersion());
        assertEquals(SyncRunState.FAILED, ledger.get(CONTINUE_FAILED_RUN).state(), "Original failed run remains FAILED after its child resumes");
        var failedEntries = ledger.entries(CONTINUE_FAILED_RUN, null, 100);
        assertEquals(1, failedEntries.size(), "Preflight failure must have no ATTEMPT, SLICE, or native intent");
        assertEquals(SyncRunLedger.Kind.RUN, failedEntries.getFirst().kind());
        assertEquals("IllegalStateException", json.readTree(failedEntries.getFirst().payloadJson()).path("errorCode").asText());
        assertNull(new DatasetIntervalLock(ledgerPath).findOwned(CONTINUE_FAILED_RUN,
                DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily_v1")));
        evidence.put("originalFailedIncrementRun", Map.of("run", failed, "ledgerEntries", failedEntries,
                "state", "FAILED", "errorCode", "IllegalStateException", "submittedRefresh", false,
                "attempts", 0, "slices", 0, "intervalLeaseReleased", true,
                "failureCauseBeyondErrorCode", "not established by durable ledger"));
        for (var name : List.of("first", "replay", "resume")) {
            var oldPhase = previous.path(name);
            var runId = oldPhase.path("result").path("result").path("runId").asText();
            assertFalse(runId.isBlank());
            var run = ledger.getRun(runId);
            assertEquals(SyncRunState.VERIFIED, ledger.get(runId).state());
            assertEquals(RetailSentimentDailyV1JobService.JOB_ID, run.jobId());
            assertEquals(failed.targetId(), run.targetId());
            assertEquals(15828L, oldPhase.path("result").path("sourceRawRows").asLong(-1));
            assertEquals(2, oldPhase.path("result").path("result").path("verifiedRows").asInt(-1));
            assertEquals(oldPhase.path("ledgerEntries"), asJson(ledger.entries(runId, null, 100)));
            assertNull(new DatasetIntervalLock(ledgerPath).findOwned(runId,
                    DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily_v1")));
        }
        var cancelledId = previous.path("preSubmissionCancelled").path("result").path("runId").asText();
        assertEquals(SyncRunState.CANCELLED, ledger.get(cancelledId).state());
        assertEquals(previous.path("preSubmissionCancelled").path("ledgerEntries"), asJson(ledger.entries(cancelledId, null, 100)));
        assertEquals(cancelledId, ledger.getRun(previous.path("resume").path("result").path("result").path("runId").asText()).parentRunId());
        assertTrue(previous.path("verifiedResumeRejected").path("sourceAndMvSnapshotUnchanged").asBoolean());

        var frozen = FrozenRunRequest.restore(ledgerPath, CONTINUE_FAILED_RUN, RetailSentimentDailyV1JobService.definition());
        assertEquals(SyncJobDefinition.Mode.INCREMENTAL, frozen.request().mode());
        assertEquals(START, frozen.request().from()); assertEquals(INCREMENT_DAY, frozen.request().to());
        assertEquals(LOGICAL, frozen.request().logicalDate());
        assertEquals(FIRST_END, frozen.request().parameters().get("checkpoint_before"));
        assertEquals(START, frozen.request().parameters().get("bootstrap_from"));
        assertEquals("9:34", frozen.request().parameters().get("source_version"));
        assertEquals(port.calendarVersion(), frozen.request().parameters().get("calendar_version"));
        assertEquals(failed.targetId(), frozen.targetId()); assertEquals(failed.targetId(), port.targetId());
        var originalSource = json.treeToValue(previous.path("incremental").path("result").path("source"),
                RetailSentimentDailyV1MaterializationPort.Snapshot.class);
        var sourceBefore = port.snapshot();
        assertTrue(originalSource.sourceUnchanged(sourceBefore), "No new source transaction or physical identity may be admitted by continuation");
        assertEquals("9:34", sourceBefore.sourceVersion());
        assertTrue(sourceBefore.valid() && sourceBefore.caughtUp() && sourceBefore.sourceSettled() && sourceBefore.mvSettled());
        sameMvIdentity(originalSource, sourceBefore);
        var comparisons = new ArrayList<Map<String, Object>>();
        int totalRows = 0;
        for (var day : List.of(START, FIRST_END, INCREMENT_DAY)) {
            var expected = raw(formal, day, day.plusDays(1));
            var actual = raw(privateJdbc, day, day.plusDays(1));
            assertFalse(expected.isEmpty()); assertEquals(expected.size(), actual.size());
            assertTrue(expected.equals(actual), "All fifteen exact typed fields must match formal source for " + day);
            assertEquals(sha(expected), sha(actual));
            if (day.equals(INCREMENT_DAY)) {
                assertEquals(7945, expected.size()); assertEquals(previous.path("formalIncrementSha256").asText(), sha(expected));
            }
            totalRows += actual.size();
            comparisons.add(Map.of("date", day, "rows", actual.size(), "completeKeysUnique", true,
                    "fieldsPerRow", SOURCE_FIELDS.size(), "fieldComparisons", actual.size() * SOURCE_FIELDS.size(),
                    "formalSha256", sha(expected), "privateSha256", sha(actual), "allFieldsMatched", true));
        }
        assertEquals(23773, totalRows);
        assertEquals(formalBefore, formalState(formal));
        var sourceAfter = port.snapshot();
        assertTrue(sourceBefore.sourceUnchanged(sourceAfter)); sameMvIdentity(sourceBefore, sourceAfter);
        assertTrue(sourceAfter.valid() && sourceAfter.caughtUp() && sourceAfter.mvSettled());
        evidence.put("continuationFixtureRevalidation", Map.of("sourceWrittenRows", 0, "nativeRefreshSubmitted", false,
                "sourceBefore", sourceBefore, "sourceAfter", sourceAfter, "perDay", comparisons,
                "sourceRows", totalRows, "allTypedFieldComparisons", totalRows * SOURCE_FIELDS.size()));

        assertTrue(Files.isRegularFile(CLI_RESUME), "Root must complete and preserve actual CLI resume before continuation");
        byte[] cliBytes = Files.readAllBytes(CLI_RESUME);
        assertTrue(cliBytes.length > 0 && cliBytes.length < 2 * 1024 * 1024);
        var cli = json.readTree(cliBytes);
        assertTrue(cli.path("actualCli").asBoolean());
        assertEquals(0, cli.path("formalWrittenRows").asInt(-1));
        assertEquals(CONTINUE_FAILED_RUN, cli.path("previousRunId").asText());
        assertEquals(ledgerPath.toAbsolutePath().normalize(), Path.of(cli.path("ledger").asText()).toAbsolutePath().normalize());
        assertTrue(cli.path("parentRunMatched").asBoolean()); assertTrue(cli.path("frozenRequestMatched").asBoolean());
        assertEquals(0, cli.path("retainedIntervalLeases").asInt(-1));
        var materialization = cli.path("materialization");
        assertTrue(materialization.isObject(), "Actual CLI evidence must contain its exact MaterializationResult");
        var resumed = json.treeToValue(materialization, RetailSentimentDailyV1JobService.MaterializationResult.class);
        assertNotNull(resumed); assertNotNull(resumed.result());
        assertEquals("d098-eea0c8a9-0645-4acb-b9d9-2fa8d7e91bc6", resumed.result().runId());
        assertEquals(SyncRunState.VERIFIED, resumed.result().state());
        assertEquals(23773L, resumed.sourceRawRows()); assertEquals(3, resumed.result().verifiedRows());
        assertEquals(0, resumed.result().reusedRows());
        assertNotNull(resumed.source()); assertNotNull(resumed.target()); assertNull(resumed.targetSnapshotError());
        assertTrue(originalSource.sourceUnchanged(resumed.source())); assertTrue(originalSource.sourceUnchanged(resumed.target()));
        sameMvIdentity(originalSource, resumed.source()); sameMvIdentity(originalSource, resumed.target());
        assertTrue(resumed.target().valid() && resumed.target().caughtUp() && resumed.target().mvSettled());
        var resumedRun = ledger.getRun(resumed.result().runId());
        assertEquals(CONTINUE_FAILED_RUN, resumedRun.parentRunId());
        assertEquals(failed.targetId(), resumedRun.targetId());
        assertEquals(SyncRequestIdentity.fingerprint(failed.frozenJson(), failed.targetId()),
                SyncRequestIdentity.fingerprint(resumedRun.frozenJson(), resumedRun.targetId()), "CLI resumed the original frozen request exactly");
        assertEquals(SyncRunState.VERIFIED, ledger.get(resumedRun.id()).state());
        assertEquals(SyncRunState.VERIFIED, owner.status(resumedRun.id()).state());
        evidence.put("actualCliIncrementResume", Map.of("artifact", CLI_RESUME.toString(),
                "artifactSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cliBytes)),
                "previousRunId", CONTINUE_FAILED_RUN, "result", resumed, "frozenRequestIdentityMatched", true,
                "continuationResubmittedRefresh", false));
        save(evidence);
        return resumed;
    }

    private static void sameMvIdentity(RetailSentimentDailyV1MaterializationPort.Snapshot expected,
                                       RetailSentimentDailyV1MaterializationPort.Snapshot actual) {
        assertEquals(expected.mvId(), actual.mvId()); assertEquals(expected.mvDirectory(), actual.mvDirectory());
        assertEquals(expected.definitionSha(), actual.definitionSha());
    }

    private static JsonNode asJson(Object value) throws Exception {
        // Parse the emitted JSON exactly as the preserved evidence was parsed, including integer-node widths.
        return JobDefinitionJson.mapper().readTree(JobDefinitionJson.mapper().writeValueAsBytes(value));
    }

    private static void phase(Map<String, Object> evidence, String name, RetailSentimentDailyV1JobService owner,
                              Path ledgerPath, RetailSentimentDailyV1JobService.MaterializationResult result,
                              SyncRunState state, long sourceRows, int verifiedRows) throws Exception {
        var details = new LinkedHashMap<String, Object>();
        details.put("result", result); evidence.put(name, details);
        save(evidence);
        assertEquals(state, result.result().state(), result.toString());
        assertEquals(sourceRows, result.sourceRawRows(), name);
        assertEquals(verifiedRows, result.result().verifiedRows(), name);
        var status = owner.status(result.result().runId());
        assertEquals(state, status.state()); assertEquals(verifiedRows, status.verifiedRows());
        if (state == SyncRunState.VERIFIED || state == SyncRunState.VERIFIED_EMPTY) assertEquals(0, status.unresolvedSlices());
        var entries = SyncRunLedger.openReadOnly(ledgerPath).entries(result.result().runId(), null, 100);
        assertTrue(entries.size() < 100, "Bounded single-page job must not exceed the ledger export budget");
        if (state == SyncRunState.VERIFIED || state == SyncRunState.VERIFIED_EMPTY) {
            var slices = entries.stream().filter(row -> row.kind() == SyncRunLedger.Kind.SLICE).toList();
            assertEquals(1, slices.size()); assertEquals(state, slices.getFirst().state());
        }
        assertNull(new DatasetIntervalLock(ledgerPath).findOwned(result.result().runId(),
                DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily_v1")), "Terminal observed phase must release exclusion");
        details.put("status", status); details.put("ledgerEntries", entries); details.put("intervalLeaseReleased", true);
    }

    private static List<Raw> raw(JdbcTemplate jdbc, LocalDate fromInclusive, LocalDate toExclusive) {
        var rows = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT " + String.join(",", SOURCE_FIELDS)
                    + " FROM l2_daily_features WHERE ts >= ? AND ts < ? ORDER BY ts,symbol LIMIT 10001");
            statement.setTimestamp(1, Timestamp.from(fromInclusive.atStartOfDay().toInstant(ZoneOffset.UTC)), utc());
            statement.setTimestamp(2, Timestamp.from(toExclusive.atStartOfDay().toInstant(ZoneOffset.UTC)), utc());
            statement.setFetchSize(500);
            return statement;
        }, (rs, row) -> rawRow(rs));
        if (rows.size() > 10000 || new HashSet<>(rows.stream().map(row -> new Key(row.ts(), row.symbol())).toList()).size() != rows.size())
            throw new IllegalStateException("D098 complete source key duplicated or raw slice reached its finite row cap");
        for (var row : rows) {
            var day = row.ts().atOffset(ZoneOffset.UTC).toLocalDate();
            if (day.isBefore(fromInclusive) || !day.isBefore(toExclusive)) throw new IllegalStateException("D098 source query returned an out-of-window key");
        }
        return rows;
    }

    private static Raw rawKey(JdbcTemplate jdbc, Raw key) {
        var rows = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT " + String.join(",", SOURCE_FIELDS)
                    + " FROM l2_daily_features WHERE ts=? AND symbol=? LIMIT 2");
            statement.setTimestamp(1, Timestamp.from(key.ts()), utc()); statement.setString(2, key.symbol());
            return statement;
        }, (rs, row) -> rawRow(rs));
        if (rows.size() != 1) throw new IllegalStateException("D098 exact real source key missing or duplicated");
        return rows.getFirst();
    }

    private static Raw rawRow(ResultSet rs) throws SQLException {
        var timestamp = rs.getTimestamp("ts", utc());
        if (timestamp == null) throw new IllegalStateException("D098 source timestamp missing");
        return new Raw(timestamp.toInstant(), rs.getString("symbol"), number(rs, "gmm_retail_ratio"), number(rs, "mean_retail_entropy"),
                number(rs, "retail_total_amount"), number(rs, "retail_funds_net_inflow"), number(rs, "mean_rel_aggro"),
                integer(rs, "q1_count"), integer(rs, "q3_count"), number(rs, "wash_trade_ratio"), integer(rs, "spoof_count"),
                integer(rs, "fake_support_count"), integer(rs, "fake_pressure_count"), number(rs, "mfi_score"), number(rs, "main_net_inflow"));
    }

    private static void bindRaw(PreparedStatement statement, Raw row) throws SQLException {
        var values = row.values();
        statement.setTimestamp(1, Timestamp.from(row.ts()), utc()); statement.setString(2, row.symbol());
        for (int index = 2; index < SOURCE_FIELDS.size(); index++) {
            Object value = values.get(index);
            boolean integer = SOURCE_LONGS.contains(SOURCE_FIELDS.get(index));
            if (value == null) statement.setNull(index + 1, integer ? Types.BIGINT : Types.DOUBLE);
            else if (integer) statement.setLong(index + 1, (Long) value);
            else statement.setDouble(index + 1, (Double) value);
        }
    }

    private static void updateAmount(JdbcTemplate jdbc, Raw key, Double value) {
        jdbc.update("UPDATE l2_daily_features SET retail_total_amount=? WHERE ts=? AND symbol=?", statement -> {
            if (value == null) statement.setNull(1, Types.DOUBLE); else statement.setDouble(1, value);
            statement.setTimestamp(2, Timestamp.from(key.ts()), utc()); statement.setString(3, key.symbol());
        });
    }

    private static List<CalendarDay> calendar(JdbcTemplate jdbc) {
        var rows = jdbc.query("SELECT exchange,cal_date,is_open,pretrade_date FROM exchange_calendar ORDER BY cal_date LIMIT 21", (rs, row) -> {
            if (!"SSE".equals(rs.getString("exchange"))) throw new IllegalStateException("D098 fixture calendar exchange differs");
            var timestamp = rs.getTimestamp("cal_date", utc());
            if (timestamp == null || !timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalTime().equals(LocalTime.MIDNIGHT))
                throw new IllegalStateException("D098 calendar date carrier differs");
            int open = rs.getInt("is_open");
            if (rs.wasNull() || open < 0 || open > 1) throw new IllegalStateException("D098 calendar open flag differs");
            return new CalendarDay(timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDate(), open, rs.getString("pretrade_date"));
        });
        assertEquals(20, rows.size());
        for (int index = 0; index < rows.size(); index++) assertEquals(LocalDate.of(2026, 9, 10).plusDays(index), rows.get(index).date());
        return rows;
    }

    private static TableState tableState(JdbcTemplate jdbc, String table) {
        if (!Set.of("l2_daily_features", "mv_retail_sentiment_daily_v1", "exchange_calendar").contains(table))
            throw new IllegalArgumentException("D098 fixed metadata table required");
        return jdbc.query("SELECT t.id,t.directoryName,t.table_txn,t.table_row_count,t.wal_pending_row_count,t.table_suspended,"
                + "w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended AS wal_suspended "
                + "FROM tables() t JOIN wal_tables() w ON t.table_name=w.name WHERE t.table_name='" + table + "'",
                (org.springframework.jdbc.core.ResultSetExtractor<TableState>) rs -> {
                    if (!rs.next()) throw new IllegalStateException("D098 metadata table absent: " + table);
                    String directory = rs.getString("directoryName");
                    if (directory == null || directory.isBlank()) throw new IllegalStateException("D098 metadata directory absent");
                    var result = new TableState(requiredInteger(rs, "id"), directory, requiredInteger(rs, "table_txn"),
                            requiredInteger(rs, "table_row_count"), requiredInteger(rs, "wal_pending_row_count"),
                            requiredInteger(rs, "sequencerTxn"), requiredInteger(rs, "writerTxn"), requiredInteger(rs, "bufferedTxnSize"),
                            flag(rs, "table_suspended"), flag(rs, "wal_suspended"));
                    if (rs.next()) throw new IllegalStateException("D098 duplicate table metadata");
                    return result;
                });
    }

    private static FormalState formalState(JdbcTemplate jdbc) {
        var source = tableState(jdbc, "l2_daily_features");
        var output = tableState(jdbc, "mv_retail_sentiment_daily_v1");
        var mv = jdbc.query("SELECT view_status,invalidation_reason,base_table_name,view_sql,refresh_base_table_txn,base_table_txn "
                + "FROM materialized_views() WHERE view_name='mv_retail_sentiment_daily_v1'", (org.springframework.jdbc.core.ResultSetExtractor<MvState>) rs -> {
                    if (!rs.next()) throw new IllegalStateException("D098 formal MV missing");
                    var result = new MvState(rs.getString("view_status"), rs.getString("invalidation_reason"),
                            rs.getString("base_table_name"), rs.getString("view_sql"), integer(rs, "refresh_base_table_txn"), integer(rs, "base_table_txn"));
                    if (rs.next()) throw new IllegalStateException("D098 formal MV duplicated");
                    return result;
                });
        return new FormalState(source, output, mv);
    }

    private static long count(JdbcTemplate jdbc, String table) {
        if (!Set.of("l2_daily_features", "exchange_calendar").contains(table)) throw new IllegalArgumentException("Fixed source count required");
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count() FROM " + table, Long.class));
    }

    private static long boundedCount(JdbcTemplate jdbc, LocalDate from, LocalDate toExclusive) {
        Long value = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT count() AS n FROM l2_daily_features WHERE ts>=? AND ts<?");
            statement.setTimestamp(1, Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)), utc());
            statement.setTimestamp(2, Timestamp.from(toExclusive.atStartOfDay().toInstant(ZoneOffset.UTC)), utc());
            return statement;
        }, (org.springframework.jdbc.core.ResultSetExtractor<Long>) rs -> {
            if (!rs.next()) throw new IllegalStateException("D098 bounded source count missing");
            long rows = requiredInteger(rs, "n");
            if (rs.next()) throw new IllegalStateException("D098 bounded source count duplicated");
            return rows;
        });
        return Objects.requireNonNull(value);
    }

    private static boolean ready(JdbcTemplate jdbc, QuestDbProperties properties, boolean caughtUp) {
        var state = new RetailSentimentDailyV1MaterializationPort(jdbc, properties, false).snapshot();
        return state.valid() && state.sourceSettled() && state.mvSettled() && (!caughtUp || state.caughtUp());
    }
    private static void assertRows(List<RetailSentimentDailyV1> expected, List<RetailSentimentDailyV1> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++)
            assertTrue(RetailSentimentDailyV1MaterializationPort.equivalent(expected.get(index), actual.get(index)),
                    "All thirteen actual source/MV values must agree for " + expected.get(index).tradeDate());
    }
    @FunctionalInterface private interface Check { boolean test() throws Exception; }
    private static void await(Check check, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try { if (check.test()) return; }
            catch (IllegalStateException transientMetadata) {
                if (!"D098 metadata changed during snapshot".equals(transientMetadata.getMessage())) throw transientMetadata;
            }
            Thread.sleep(100);
        }
        fail("D098 isolated state did not become visible within the finite deadline");
    }
    private static Double number(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column); return rs.wasNull() ? null : value;
    }
    private static Long integer(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private static long requiredInteger(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull() || value < 0) throw new IllegalStateException("D098 required metadata counter unavailable: " + column);
        return value;
    }
    private static boolean flag(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        if (rs.wasNull()) throw new IllegalStateException("D098 required metadata flag unavailable: " + column);
        return value;
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static String sha(List<Raw> rows) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JobDefinitionJson.mapper().writeValueAsBytes(rows)));
    }
    private static void save(Map<String, Object> evidence) throws Exception {
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
    private static HikariDataSource pool(String name, String host, int port, String user, String password) {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/qdb?sslmode=disable");
        dataSource.setUsername(user); dataSource.setPassword(password); dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setPoolName(name); dataSource.setMaximumPoolSize(4); dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(5000); dataSource.setValidationTimeout(3000);
        return dataSource;
    }
    private static JdbcTemplate jdbc(HikariDataSource dataSource) {
        var jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(30); return jdbc;
    }
    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing setting: " + key);
        return value;
    }
}
