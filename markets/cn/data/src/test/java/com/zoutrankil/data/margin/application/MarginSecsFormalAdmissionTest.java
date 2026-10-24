package com.zoutrankil.data.margin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.IntervalLockStore;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.domain.stock.margin.MarginSecs;
import com.zoutrankil.data.domain.stock.margin.MarginSecsDataset;
import com.zoutrankil.data.domain.stock.margin.MarginSecsKey;
import com.zoutrankil.data.margin.domain.MarginSecsRows;
import com.zoutrankil.data.margin.domain.MarginSecsState;
import com.zoutrankil.data.margin.port.MarginSecsTarget;
import com.zoutrankil.data.margin.port.MarginSecsWriteSession;
import com.zoutrankil.data.margin.storage.MarginSecsWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.TusharePageService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

class MarginSecsFormalAdmissionTest {
    @TempDir Path temp;
    static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    static final String ID = "static-v2-" + "a".repeat(64);

    @Test
    void formalBackfillDoesNotAdoptLegacyCoverageOrExposeOtherTables() throws Exception {
        var f = new Fixture();
        f.rows.add(row(DAY.minusDays(2), "原范围外"));
        for (var mode : List.of(Mode.INCREMENTAL, Mode.RECONCILE))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.job.plan(mode, DAY, DAY, DAY.plusDays(2)));
        assertEquals(0, f.writers);
        var plan = f.plan(DAY);
        assertEquals(0, plan.checkedRows());
        assertNull(plan.checkpointAnchor());
        assertNull(plan.checkpointBefore());
        assertFalse(plan.bootstrap());
        assertFalse(Files.exists(f.ledger));
        var result = f.job.run(plan);
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertTrue(f.rows.contains(row(DAY.minusDays(2), "原范围外")));
        assertEquals(2, plan.request().definition().version());
        var p = new LinkedHashMap<>(plan.request().parameters());
        p.remove("baselineScope");
        p.put("checkpointAnchor", DAY);
        var forged =
                MarginSecsSyncJobOwner.DEFINITION.freeze(
                        Mode.INCREMENTAL, p, DAY, DAY, DAY.plusDays(2));
        assertThrows(
                IllegalArgumentException.class,
                () -> f.job.runAsGroupChild("forged", "parent", ID, forged));
        assertThrows(
                IllegalArgumentException.class,
                () -> MarginSecsDataset.createIsolatedTableSql("margin_secs"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MarginSecsDataset.isolatedWriteDefinition("margin_secs"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MarginSecsDataset.requireExecutionTable("margin_all"));
        assertEquals(
                "margin_secs",
                MarginSecsDataset.executionWriteDefinition("margin_secs").objectName());
    }

    @Test
    void scopedBaselineRejectsInsideChangesButDoesNotFreezeOutsideHistory() throws Exception {
        var f = new Fixture();
        f.rows.add(row(DAY, "计划时旧值"));
        f.rows.add(row(DAY.minusDays(2), "原范围外"));
        var initial = f.plan(DAY);
        f.rows.set(0, row(DAY, "计划后变化"));
        assertThrows(IllegalStateException.class, () -> f.job.run(initial));
        assertEquals(0, f.sends);
        assertFalse(Files.exists(f.ledger));
        var plan = f.plan(DAY);
        f.rows.set(1, row(DAY.minusDays(2), "范围外独立修订"));
        var result = f.job.run(plan);
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertTrue(f.rows.contains(row(DAY.minusDays(2), "范围外独立修订")));
    }

    @Test
    void wholeDateFailureKeepsRunnerAndIntervalInDoubtAndBlocksReplay() throws Exception {
        var f = new Fixture();
        f.corruptFinal = true;
        var result = f.job.run(f.plan(DAY));
        assertEquals(SyncRunState.IN_DOUBT, result.state());
        var lease =
                new DatasetIntervalLock(f.ledger)
                        .findOwned(
                                result.runId(),
                                new IntervalLockStore.Scope("margin_secs", DAY, DAY));
        assertNotNull(lease);
        assertTrue(lease.inDoubt());
        int sends = f.sends;
        assertThrows(IllegalStateException.class, () -> f.job.resume(result.runId()));
        assertEquals(sends, f.sends);
        assertTrue(
                Files.isRegularFile(
                        temp.resolve("sync-evidence")
                                .resolve(result.runId())
                                .resolve("whole-date-in-doubt-" + DAY + ".json")));
    }

    @Test
    void laterSourceFailureResumesOriginalRequestAndReusesVerifiedDay() throws Exception {
        var f = new Fixture();
        f.failDate = DAY.plusDays(1);
        f.rows.add(row(DAY.minusDays(2), "范围外"));
        var partial = f.job.run(f.plan(DAY.plusDays(1)));
        assertEquals(SyncRunState.PARTIAL, partial.state());
        f.failDate = null;
        var resumed = f.job.resume(partial.runId());
        assertEquals(
                SyncRunState.VERIFIED,
                resumed.state(),
                JobDefinitionJson.mapper()
                        .writeValueAsString(f.job.entries(resumed.runId(), null, 100)));
        assertEquals(1, resumed.reusedRows());
        var ledger = SyncRunLedger.openReadOnly(f.ledger);
        assertEquals(
                ledger.getRun(partial.runId()).frozenJson(),
                ledger.getRun(resumed.runId()).frozenJson());
        assertTrue(f.rows.contains(row(DAY.minusDays(2), "范围外")));
    }

    static MarginSecs row(LocalDate date, String name) {
        return new MarginSecs(new MarginSecsKey(date, "600000.SH"), name, "SSE");
    }

    final class Fixture {
        final Path ledger = temp.resolve("sync.sqlite");
        final List<MarginSecs> rows = new ArrayList<>();
        final MarginSecsJobService job;
        int writers, sends;
        boolean corruptFinal;
        LocalDate failDate;

        Fixture() throws Exception {
            var jobs = mock(SyncJobRegistry.class);
            when(jobs.prepare(anyString(), anyInt(), any(), anyMap(), any(), any(), any()))
                    .thenAnswer(
                            i ->
                                    (i.<Integer>getArgument(1) == 2
                                                    ? MarginSecsSyncJobOwner.FORMAL_DEFINITION
                                                    : MarginSecsSyncJobOwner.DEFINITION)
                                            .freeze(
                                                    i.getArgument(2),
                                                    i.getArgument(3),
                                                    i.getArgument(4),
                                                    i.getArgument(5),
                                                    i.getArgument(6)));
            var target = mock(MarginSecsTarget.class);
            when(target.tableName()).thenReturn("margin_secs");
            when(target.targetId()).thenReturn(ID);
            when(target.snapshot()).thenThrow(new AssertionError("正式v2不能扫全表"));
            when(target.snapshot(any(), any()))
                    .thenAnswer(
                            i -> {
                                var all =
                                        rows.stream()
                                                .filter(
                                                        r ->
                                                                !r.tradeDate()
                                                                                .isBefore(
                                                                                        i
                                                                                                .getArgument(
                                                                                                        0))
                                                                        && !r.tradeDate()
                                                                                .isAfter(
                                                                                        i
                                                                                                .getArgument(
                                                                                                        1)))
                                                .sorted(
                                                        Comparator.comparing(MarginSecs::tradeDate)
                                                                .thenComparing(MarginSecs::tsCode))
                                                .toList();
                                return new MarginSecsState.Snapshot(
                                        all, MarginSecsRows.fingerprint(all));
                            });
            when(target.newWriter(ID))
                    .thenAnswer(
                            i -> {
                                writers++;
                                var writer = mock(MarginSecsWriteSession.class);
                                when(writer.codec()).thenReturn(MarginSecsWritePort.CODEC);
                                when(writer.walSettled()).thenReturn(true);
                                when(writer.recoveryReadbackBatchRows()).thenReturn(250);
                                when(writer.readTargetRange())
                                        .thenAnswer(
                                                j ->
                                                        new MarginSecsState.TargetRange(
                                                                rows.stream()
                                                                        .map(MarginSecs::tradeDate)
                                                                        .min(LocalDate::compareTo)
                                                                        .orElse(null),
                                                                rows.stream()
                                                                        .map(MarginSecs::tradeDate)
                                                                        .max(LocalDate::compareTo)
                                                                        .orElse(null),
                                                                rows.size()));
                                doAnswer(
                                                j -> {
                                                    sends++;
                                                    for (var r :
                                                            j.<List<MarginSecs>>getArgument(0)) {
                                                        rows.removeIf(
                                                                old -> old.key().equals(r.key()));
                                                        rows.add(r);
                                                    }
                                                    return null;
                                                })
                                        .when(writer)
                                        .send(anyList());
                                when(writer.readback(anyList()))
                                        .thenAnswer(
                                                j ->
                                                        rows.stream()
                                                                .filter(
                                                                        r ->
                                                                                j.<List<
                                                                                                        MarginSecsKey>>
                                                                                                getArgument(
                                                                                                        0)
                                                                                        .contains(
                                                                                                r
                                                                                                        .key()))
                                                                .toList());
                                when(writer.readDateBefore(any()))
                                        .thenAnswer(
                                                j ->
                                                        rows.stream()
                                                                .filter(
                                                                        r ->
                                                                                r.tradeDate()
                                                                                        .equals(
                                                                                                j
                                                                                                        .getArgument(
                                                                                                                0)))
                                                                .toList());
                                when(writer.readDate(any()))
                                        .thenAnswer(
                                                j ->
                                                        rows.stream()
                                                                .filter(
                                                                        r ->
                                                                                r.tradeDate()
                                                                                        .equals(
                                                                                                j
                                                                                                        .getArgument(
                                                                                                                0)))
                                                                .map(
                                                                        r ->
                                                                                corruptFinal
                                                                                        ? row(
                                                                                                r
                                                                                                        .tradeDate(),
                                                                                                "并发变化")
                                                                                        : r)
                                                                .toList());
                                return writer;
                            });
            ExchangeCalendarReadPort calendar =
                    query -> {
                        var dates = new ArrayList<ExchangeCalendar>();
                        for (LocalDate d = (LocalDate) query.fromInclusive();
                                d.isBefore((LocalDate) query.toExclusive());
                                d = d.plusDays(1))
                            dates.add(new ExchangeCalendar("SSE", d, true, d.minusDays(1)));
                        return new DatasetReadPage<>(
                                "exchange_calendar", 1, null, Instant.EPOCH, dates, null);
                    };
            var pages =
                    new TusharePageService(null) {
                        public PageExecutor.Fetcher fetcher(
                                PageContract contract, BooleanSupplier stopped) {
                            return params -> {
                                var day =
                                        LocalDate.parse(
                                                (String) params.get("trade_date"),
                                                DateTimeFormatter.BASIC_ISO_DATE);
                                if (day.equals(failDate)) throw new IllegalStateException("夹具来源失败");
                                var json = JobDefinitionJson.mapper();
                                var raw = new LinkedHashMap<String, JsonNode>();
                                raw.put("trade_date", json.valueToTree(params.get("trade_date")));
                                raw.put("ts_code", json.valueToTree("600000.SH"));
                                raw.put("name", json.valueToTree("来源名称"));
                                raw.put("exchange", json.valueToTree("SSE"));
                                return new PageExecutor.Page(List.of(raw), null, false, null);
                            };
                        }
                    };
            job = new MarginSecsJobService(jobs, pages, calendar, target, ledger);
        }

        MarginSecsJobService.Plan plan(LocalDate to) throws Exception {
            return job.plan(Mode.BACKFILL, DAY, to, DAY.plusDays(2));
        }
    }
}
