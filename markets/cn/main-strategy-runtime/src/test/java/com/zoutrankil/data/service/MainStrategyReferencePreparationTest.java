package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarSyncAdapter;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.etf.application.EtfBasicJobService;
import com.zoutrankil.data.etf.application.EtfBasicSyncJobOwner;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

class MainStrategyReferencePreparationTest {
    @TempDir Path root;
    static final LocalDate DAY = LocalDate.parse("2026-10-22");
    static final String TARGET = "static-v2-" + "a".repeat(64);
    final ExchangeCalendarJobService calendar = mock(ExchangeCalendarJobService.class);
    final StockDetailInfoJobService stock = mock(StockDetailInfoJobService.class);
    final EtfBasicJobService etf = mock(EtfBasicJobService.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final AtomicInteger planned = new AtomicInteger(), submitted = new AtomicInteger();

    Map<String, Object> metadata(long txn) {
        return Map.of(
                "id",
                1,
                "directoryName",
                "stock_detail_info~1",
                "table_txn",
                txn,
                "walEnabled",
                true,
                "table_suspended",
                false,
                "writerTxn",
                txn,
                "sequencerTxn",
                txn,
                "suspended",
                false,
                "bufferedTxnSize",
                0L);
    }

    MainStrategyReferencePreparation service() throws Exception {
        var input = new JdbcTemplate(mock(DataSource.class));
        var service =
                new MainStrategyReferencePreparation(
                        calendar,
                        stock,
                        etf,
                        input,
                        root.resolve("sync.sqlite").toString(),
                        root.toString());
        var field = MainStrategyReferencePreparation.class.getDeclaredField("jdbc");
        field.setAccessible(true);
        field.set(service, jdbc);
        when(jdbc.queryForList(anyString(), eq("stock_detail_info")))
                .thenReturn(List.of(metadata(7)));
        return service;
    }

    MainStrategyReferencePreparation.Planner planner(boolean fail) {
        return () -> {
            planned.incrementAndGet();
            var request =
                    StockDetailInfoJobService.definition()
                            .freeze(null, Map.of("discover", true), DAY, DAY, DAY);
            return new MainStrategyReferencePreparation.Planned(
                    request,
                    TARGET,
                    id -> {
                        submitted.incrementAndGet();
                        var ledger = new SyncRunLedger(root.resolve("sync.sqlite"));
                        ledger.createRun(id, null, TARGET, request);
                        ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
                        if (fail) {
                            ledger.transition(id, 1, SyncRunState.IN_DOUBT, "{}");
                            throw new IllegalStateException("夹具未决");
                        }
                        String payload =
                                JobDefinitionJson.mapper()
                                        .writeValueAsString(
                                                Map.of(
                                                        "verification",
                                                        Map.of(
                                                                "passed",
                                                                true,
                                                                "expectedRows",
                                                                1,
                                                                "actualRows",
                                                                1,
                                                                "matchedRows",
                                                                1,
                                                                "mismatchedRows",
                                                                0,
                                                                "duplicateKeys",
                                                                0,
                                                                "missingKeys",
                                                                0,
                                                                "sourceFingerprint",
                                                                "fixture-source",
                                                                "readbackEvidence",
                                                                "fixture")));
                        ledger.transition(id, 1, SyncRunState.VERIFIED, payload);
                    });
        };
    }

    private void completed(String id, FrozenRequest request) throws Exception {
        var ledger = new SyncRunLedger(root.resolve("sync.sqlite"));
        ledger.createRun(id, null, TARGET, request);
        ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
        String payload =
                JobDefinitionJson.mapper()
                        .writeValueAsString(
                                Map.of(
                                        "verification",
                                        Map.of(
                                                "passed",
                                                true,
                                                "expectedRows",
                                                1,
                                                "actualRows",
                                                1,
                                                "matchedRows",
                                                1,
                                                "mismatchedRows",
                                                0,
                                                "duplicateKeys",
                                                0,
                                                "missingKeys",
                                                0,
                                                "sourceFingerprint",
                                                "fixture",
                                                "readbackEvidence",
                                                "fixture")));
        ledger.transition(id, 1, SyncRunState.VERIFIED, payload);
    }

    @Test
    void allThreeOwnersRunBeforeTargetSelectionAndSameDayDoesNotResubmit() throws Exception {
        var service = service();
        when(calendar.tableName()).thenReturn("exchange_calendar");
        when(stock.tableName()).thenReturn("stock_detail_info");
        when(etf.tableName()).thenReturn("etf_basic");
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of(metadata(7)));
        var calendarRequest =
                ExchangeCalendarSyncAdapter.definition(true)
                        .freeze(
                                Mode.BACKFILL,
                                Map.of("exchanges", List.of("SSE", "SZSE")),
                                DAY.minusDays(31),
                                DAY.plusDays(31),
                                DAY);
        var calendarPlan =
                new ExchangeCalendarJobService.Plan(calendarRequest, TARGET, Map.of(), 0);
        when(calendar.plan(anyList(), any(), any(), eq(DAY), any())).thenReturn(calendarPlan);
        doAnswer(
                        call -> {
                            completed(call.getArgument(0), calendarRequest);
                            return null;
                        })
                .when(calendar)
                .executeRecorded(anyString(), eq(calendarPlan));
        var stockRequest =
                StockDetailInfoJobService.definition()
                        .freeze(null, Map.of("discover", true), DAY, DAY, DAY);
        when(stock.plan(List.of(), true, DAY)).thenReturn(stockRequest);
        when(stock.targetId()).thenReturn(TARGET);
        doAnswer(
                        call -> {
                            completed(call.getArgument(0), stockRequest);
                            return null;
                        })
                .when(stock)
                .execute(anyString(), isNull(), eq(stockRequest));
        var observed = Instant.parse("2026-10-22T01:00:00Z");
        var etfRequest =
                EtfBasicSyncJobOwner.DEFINITION.freeze(
                        Mode.SNAPSHOT,
                        Map.of("targetId", TARGET, "observedAt", observed.toString()),
                        null,
                        null,
                        DAY);
        var etfPlan = new EtfBasicJobService.Plan(etfRequest, TARGET, observed);
        when(etf.plan(DAY)).thenReturn(etfPlan);
        doAnswer(
                        call -> {
                            completed(call.getArgument(0), etfRequest);
                            return null;
                        })
                .when(etf)
                .executeRecorded(anyString(), eq(etfPlan));
        var clock = Clock.fixed(observed, ZoneOffset.UTC);
        service.prepare(clock);
        service.prepare(clock);
        var order = inOrder(calendar, stock, etf);
        order.verify(calendar).executeRecorded(anyString(), eq(calendarPlan));
        order.verify(calendar).revalidate(anyString());
        order.verify(stock).execute(anyString(), isNull(), eq(stockRequest));
        order.verify(stock).revalidateGroupChild(anyString(), eq(TARGET), eq(stockRequest));
        order.verify(etf).executeRecorded(anyString(), eq(etfPlan));
        order.verify(etf).revalidate(anyString());
        verify(calendar, times(1)).executeRecorded(anyString(), any());
        verify(stock, times(1)).execute(anyString(), isNull(), any());
        verify(etf, times(1)).executeRecorded(anyString(), any());
    }

