package com.zoutrankil.market.cn.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.macro.application.MacroObservationSyncOwner;
import com.zoutrankil.market.cn.macro.mapper.MacroObservationDefinition;
import com.zoutrankil.market.cn.macro.port.MacroObservationTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

class MacroObservationSyncTest {
    @TempDir Path root;
    static final LocalDate DAY = LocalDate.parse("2026-10-20");

    static Map<String, JsonNode> sample(String dataset, String month, double value) {
        var row = new LinkedHashMap<String, JsonNode>();
        var source = SourceContract.load(dataset);
        for (var column : source.columns())
            if (column.type().equals("DOUBLE")) row.put(column.source(), new DoubleNode(value++));
        row.put(dataset.equals("cn_gdp") ? "quarter" : "month", new TextNode(month));
        return row;
    }

    static TusharePageService source(Supplier<List<Map<String, JsonNode>>> rows) {
        var pages = mock(TusharePageService.class);
        when(pages.fetcher(any(), any()))
                .thenReturn(params -> new PageExecutor.Page(rows.get(), null, false, null));
        return pages;
    }

    MacroObservationSyncOwner owner(
            String dataset, Memory target, Supplier<List<Map<String, JsonNode>>> rows) {
        var contract = SourceContract.load(dataset);
        return new MacroObservationSyncOwner(
                contract,
                target.definition,
                target,
                source(rows),
                root.resolve(dataset + ".sqlite3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cn_cpi", "cn_ppi", "cn_pmi", "cn_m", "cn_gdp", "sf_month"})
    void eachOfficialContractKeepsEveryFieldAndReconcilesRevision(String dataset) throws Exception {
        var definition = MacroObservationDefinition.from(SourceContract.load(dataset));
        var target = new Memory(definition);
        String period = dataset.equals("cn_gdp") ? "2026Q3" : "202609";
        LocalDate date = LocalDate.parse(dataset.equals("cn_gdp") ? "2026-09-30" : "2026-09-01");
        var raw = new ArrayList<>(List.of(sample(dataset, period, 1)));
        var owner = owner(dataset, target, () -> List.copyOf(raw));
        var plan = owner.plan(date, date, DAY, Mode.RECONCILE);
        var result = owner.run(plan);
        assertEquals(
                SyncRunState.VERIFIED,
                result.state(),
                () -> {
                    try {
                        return new SyncRunLedger(root.resolve(dataset + ".sqlite3"))
                                .get(result.runId())
                                .payloadJson();
                    } catch (Exception failure) {
                        return failure.toString();
                    }
                });
        assertEquals(1, target.rows.size());
        var row = target.rows.values().iterator().next();
        assertEquals(date + "T00:00:00Z", row.get(definition.designatedTimestamp()));
        for (var column : definition.columns())
            if (column.storageType() == DatasetDefinition.StorageType.DOUBLE)
                assertEquals(
                        raw.getFirst().get(column.sourceName()).doubleValue(),
                        row.get(column.storageName()),
                        column.storageName());
        raw.set(0, sample(dataset, period, 100));
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(1, target.rows.size());
        assertTrue(target.rows.values().iterator().next().values().contains(100d));
    }

    @Test
    void incompletePeriodNeverWritesAndExplicitResumeKeepsFrozenScope() throws Exception {
        var target = new Memory(MacroObservationDefinition.from(SourceContract.load("cn_cpi")));
        var raw = new ArrayList<>(List.of(sample("cn_cpi", "202608", 1)));
        var owner = owner("cn_cpi", target, () -> List.copyOf(raw));
        var plan =
                owner.plan(
                        LocalDate.parse("2026-08-01"),
                        LocalDate.parse("2026-09-01"),
                        DAY,
                        Mode.BACKFILL);
        var failed = owner.run(plan);
        assertFalse(failed.state() == SyncRunState.VERIFIED);
        assertTrue(target.rows.isEmpty());
        raw.add(sample("cn_cpi", "202609", 2));
        var resumed = owner.resume(failed.runId());
        assertEquals(SyncRunState.VERIFIED, resumed.state());
        assertEquals(2, target.rows.size());
    }

    @Test
    void interruptionAfterSendKeepsUnresolvedLockAndNeverResends() throws Exception {
        var target = new Memory(MacroObservationDefinition.from(SourceContract.load("cn_m")));
        target.interruptAfterSend = true;
        var owner = owner("cn_m", target, () -> List.of(sample("cn_m", "202609", 1)));
        var date = LocalDate.parse("2026-09-01");
        var plan = owner.plan(date, date, DAY, Mode.INCREMENTAL);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> owner.run(plan)).get(5, TimeUnit.SECONDS);
            assertEquals(SyncRunState.IN_DOUBT, first.state());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, target.sends);
        var second = owner.run(plan);
        assertEquals("DATASET_INTERVAL_BUSY", second.errorCode());
        assertEquals(1, target.sends);
    }

    @Test
    void recreatedTargetAndInvalidPeriodCannotSilentlyResumeOrChangeDate() throws Exception {
        var target = new Memory(MacroObservationDefinition.from(SourceContract.load("cn_gdp")));
        var owner = owner("cn_gdp", target, List::of);
        var date = LocalDate.parse("2026-09-30");
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan(date.minusDays(1), date, DAY, Mode.BACKFILL));
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan(date.minusYears(4), date, DAY, Mode.BACKFILL));
        var failed = owner.run(owner.plan(date, date, DAY, Mode.INCREMENTAL));
        target.identity = "recreated-target";
        assertThrows(IllegalStateException.class, () -> owner.resume(failed.runId()));
    }

    static class Memory implements MacroObservationTarget {
        final DatasetDefinition definition;
        final Map<String, Map<String, Object>> rows = new HashMap<>();
        String identity = "original-macro-target";
        int sends;
        boolean interruptAfterSend;

        Memory(DatasetDefinition definition) {
            this.definition = definition;
        }

        public String targetId() {
            return identity;
        }

        public void preflight() {}

        public boolean walSettled() {
            return !interruptAfterSend || sends == 0;
        }

        public void send(List<Map<String, Object>> values) {
            sends++;
            var codec = VerifiedMarketDataStore.codec(definition);
            for (var value : values) rows.put(codec.key(value), value);
            if (interruptAfterSend) Thread.currentThread().interrupt();
        }

        public List<Map<String, Object>> readback(List<String> keys) {
            return keys.stream().filter(rows::containsKey).map(rows::get).toList();
        }
    }
}
