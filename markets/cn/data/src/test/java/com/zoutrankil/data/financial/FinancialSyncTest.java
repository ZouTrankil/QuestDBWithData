package com.zoutrankil.data.financial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.zoutrankil.data.config.TushareProperties;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.financial.application.FinancialDailySync;
import com.zoutrankil.data.financial.application.FinancialSyncOwner;
import com.zoutrankil.data.financial.config.FinancialSyncConfiguration;
import com.zoutrankil.data.financial.domain.FinancialContract;
import com.zoutrankil.data.financial.domain.FinancialContract.Key;
import com.zoutrankil.data.financial.domain.FinancialContract.Row;
import com.zoutrankil.data.financial.mapper.FinancialMapper;
import com.zoutrankil.data.financial.port.FinancialTarget;
import com.zoutrankil.data.financial.storage.FinancialWritePort;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.TusharePageService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import javax.sql.DataSource;

class FinancialSyncTest {
    @TempDir Path root;
    static final LocalDate PERIOD = LocalDate.parse("2026-06-30"),
            DAY = LocalDate.parse("2026-10-17");

    static Map<String, JsonNode> row(FinancialContract c, String ann, double n) {
        var m = new LinkedHashMap<String, JsonNode>();
        for (var f : c.fields()) m.put(f.name(), NullNode.instance);
        m.put("ts_code", new TextNode("600000.SH"));
        m.put("ann_date", new TextNode(ann));
        m.put("end_date", new TextNode("20260630"));
        if (Set.of("cashflow", "income").contains(c.dataset()))
            m.put("report_type", new TextNode("1"));
        c.fields().stream()
                .filter(f -> f.type().equals("DOUBLE"))
                .findFirst()
                .ifPresent(f -> m.put(f.name(), new DoubleNode(n)));
        return m;
    }

    static TusharePageService source(Supplier<List<Map<String, JsonNode>>> supplier) {
        var pages = mock(TusharePageService.class);
        when(pages.fetcher(any(), any()))
                .thenReturn(p -> new PageExecutor.Page(supplier.get(), null, false, null));
        return pages;
    }

    @ParameterizedTest
    @ValueSource(strings = {"cashflow", "income", "express", "forecast", "fina_indicator"})
    void newAnnouncementsLateDisclosureAndHistoricalCorrectionsUseTheSameVerifiedOwner(String name)
            throws Exception {
        var c = FinancialSyncConfiguration.contract(name);
        var raw = new ArrayList<>(List.of(row(c, "20260820", 1)));
        var pages = source(() -> List.copyOf(raw));
        var target = new Memory();
        var owner = new FinancialSyncOwner(c, target, pages, root.resolve(name + ".sqlite3"));
        var plan =
                owner.plan(
                        List.of("600000.SH"),
                        List.of(PERIOD),
                        DAY,
                        SyncJobDefinition.Mode.INCREMENTAL);
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(1, target.rows.size());
        raw.add(row(c, "20260825", 2));
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(2, target.rows.size());
        raw.set(0, row(c, "20260820", 3));
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(2, target.rows.size());
        var expected = new FinancialMapper(c).normalize(raw.getFirst(), "600000.SH", PERIOD, DAY);
        assertEquals(expected, target.rows.get(expected.key()));
        assertTrue(Files.isDirectory(root.resolve("sync-evidence")));
    }

    @Test
    void cappedDuplicateOrForeignSourceNeverWritesAndResumeKeepsFrozenDate() throws Exception {
        var c = FinancialSyncConfiguration.contract("forecast");
        var raw = new ArrayList<Map<String, JsonNode>>();
        for (int i = 0; i < 100; i++) raw.add(row(c, "20260820", 1));
        var target = new Memory();
        var owner =
                new FinancialSyncOwner(
                        c, target, source(() -> List.copyOf(raw)), root.resolve("runs.sqlite3"));
        var plan =
                owner.plan(
                        List.of("600000.SH"),
                        List.of(PERIOD),
                        DAY,
                        SyncJobDefinition.Mode.INCREMENTAL);
        var failed = owner.run(plan);
        assertEquals(SyncRunState.FAILED, failed.state());
        assertTrue(target.rows.isEmpty());
        raw.clear();
        raw.add(row(c, "20260820", 1));
        assertEquals(SyncRunState.VERIFIED, owner.resume(failed.runId()).state());
        var ledger = SyncRunLedger.openReadOnly(root.resolve("runs.sqlite3"));
        assertTrue(ledger.getRun(failed.runId()).frozenJson().contains("2026-10-17"));
        var invalid = new LinkedHashMap<>(raw.getFirst());
        invalid.put("ts_code", new TextNode("600001.SH"));
        raw.set(0, invalid);
        assertEquals(SyncRunState.FAILED, owner.run(plan).state());
        assertEquals(1, target.rows.size());
    }

