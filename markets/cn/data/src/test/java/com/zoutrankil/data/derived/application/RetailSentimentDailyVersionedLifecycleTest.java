package com.zoutrankil.data.derived.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyLayout;
import com.zoutrankil.data.derived.port.RetailSentimentDailySession;
import com.zoutrankil.data.derived.port.RetailSentimentDailyTarget;
import com.zoutrankil.data.derived.storage.QuestDbRetailSentimentDailyTarget;
import com.zoutrankil.data.derived.storage.RetailSentimentDailyMaterializationPort;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.derived.RetailSentimentDaily;
import com.zoutrankil.data.domain.derived.RetailSentimentDailySnapshot;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class RetailSentimentDailyVersionedLifecycleTest {
    static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    static final RetailSentimentDailyLayout V2 =
            new RetailSentimentDailyLayout(
                    CnTradeDateLayout.TRADE_DATE_V2,
                    "java_d086_l2_daily_features_native_v2",
                    "java_d098_mv_retail_sentiment_daily_native_v2");
    static final String CALENDAR = "calendar-31:8:8:" + "b".repeat(64);
    @TempDir Path root;

    @Test
    void originalVersionAndTablesSurviveDefaultFlipWithoutEditingFrozenJson() throws Exception {
        var w = new World();
        w.allowed = false;
        var owner = w.owner(V2);
        var plan = owner.plan(DAY, DAY, DAY, Mode.MATERIALIZE);
        var failed = owner.run(plan);
        assertEquals(SyncRunState.FAILED, failed.result().state());
        var ledger = SyncRunLedger.openReadOnly(w.path);
        String original = ledger.getRun(failed.result().runId()).frozenJson();
        var frozen = JobDefinitionJson.mapper().readTree(original);
        assertEquals(V2.sourceTable(), frozen.path("parameters").path("source_table").asText());
        assertEquals(V2.outputTable(), frozen.path("parameters").path("output_table").asText());
        w.allowed = true;
        var flipped = w.owner(RetailSentimentDailyLayout.LEGACY);
        var resumed = flipped.resume(failed.result().runId());
        assertEquals(SyncRunState.VERIFIED, resumed.result().state());
        assertEquals(1, w.sent2);
        assertEquals(0, w.sent1);
        assertEquals(original, ledger.getRun(failed.result().runId()).frozenJson());
        assertEquals(original, ledger.getRun(resumed.result().runId()).frozenJson());
        assertEquals(2, ledger.getRun(resumed.result().runId()).jobVersion());
        assertEquals(
                RetailSentimentDailyMaterializationPort.definitionSha(V2),
                flipped.status(resumed.result().runId()).currentTarget().definitionSha());
        assertThrows(IllegalArgumentException.class, () -> flipped.run(plan));
    }

    @Test
    void changedSourceCannotBeReplannedUnderAnOldRun() throws Exception {
        var w = new World();
        w.allowed = false;
        var owner = w.owner(V2);
        var failed = owner.run(owner.plan(DAY, DAY, DAY, Mode.MATERIALIZE));
        String old =
                SyncRunLedger.openReadOnly(w.path).getRun(failed.result().runId()).frozenJson();
        w.allowed = true;
        w.snapshot2.set(snapshot(V2, 8, 11));
        var resumed = w.owner(RetailSentimentDailyLayout.LEGACY).resume(failed.result().runId());
        assertEquals(SyncRunState.FAILED, resumed.result().state());
        assertEquals(0, w.sent2);
        assertEquals(
                old,
                SyncRunLedger.openReadOnly(w.path).getRun(failed.result().runId()).frozenJson());
    }

    @Test
    void lostResponseRetainsTheOriginalLeaseAndReconciliationNeverResends() throws Exception {
        var w = new World();
        w.loseAck = true;
        var owner = w.owner(V2);
        var result = owner.run(owner.plan(DAY, DAY, DAY, Mode.MATERIALIZE));
        assertEquals(SyncRunState.IN_DOUBT, result.result().state());
        var scope = DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily");
        var locks = new DatasetIntervalLock(w.path);
        var lease = locks.findOwned(result.result().runId(), scope);
        assertNotNull(lease);
        assertTrue(lease.inDoubt());
        var flipped = w.owner(RetailSentimentDailyLayout.LEGACY);
        assertThrows(IllegalStateException.class, () -> flipped.resume(result.result().runId()));
        assertThrows(
                IllegalArgumentException.class,
                () -> flipped.reconcile(result.result().runId(), false));
        assertEquals(lease, locks.findOwned(result.result().runId(), scope));
        String old =
                SyncRunLedger.openReadOnly(w.path).getRun(result.result().runId()).frozenJson();
        var checked = flipped.reconcile(result.result().runId(), true);
        assertEquals(SyncRunState.VERIFIED, checked.state());
        assertEquals(1, w.sent2);
        assertNull(locks.findOwned(result.result().runId(), scope));
        assertEquals(
                old,
                SyncRunLedger.openReadOnly(w.path).getRun(result.result().runId()).frozenJson());
    }

    @Test
    void newBaseVersionUsesIndependentCurrentPrefixProofInsteadOfRelabelingOldInput()
            throws Exception {
        var w = new World();
        var owner = w.owner(V2);
        var first = owner.run(owner.plan(DAY, DAY, DAY, Mode.INCREMENTAL));
        assertEquals(SyncRunState.VERIFIED, first.result().state());
        var ledger = SyncRunLedger.openReadOnly(w.path);
        String old = ledger.getRun(first.result().runId()).frozenJson();
        String payload = ledger.get(first.result().runId()).payloadJson();
        w.rows2.set(List.of(row(DAY, 0.75), row(DAY.plusDays(1), 0.8)));
        w.snapshot2.set(snapshot(V2, 8, 11));
        var next = owner.plan(DAY, DAY.plusDays(1), DAY.plusDays(1), Mode.INCREMENTAL);
        assertEquals(DAY, next.request().parameters().get("checkpoint_before"));
        assertEquals(1, w.sent2);
        assertEquals(old, ledger.getRun(first.result().runId()).frozenJson());
        assertEquals(payload, ledger.get(first.result().runId()).payloadJson());
        try (var proofs = Files.list(root.resolve("sync-evidence"))) {
            var proof =
                    proofs.filter(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .startsWith("retail-prefix-"))
                            .findFirst()
                            .orElseThrow();
            var json = JobDefinitionJson.mapper().readTree(Files.readString(proof));
            assertEquals(first.result().runId(), json.path("originalRunId").asText());
            assertTrue(json.path("passed").asBoolean());
            assertEquals(
                    w.snapshot2.get().sourceVersion(),
                    json.path("currentRequest").path("parameters").path("source_version").asText());
            assertFalse(json.path("verifiedAt").asText().isBlank());
            assertNotEquals(
                    JobDefinitionJson.mapper()
                            .readTree(old)
                            .path("parameters")
                            .path("source_version")
                            .asText(),
                    json.path("currentRequest").path("parameters").path("source_version").asText());
        }
    }

    @Test
    void scopesCannotUpgradeLegacySessionsOrDiscardDeploymentAdmission() {
        var jdbc = mock(JdbcTemplate.class);
        var target =
                new QuestDbRetailSentimentDailyTarget(
                        jdbc, new QuestDbProperties(), false, null, V2);
        var original =
                RetailSentimentDailyTarget.forFrozenLayout(
                        target, RetailSentimentDailyLayout.LEGACY);
        var session = original.newSession();
        assertEquals(
                RetailSentimentDailyLayout.LEGACY, RetailSentimentDailySession.layoutOf(session));
        session.bind(DAY, DAY, snapshot(RetailSentimentDailyLayout.LEGACY, 7, 10));
        assertThrows(IllegalStateException.class, session::preflight);
        verifyNoInteractions(jdbc);
        var old = (RetailSentimentDailyTarget) () -> mock(RetailSentimentDailySession.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> RetailSentimentDailyTarget.forFrozenLayout(old, V2));
        var denied =
                new QuestDbRetailSentimentDailyTarget(
                        jdbc, new QuestDbProperties(), true, "bad", V2);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RetailSentimentDailyTarget.forFrozenLayout(
                                        denied, RetailSentimentDailyLayout.LEGACY)
                                .newSession());
    }

    static RetailSentimentDaily row(LocalDate day, double ratio) {
        return new RetailSentimentDaily(
                day, ratio, 1.2, 10.0, -2.0, 0.6, 2L, 1L, 0.2, 1L, 3L, 51.0, -1.0);
    }

    static RetailSentimentDailySnapshot snapshot(
            RetailSentimentDailyLayout layout, long source, long output) {
        return new RetailSentimentDailySnapshot(
                20 + layout.version(),
                layout.sourceTable() + "~21",
                source,
                source,
                source,
                true,
                30 + layout.version(),
                layout.outputTable() + "~31",
                output,
                output,
                output,
                true,
                true,
                true,
                RetailSentimentDailyMaterializationPort.definitionSha(layout),
                "start-" + output,
                "finish-" + output,
                source,
                source,
                "DAY",
                "valid");
    }

    // 外部 MV 已追上时 INCREMENTAL 可为无写值变化的刷新；仍跑真实 runner、账本和逐字段核对。
    final class World {
        final Path path = root.resolve(UUID.randomUUID() + ".sqlite3");
        boolean allowed = true, loseAck;
        int sent1, sent2;
        final AtomicReference<RetailSentimentDailySnapshot> snapshot1 =
                new AtomicReference<>(snapshot(RetailSentimentDailyLayout.LEGACY, 7, 10));
        final AtomicReference<RetailSentimentDailySnapshot> snapshot2 =
                new AtomicReference<>(snapshot(V2, 7, 10));
        final AtomicReference<List<RetailSentimentDaily>>
                rows1 = new AtomicReference<>(List.of(row(DAY, 0.5))),
                rows2 = new AtomicReference<>(List.of(row(DAY, 0.5)));

        RetailSentimentDailyJobService owner(RetailSentimentDailyLayout layout) {
            return new RetailSentimentDailyJobService(new Target(layout), path);
        }

        final class Target implements RetailSentimentDailyTarget.Versioned {
            final RetailSentimentDailyLayout layout;

            Target(RetailSentimentDailyLayout layout) {
                this.layout = layout;
            }

            public RetailSentimentDailyLayout layout() {
                return layout;
            }

            public RetailSentimentDailyTarget forLayout(RetailSentimentDailyLayout original) {
                if (!original.equals(V2) && !original.equals(RetailSentimentDailyLayout.LEGACY))
                    throw new IllegalArgumentException("未知测试物理对象");
                return new Target(original);
            }

            public RetailSentimentDailySession newSession() {
                var session = mock(RetailSentimentDailySession.Versioned.class);
                var state = layout.version() == 2 ? snapshot2 : snapshot1;
                var values = layout.version() == 2 ? rows2 : rows1;
                String identity = "questdb-" + String.valueOf(layout.version()).repeat(64);
                when(session.layout()).thenReturn(layout);
                when(session.snapshot()).thenAnswer(call -> state.get());
                when(session.targetId()).thenReturn(identity);
                when(session.calendarVersion()).thenReturn(CALENDAR);
                when(session.newCalendarReader())
                        .thenReturn(
                                query ->
                                        MaterializationCalendarFixture.page(
                                                query, List.of(DAY, DAY.plusDays(1))));
                when(session.expected(any(), any()))
                        .thenAnswer(
                                call -> {
                                    LocalDate from = call.getArgument(0), to = call.getArgument(1);
                                    return values.get().stream()
                                            .filter(
                                                    r ->
                                                            !r.tradeDate().isBefore(from)
                                                                    && !r.tradeDate().isAfter(to))
                                            .toList();
                                });
                when(session.sourceRawRows(any(), any()))
                        .thenAnswer(
                                call -> {
                                    LocalDate from = call.getArgument(0), to = call.getArgument(1);
                                    return 2L
                                            * values.get().stream()
                                                    .filter(
                                                            r ->
                                                                    !r.tradeDate().isBefore(from)
                                                                            && !r.tradeDate()
                                                                                    .isAfter(to))
                                                    .count();
                                });
                when(session.readback(anyList()))
                        .thenAnswer(
                                call -> {
                                    List<LocalDate> keys = call.getArgument(0);
                                    return values.get().stream()
                                            .filter(r -> keys.contains(r.tradeDate()))
                                            .toList();
                                });
                when(session.walSettled()).thenReturn(true);
                when(session.verifiedSnapshot()).thenAnswer(call -> state.get());
                when(session.uncertainSenderStopped()).thenReturn(false);
                when(session.unresolved()).thenAnswer(call -> loseAck);
                when(session.visibilityTimeout()).thenReturn(Duration.ofMillis(10));
                doAnswer(
                                call -> {
                                    if (!allowed) throw new IllegalStateException("未准入");
                                    return null;
                                })
                        .when(session)
                        .preflight();
                doAnswer(
                                call -> {
                                    if (layout.version() == 2) sent2++;
                                    else sent1++;
                                    if (loseAck) throw new IllegalStateException("响应丢失");
                                    return null;
                                })
                        .when(session)
                        .send(anyList());
                return session;
            }
        }
    }
}
