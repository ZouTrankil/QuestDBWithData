package com.zoutrankil.market.cn.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.application.ContractTableSyncOwner;
import com.zoutrankil.market.cn.observation.mapper.ContractTableDefinition;
import com.zoutrankil.market.cn.observation.port.ContractTableTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

class ContractTableSyncTest {
    @TempDir Path root;
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    static Stream<String> datasets() {
        return ContractTableDefinition.DATASETS.stream()
                .filter(id -> !id.equals("cyq_chips"))
                .sorted();
    }

    static String code(String dataset) {
        if (dataset.equals("fut_holding")) return "IM2612";
        return dataset.equals("fut_basic")
                ? "CFFEX"
                : Set.of("disclosure_date", "broker_recommend", "sge_daily").contains(dataset)
                        ? "ALL"
                        : "000001.SZ";
    }

    static LocalDate date(String dataset) {
        if (dataset.equals("broker_recommend")) return LocalDate.of(2026, 8, 1);
        return dataset.equals("fut_basic")
                ? TODAY
                : Set.of("fina_mainbz", "disclosure_date").contains(dataset)
                        ? LocalDate.of(2026, 6, 30)
                        : LocalDate.of(2026, 8, 28);
    }

    static Map<String, JsonNode> raw(String dataset, String category, double value) {
        var row = new TreeMap<String, JsonNode>();
        for (var c : SourceContract.load(dataset).businessColumns()) {
            String text =
                    switch (c.source()) {
                        case "month" -> "202608";
                        case "broker" -> "券商甲";
                        case "ts_code" -> dataset.equals("fut_basic") ? "IF2612.CFX" : "000001.SZ";
                        case "exchange" -> "CFFEX";
                        case "symbol" -> dataset.equals("fut_holding") ? "IM2612" : "IF2612";
                        case "fut_code" -> "IF";
                        case "end_date" ->
                                dataset.equals("stk_alert")
                                        ? "20260928"
                                        : dataset.equals("pledge_stat") ? "20260828" : "20260630";
                        case "ann_date", "trade_date", "start_date" -> "20260828";
                        case "float_date" -> "20351029";
                        case "list_date" -> "20260101";
                        case "delist_date", "last_ddate" -> "20261218";
                        case "d_month" -> "202612";
                        case "pre_date", "actual_date", "modify_date" -> "20260829";
                        case "bz_code" -> category;
                        case "bz_item" -> category.equals("P") ? "银行业务" : "国内地区";
                        case "curr_type" -> "CNY";
                        case "update_flag" -> "1";
                        case "div_proc" -> value == 1d ? "预案" : "实施";
                        case "holder_name" -> "股东甲";
                        case "in_de" -> "IN";
                        case "holder_type" -> "P";
                        case "begin_date" -> "20260801";
                        case "close_date" -> "20260827";
                        case "period" -> "20260825-20260828";
                        default -> "说明";
                    };
            row.put(
                    c.source(),
                    c.type().equals("LONG")
                            ? new LongNode(9007199254740993L)
                            : c.type().equals("DOUBLE")
                                    ? new DoubleNode(c.source().equals("multiplier") ? 300d : value)
                                    : new TextNode(text));
        }
        return row;
    }

    class Fixture {
        final String dataset;
        final Memory target;
        final ContractTableSyncOwner owner;
        double value = 1d;
        boolean fail, empty, collide;
        Consumer<Map<String, JsonNode>> mutate = row -> {};
        final List<Map<String, Object>> requests = new ArrayList<>();

        Fixture(String dataset) {
            this.dataset = dataset;
            target = new Memory(dataset);
            var pages = mock(TusharePageService.class);
            when(pages.fetcher(any(), any()))
                    .thenReturn(
                            params -> {
                                requests.add(params);
                                if (fail) throw new IllegalStateException("来源故障");
                                if (empty)
                                    return new PageExecutor.Page(List.of(), null, false, null);
                                String type = params.getOrDefault("type", "P").toString();
                                var row = raw(dataset, type, value);
                                mutate.accept(row);
                                if (collide && dataset.equals("fina_mainbz"))
                                    row.put("bz_item", new TextNode("相同项目"));
                                return new PageExecutor.Page(List.of(row), null, false, null);
                            });
            owner = new ContractTableSyncOwner(dataset, target, pages, ledger());
        }

        Path ledger() {
            return root.resolve(dataset + ".sqlite");
        }

        FrozenRequest plan() {
            return owner.plan(code(dataset), date(dataset), date(dataset), TODAY, Mode.RECONCILE);
        }