    @Test
    void sharedKeyCannotCollapseReportTypesAndNumericOverflowIsRejected() {
        var c = FinancialSyncConfiguration.contract("income");
        var raw = row(c, "20260820", 1);
        raw.put("report_type", new TextNode("2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FinancialMapper(c).normalize(raw, "600000.SH", PERIOD, DAY));
        raw.put("report_type", new TextNode("1"));
        raw.put("revenue", new TextNode("1e999"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FinancialMapper(c).normalize(raw, "600000.SH", PERIOD, DAY));
    }

    @Test
    void correctedSourceWinsRegardlessOfResponseOrderAndConflictingSamePriorityFails() {
        var c = FinancialSyncConfiguration.contract("income");
        var mapper = new FinancialMapper(c);
        var old = row(c, "20260820", 1);
        old.put("update_flag", new TextNode("0"));
        var revised = row(c, "20260820", 2);
        revised.put("update_flag", new TextNode("1"));
        assertEquals(
                List.of(revised),
                mapper.revisions(List.of(old, revised), "600000.SH", PERIOD, DAY));
        assertEquals(
                List.of(revised),
                mapper.revisions(List.of(revised, old), "600000.SH", PERIOD, DAY));
        var conflict = row(c, "20260820", 3);
        conflict.put("update_flag", new TextNode("1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.revisions(List.of(revised, conflict), "600000.SH", PERIOD, DAY));
    }

    @Test
    void localLegacyColumnsSurviveAuthoritativeSourceCorrections() {
        var c = FinancialSyncConfiguration.contract("fina_indicator");
        var mapper = new FinancialMapper(c);
        var fresh = mapper.normalize(row(c, "20260820", 2), "600000.SH", PERIOD, DAY);
        var old = new TreeMap<>(fresh.values());
        old.put("growth_assets", 99d);
        var port = spy(new FinancialWritePort(c, new JdbcTemplate(mock(DataSource.class)), null));
        doReturn(List.of(new Row(old))).when(port).readback(anyList());
        var preserved = port.preserveLocalColumns(List.of(fresh)).getFirst();
        assertEquals(99d, preserved.values().get("growth_assets"));
        for (var field : c.fields())
            if (field.collected())
                assertEquals(
                        fresh.values().get(field.name()),
                        preserved.values().get(field.name()),
                        field.name());
    }

    @Test
    void revisionCycleIncludesRecentAndEventuallyEveryOldQuarter() {
        var first = LocalDate.parse("2018-03-31");
        var seen = new HashSet<LocalDate>();
        for (int i = 0; i < 200; i++) {
            var periods = FinancialSyncOwner.dailyPeriods(DAY.plusDays(i), first);
            assertTrue(periods.size() <= 5);
            seen.addAll(periods);
        }
        for (LocalDate p = first;
                p.isBefore(LocalDate.parse("2025-09-30"));
                p = p.plusMonths(3).with(TemporalAdjusters.lastDayOfMonth()))
            assertTrue(seen.contains(p), p.toString());
    }

    @Test
    void dailyBudgetRejectsFullMarketBeforeAnySourceOrTargetAccess() throws Exception {
        var pages = mock(TusharePageService.class);
        var target = mock(FinancialTarget.class);
        var owners =
                FinancialContract.DATASETS.stream()
                        .map(
                                name ->
                                        new FinancialSyncOwner(
                                                FinancialSyncConfiguration.contract(name),
                                                target,
                                                pages,
                                                root.resolve("ledger.sqlite3")))
                        .toList();
        var codes =
                IntStream.range(0, 5000)
                        .mapToObj(i -> String.format(Locale.ROOT, "%06d.SH", i))
                        .toList();
        byte[] scope =
                JobDefinitionJson.mapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "schema",
                                        "java.financial.scope.v1",
                                        "validFrom",
                                        DAY.toString(),
                                        "validThrough",
                                        DAY.toString(),
                                        "codes",
                                        codes));
        var file = root.resolve("scope.json");
        Files.write(file, scope);
        var scheduler =
                new FinancialDailySync(
                        owners,
                        file.toString(),
                        FileEvidenceStore.sha256(scope),
                        "2018-03-31",
                        new TushareProperties(),
                        1000,
                        Duration.ofHours(4));
        var error = assertThrows(IllegalStateException.class, () -> scheduler.runAt(DAY));
        assertTrue(error.getMessage().contains("超过预算"));
        verifyNoInteractions(pages, target);
        assertFalse(Files.exists(root.resolve("ledger.sqlite3")));
    }

    @Test
    void exhaustedSharedWindowDoesNotStartARequestOrWrite() throws Exception {
        var pages = mock(TusharePageService.class);
        var target = spy(new Memory());
        var owner =
                new FinancialSyncOwner(
                        FinancialSyncConfiguration.contract("income"),
                        target,
                        pages,
                        root.resolve("deadline.sqlite3"));
        var plan =
                owner.plan(
                        List.of("600000.SH"),
                        List.of(PERIOD),
                        DAY,
                        SyncJobDefinition.Mode.INCREMENTAL);
        var result = owner.runWithin(plan, Duration.ofNanos(1));
        assertFalse(
                Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                        .contains(result.state()));
        verifyNoInteractions(pages);
        verify(target, never()).send(anyList());
    }

    static class Memory implements FinancialTarget {
        final Map<Key, Row> rows = new HashMap<>();

        public String targetId() {
            return "fixture-financial-target";
        }

        public void preflight() {}

        public void send(List<Row> value) {
            value.forEach(row -> rows.put(row.key(), row));
        }

        public List<Row> readback(List<Key> keys) {
            return keys.stream().map(rows::get).filter(Objects::nonNull).toList();
        }

        public boolean walSettled() {
            return true;
        }
    }
}
