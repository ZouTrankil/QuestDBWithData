package com.zoutrankil.data.maintenance.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.data.maintenance.storage.BacktestRefreshJournal;
import com.zoutrankil.data.repository.BacktestDailyMaterializationReadGuard.NotReadyException;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort.State;
import com.zoutrankil.data.repository.BacktestDailyWindowVerifier;
import com.zoutrankil.data.repository.BacktestDailyWindowVerifier.RowDigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

class BacktestMvMaintenanceTest {
    private static final BacktestDailyScope SCOPE =
            new BacktestDailyScope(
                    "java_d011_stk_suspend_maint",
                    2,
                    "java_d012_stk_st_daily_maint",
                    1,
                    "java_backtest_v2_maint");
    private static final RowDigest EXPECTED = new RowDigest(4, "b".repeat(64));
    private static final RowDigest OLD = new RowDigest(4, "a".repeat(64));
    @TempDir Path root;
    private RowDigest actual = OLD;
    private long inputTxn = 10, outputTxn = 10, finished = 100;
    private String directory = "native~9";
    private boolean lostAck,
            sourceChangesAfterFull,
            changeDuringPreflight,
            preflightFails,
            sourceLate;
    private int calls, writes;

    private Path ledger() {
        return root.resolve("ledger.sqlite");
    }

    private State state() {
        var tables = new HashMap<String, Map<String, Object>>();
        long id = 1;
        for (String source : SCOPE.sources()) {
            var row = new HashMap<String, Object>();
            row.put("table_name", source);
            row.put("id", id++);
            row.put("directoryName", source + "~1");
            row.put("table_type", "T");
            row.put("walEnabled", true);
            row.put("table_suspended", false);
            row.put("dedup", true);
            row.put("writerTxn", inputTxn);
            row.put("sequencerTxn", inputTxn);
            row.put("bufferedTxnSize", 0L);
            row.put("suspended", false);
            tables.put(source, row);
        }
        tables.put(
                SCOPE.outputTable(),
                Map.of(
                        "id",
                        9L,
                        "directoryName",
                        directory,
                        "sequencerTxn",
                        outputTxn,
                        "table_type",
                        "M"));
        return new State(Map.of("finished", finished), tables);
    }