        String detail(String id) throws Exception {
            return new SyncRunLedger(ledger()).get(id).payloadJson();
        }
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void sourceScopeEveryFieldAndRevisionAreVerifiedBeforeCompletion(String dataset)
            throws Exception {
        var f = new Fixture(dataset);
        var first = f.owner.run(f.plan());
        assertEquals(SyncRunState.VERIFIED, first.state(), () -> first.toString());
        assertEquals(dataset.equals("fina_mainbz") ? 2 : 1, f.target.rows.size());
        var plan = f.plan();
        f.value = 2d;
        assertEquals(SyncRunState.VERIFIED, f.owner.run(plan).state());
        var expected = new ArrayList<Map<String, Object>>();
        for (String category : dataset.equals("fina_mainbz") ? List.of("P", "D") : List.of("P")) {
            var contract = SourceContract.load(dataset);
            var business =
                    contract.normalize(
                            raw(dataset, category, 2),
                            date(dataset),
                            Set.of("disclosure_date", "broker_recommend", "sge_daily")
                                            .contains(dataset)
                                    ? Set.of()
                                    : Set.of(code(dataset)));
            expected.add(
                    ContractTableDefinition.physical(
                            dataset,
                            business,
                            Instant.parse(plan.parameters().get("observedAt").toString())));
        }
        assertEquals(new HashSet<>(expected), new HashSet<>(f.target.rows.values()));
        if (dataset.equals("fut_basic")) {
            assertEquals("1970-01-01T00:00:00Z", expected.getFirst().get("timestamp"));
            assertEquals("1", f.requests.getFirst().get("fut_type"));
            assertFalse(f.requests.getFirst().containsKey("trade_date"));
        }
        if (dataset.equals("share_float"))
            assertEquals("2035-10-29T00:00:00Z", expected.getFirst().get("float_date"));
        if (dataset.equals("disclosure_date")) {
            assertEquals("20260630", f.requests.getFirst().get("end_date"));
            assertFalse(f.requests.getFirst().containsKey("ann_date"));
        }
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void sourceFailureSendsNothingAndResumeRetainsFrozenClock(String dataset) throws Exception {
        var f = new Fixture(dataset);
        f.fail = true;
        var plan = f.plan();
        var failed = f.owner.run(plan);
        assertNotEquals(SyncRunState.VERIFIED, failed.state());
        assertEquals(0, f.target.sends);
        f.fail = false;
        var restored = f.owner.resume(failed.runId());
        assertEquals(SyncRunState.VERIFIED, restored.state(), () -> restored.toString());
        for (var row : f.target.rows.values())
            if (row.containsKey("update_time"))
                assertEquals(plan.parameters().get("observedAt"), row.get("update_time"));
        f.target.identity = "replacement";
        assertThrows(IllegalStateException.class, () -> f.owner.resume(failed.runId()));
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void disappearingSourceRowsCannotBeCertifiedOrEraseOldRows(String dataset) throws Exception {
        var f = new Fixture(dataset);
        assertEquals(SyncRunState.VERIFIED, f.owner.run(f.plan()).state());
        int sends = f.target.sends;
        var old = Map.copyOf(f.target.rows);
        f.empty = true;
        var result = f.owner.run(f.plan());
        assertNotEquals(SyncRunState.VERIFIED_EMPTY, result.state());
        assertNotEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(sends, f.target.sends);
        assertEquals(old, f.target.rows);
    }

    @Test
    void malformedStatisticalPeriodUnknownDirectionAndNegativeCountsFailBeforeSending()
            throws Exception {
        var holders = new Fixture("stk_holdernumber");
        holders.mutate = r -> r.put("end_date", new TextNode("20261399"));
        assertNotEquals(SyncRunState.VERIFIED, holders.owner.run(holders.plan()).state());
        assertEquals(0, holders.target.sends);
        var trades = new Fixture("stk_holdertrade");
        trades.mutate = r -> r.put("in_de", new TextNode("UNKNOWN"));
        assertNotEquals(SyncRunState.VERIFIED, trades.owner.run(trades.plan()).state());
        assertEquals(0, trades.target.sends);
        var pledge = new Fixture("pledge_stat");
        pledge.mutate = r -> r.put("pledge_count", new LongNode(-1));
        assertNotEquals(SyncRunState.VERIFIED, pledge.owner.run(pledge.plan()).state());
        assertEquals(0, pledge.target.sends);
    }

    @Test
    void classificationCollisionsAndUnknownSendKeepOriginalDataAndLock() throws Exception {
        var main = new Fixture("fina_mainbz");
        main.collide = true;
        assertNotEquals(SyncRunState.VERIFIED, main.owner.run(main.plan()).state());
        assertEquals(0, main.target.sends);
        main.collide = false;
        assertEquals(SyncRunState.VERIFIED, main.owner.run(main.plan()).state());
        var row = new TreeMap<>(main.target.rows.values().iterator().next());
        row.put("bz_code", "X");
        main.target.rows.put(
                VerifiedMarketDataStore.codec(ContractTableDefinition.from("fina_mainbz")).key(row),
                row);
        int sends = main.target.sends;
        assertNotEquals(SyncRunState.VERIFIED, main.owner.run(main.plan()).state());
        assertEquals(sends, main.target.sends);
        var f = new Fixture("dividend");
        f.target.interruptAfterSend = true;
        var plan = f.plan();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> f.owner.run(plan)).get(5, TimeUnit.SECONDS);
            assertEquals(SyncRunState.IN_DOUBT, result.state());
            assertEquals("DATASET_INTERVAL_BUSY", f.owner.run(plan).errorCode());
            assertThrows(IllegalStateException.class, () -> f.owner.resume(result.runId()));
            assertEquals(1, f.target.sends);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void changedDisclosureAnnouncementRetainsOldPlanAsHistoryAndVerifiesCurrentSource()
            throws Exception {
        var f = new Fixture("disclosure_date");
        var old =
                new TreeMap<>(
                        ContractTableDefinition.physical(
                                "disclosure_date",
                                SourceContract.load("disclosure_date")
                                        .normalize(
                                                raw("disclosure_date", "P", 1),
                                                date("disclosure_date"),
                                                Set.of()),
                                Instant.parse("2026-08-01T00:00:00Z")));
        old.put("ann_date", "2026-07-01T00:00:00Z");
        f.target.rows.put(
                VerifiedMarketDataStore.codec(ContractTableDefinition.from("disclosure_date"))
                        .key(old),
                old);
        assertEquals(SyncRunState.VERIFIED, f.owner.run(f.plan()).state());
        assertEquals(2, f.target.rows.size());
        assertTrue(f.target.rows.values().contains(old));
    }

    @Test
    void directoryRetainsHistoricalContractsWithoutCertifyingThemAsCurrent() throws Exception {
        var f = new Fixture("fut_basic");
        var old =
                new TreeMap<>(
                        ContractTableDefinition.physical(
                                "fut_basic",
                                SourceContract.load("fut_basic")
                                        .normalize(
                                                raw("fut_basic", "P", 1), TODAY, Set.of("CFFEX")),
                                Instant.parse("2026-09-01T00:00:00Z")));
        old.put("ts_code", "IF2501.CFX");
        old.put("symbol", "IF2501");
        f.target.rows.put(
                VerifiedMarketDataStore.codec(ContractTableDefinition.from("fut_basic")).key(old),
                old);
        assertEquals(SyncRunState.VERIFIED, f.owner.run(f.plan()).state());
        assertEquals(2, f.target.rows.size());
        assertTrue(f.target.rows.values().contains(old));
    }

    @Test
    void sourceCheckNeverCreatesFormalLedgerAndDirectoryRejectsHistory() throws Exception {
        var f = new Fixture("fut_basic");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        f.owner.plan(
                                "CFFEX",
                                TODAY.minusDays(1),
                                TODAY.minusDays(1),
                                TODAY,
                                Mode.BACKFILL));
        assertThrows(
                IllegalArgumentException.class,
                () -> f.owner.plan("BAD", TODAY, TODAY, TODAY, Mode.RECONCILE));
        assertEquals(false, f.owner.checkSource(f.plan()).get("questdbWritten"));
        assertFalse(Files.exists(f.ledger()));
        assertEquals(0, f.target.sends);
    }

    static class Memory implements ContractTableTarget {
        final String dataset;
        final Map<String, Map<String, Object>> rows = new HashMap<>();
        String identity = "original";
        int sends;
        boolean interruptAfterSend;

        Memory(String dataset) {
            this.dataset = dataset;
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
            var codec = VerifiedMarketDataStore.codec(ContractTableDefinition.from(dataset));
            values.forEach(r -> rows.put(codec.key(r), r));
            if (interruptAfterSend) Thread.currentThread().interrupt();
        }

        public List<Map<String, Object>> readback(List<String> keys) {
            return keys.stream().filter(rows::containsKey).map(rows::get).toList();
        }

        public List<Map<String, Object>> readScope(String code, LocalDate date) {
            return List.copyOf(rows.values());
        }
    }
}