    @Test
    void completedSameObservationIsReusedWithoutAnotherSubmission() throws Exception {
        var service = service();
        service.one(DAY, "stock_detail_info", planner(false));
        service.one(
                DAY,
                "stock_detail_info",
                () -> {
                    throw new AssertionError("不能重新计划");
                });
        assertEquals(1, submitted.get());
        assertEquals(1, planned.get());
        verify(stock, times(1)).revalidateGroupChild(anyString(), eq(TARGET), any());
    }

    @Test
    void uncertainIntentBlocksFreshPlanningAndExplicitCertification() throws Exception {
        var service = service();
        assertThrows(Exception.class, () -> service.one(DAY, "stock_detail_info", planner(true)));
        assertThrows(Exception.class, () -> service.one(DAY, "stock_detail_info", planner(false)));
        assertThrows(Exception.class, () -> service.reconcile(DAY, "stock_detail_info", true));
        assertEquals(1, submitted.get());
        assertEquals(1, planned.get());
        verifyNoInteractions(stock);
    }

    @Test
    void changedPhysicalVersionCannotReuseOrOverwriteOriginalCertificate() throws Exception {
        var service = service();
        service.one(DAY, "stock_detail_info", planner(false));
        Path receipt =
                root.resolve("reference-preparation-v1/2026-10-22/stock_detail_info-verified.json");
        byte[] bytes = Files.readAllBytes(receipt);
        when(jdbc.queryForList(anyString(), eq("stock_detail_info")))
                .thenReturn(List.of(metadata(8)));
        assertThrows(Exception.class, () -> service.one(DAY, "stock_detail_info", planner(false)));
        assertThrows(Exception.class, () -> service.reconcile(DAY, "stock_detail_info", true));
        assertArrayEquals(bytes, Files.readAllBytes(receipt));
        assertEquals(1, submitted.get());
    }

    @Test
    void missingCompletionNeedsExplicitStoppedWriterAndReadback() throws Exception {
        var service = service();
        service.one(DAY, "stock_detail_info", planner(false));
        Path receipt =
                root.resolve("reference-preparation-v1/2026-10-22/stock_detail_info-verified.json");
        Files.delete(receipt);
        assertThrows(Exception.class, () -> service.one(DAY, "stock_detail_info", planner(false)));
        assertThrows(Exception.class, () -> service.reconcile(DAY, "stock_detail_info", false));
        assertEquals("VERIFIED", service.reconcile(DAY, "stock_detail_info", true).get("state"));
        assertTrue(Files.isRegularFile(receipt));
        assertEquals(1, submitted.get());
    }

    @Test
    void unstableWalAfterWriteKeepsOriginalIntentAndPreventsReplay() throws Exception {
        var service = service();
        var bad = new LinkedHashMap<>(metadata(7));
        bad.put("sequencerTxn", 8);
        when(jdbc.queryForList(anyString(), eq("stock_detail_info"))).thenReturn(List.of(bad));
        assertThrows(Exception.class, () -> service.one(DAY, "stock_detail_info", planner(false)));
        assertThrows(Exception.class, () -> service.one(DAY, "stock_detail_info", planner(false)));
        assertEquals(1, submitted.get());
        verifyNoInteractions(stock);
    }

    @Test
    void formalBindingIsRequiredBeforeAnySourceOrDatabaseAccess() throws Exception {
        var service = service();
        when(calendar.tableName()).thenReturn("exchange_calendar");
        when(stock.tableName()).thenReturn("stock_detail_info");
        when(etf.tableName()).thenReturn("java_d013_etf_basic_acceptance");
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.prepare(
                                Clock.fixed(
                                        Instant.parse("2026-10-22T01:00:00Z"), ZoneOffset.UTC)));
        verifyNoInteractions(jdbc);
        assertFalse(Files.exists(root.resolve("reference-preparation-v1")));
    }
}