    private void fixture(Consumer<BacktestMvMaintenance> test) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString()))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "lo",
                                        LocalDate.of(2026, 10, 14).toEpochDay() * 86_400_000_000L,
                                        "hi",
                                        LocalDate.of(2026, 10, 15).toEpochDay()
                                                * 86_400_000_000L)));
        doAnswer(
                        call -> {
                            writes++;
                            var pending =
                                    new BacktestRefreshJournal(ledger())
                                            .pending(SCOPE.outputTable())
                                            .orElseThrow();
                            assertEquals(call.getArgument(0), pending.request().refreshSql());
                            assertEquals(EXPECTED, pending.request().expected());
                            actual = EXPECTED;
                            finished = 200;
                            outputTxn = 11;
                            sourceLate = false;
                            if (sourceChangesAfterFull) inputTxn++;
                            if (lostAck) throw new IllegalStateException("真实提交后模拟确认丢失");
                            return null;
                        })
                .when(jdbc)
                .execute(anyString());
        try (var ports =
                        mockConstruction(
                                BacktestDailyMaterializedViewPort.class,
                                (port, context) -> {
                                    assertEquals(SCOPE, context.arguments().get(1));
                                    when(port.refreshAdmissionState())
                                            .thenAnswer(
                                                    call -> {
                                                        calls++;
                                                        if (changeDuringPreflight && calls == 2)
                                                            inputTxn++;
                                                        return state();
                                                    });
                                    when(port.sourceState())
                                            .thenAnswer(
                                                    call -> {
                                                        if (sourceLate)
                                                            throw new NotReadyException(
                                                                    NotReadyException.Reason
                                                                            .SOURCES_CHANGED,
                                                                    "关联源晚于原刷新");
                                                        return state();
                                                    });
                                });
                var checkers =
                        mockConstruction(
                                BacktestDailyWindowVerifier.class,
                                (checker, context) -> {
                                    when(checker.expectedDigest(any(), any(), any()))
                                            .thenAnswer(
                                                    call -> {
                                                        if (preflightFails)
                                                            throw new IllegalStateException(
                                                                    "原始期望超过预算或类型不符");
                                                        return EXPECTED;
                                                    });
                                    when(checker.outputDigest(any(), any(), any()))
                                            .thenAnswer(call -> actual);
                                    when(checker.verifyRange(any(), any(), any()))
                                            .thenAnswer(call -> state());
                                })) {
            test.accept(new BacktestMvMaintenance(jdbc, ledger(), Duration.ofMillis(20), SCOPE));
        }
    }

    @Test
    void planningAndCurrentResultDoNotCreateSqliteOrSubmitRefresh() {
        fixture(
                service -> {
                    assertEquals("FULL_REQUIRED", service.plan().get("status"));
                    assertFalse(Files.exists(ledger()));
                    assertEquals(0, writes);
                    actual = EXPECTED;
                    assertEquals("CURRENT", service.plan().get("status"));
                    try {
                        assertEquals("CURRENT", service.refresh(true).get("status"));
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    assertFalse(Files.exists(ledger()));
                    assertEquals(0, writes);
                });
    }

    @Test
    void intentIsDurableBeforeFullAndOnlyTheResultChangesOnCompletion() {
        fixture(
                service -> {
                    var plan = service.plan();
                    try {
                        var result = service.refresh(true);
                        var op =
                                new BacktestRefreshJournal(ledger())
                                        .get(result.get("runId").toString());
                        assertEquals("VERIFIED", op.state());
                        assertEquals(1, writes);
                        assertEquals(plan.get("request"), op.request());
                        String original = op.requestJson(), receipt = op.resultJson();
                        var replay = service.reconcile(op.id(), true);
                        assertEquals(result, replay);
                        assertEquals(
                                receipt,
                                new BacktestRefreshJournal(ledger()).get(op.id()).resultJson());
                        assertEquals(
                                original,
                                new BacktestRefreshJournal(ledger()).get(op.id()).requestJson());
                        assertEquals(1, writes);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                });
    }

    @Test
    void lostAcknowledgementBlocksReplayAndReconcilesTheOriginalScopeReadOnly() {
        fixture(
                service -> {
                    lostAck = true;
                    assertThrows(Exception.class, () -> service.refresh(true));
                    var journal = new BacktestRefreshJournal(ledger());
                    var op = journal.pending(SCOPE.outputTable()).orElseThrow();
                    assertThrows(IllegalStateException.class, () -> service.refresh(true));
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> service.reconcile(op.id(), false));
                    assertEquals(1, writes);
                    try {
                        // 读恢复不访问当前配置的表；给它同一只读查询端口的范围回答。
                        var jdbc = mock(JdbcTemplate.class);
                        when(jdbc.queryForList(anyString()))
                                .thenReturn(
                                        List.of(
                                                Map.of(
                                                        "lo",
                                                        LocalDate.of(2026, 10, 14).toEpochDay()
                                                                * 86_400_000_000L,
                                                        "hi",
                                                        LocalDate.of(2026, 10, 15).toEpochDay()
                                                                * 86_400_000_000L)));
                        var differentCurrent =
                                new BacktestMvMaintenance(
                                        jdbc,
                                        ledger(),
                                        Duration.ofMillis(20),
                                        BacktestDailyScope.LEGACY);
                        assertEquals(
                                "VERIFIED",
                                differentCurrent.reconcile(op.id(), true).get("status"));
                        verify(jdbc, never()).execute(anyString());
                        assertEquals(op.requestJson(), journal.get(op.id()).requestJson());
                        assertEquals(op.requestSha256(), journal.get(op.id()).requestSha256());
                        assertEquals(1, writes);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                });
    }

    @Test
    void changedSourcesOrOutputGenerationKeepTheOriginalPendingOperation() {
        fixture(
                service -> {
                    lostAck = true;
                    assertThrows(Exception.class, () -> service.refresh(true));
                    var journal = new BacktestRefreshJournal(ledger());
                    var op = journal.pending(SCOPE.outputTable()).orElseThrow();
                    inputTxn++;
                    assertThrows(
                            IllegalStateException.class, () -> service.reconcile(op.id(), true));
                    inputTxn--;
                    directory = "replacement~99";
                    assertThrows(
                            IllegalStateException.class, () -> service.reconcile(op.id(), true));
                    assertEquals("IN_DOUBT", journal.get(op.id()).state());
                    assertEquals(op.requestJson(), journal.get(op.id()).requestJson());
                    assertEquals(1, writes);
                });
    }

    @Test
    void preflightFailureOrInputChangesStopBeforeIntentAndFull() {
        fixture(
                service -> {
                    preflightFails = true;
                    assertThrows(IllegalStateException.class, () -> service.refresh(true));
                    assertFalse(Files.exists(ledger()));
                    assertEquals(0, writes);
                    preflightFails = false;
                    changeDuringPreflight = true;
                    calls = 0;
                    assertThrows(IllegalStateException.class, () -> service.refresh(true));
                    assertFalse(Files.exists(ledger()));
                    assertEquals(0, writes);
                });
    }

    @Test
    void actualMatchingRowsDoNotBypassLateSourceAdmission() {
        fixture(
                service -> {
                    actual = EXPECTED;
                    sourceLate = true;
                    assertEquals("FULL_REQUIRED", service.plan().get("status"));
                    try {
                        assertEquals("VERIFIED", service.refresh(true).get("status"));
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    assertEquals(1, writes);
                });
    }

    @Test
    void sourceChangeAfterSubmissionAndPayloadTamperingNeverUnlockThePendingOperation() {
        fixture(
                service -> {
                    sourceChangesAfterFull = true;
                    assertThrows(IllegalStateException.class, () -> service.refresh(true));
                    var journal = new BacktestRefreshJournal(ledger());
                    var op = journal.pending(SCOPE.outputTable()).orElseThrow();
                    assertEquals("IN_DOUBT", op.state());
                    try (var db = DriverManager.getConnection("jdbc:sqlite:" + ledger());
                            var s = db.createStatement()) {
                        s.executeUpdate("UPDATE native_backtest_refresh_ops SET request_json='{}'");
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    assertThrows(IllegalStateException.class, () -> journal.get(op.id()));
                    assertEquals(1, writes);
                });
    }

    @Test
    void missingStopAssertionAndAnActiveExecutorNeverTouchTheDatabase() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var service = new BacktestMvMaintenance(jdbc, ledger(), Duration.ofSeconds(1), SCOPE);
        assertThrows(IllegalArgumentException.class, () -> service.refresh(false));
        assertFalse(Files.exists(ledger()));
        verifyNoInteractions(jdbc);
        try (var channel =
                        FileChannel.open(
                                ledger().resolveSibling("ledger.sqlite.backtest-mv.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            assertThrows(IllegalStateException.class, () -> service.refresh(true));
            verifyNoInteractions(jdbc);
        }
    }
}
