package com.zoutrankil.market.cn.futures;

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
import com.fasterxml.jackson.databind.node.TextNode;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.futures.application.FuturesDaySyncOwner;
import com.zoutrankil.market.cn.futures.mapper.FuturesDayDefinition;
import com.zoutrankil.market.cn.futures.port.FuturesDayTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

class FuturesDaySyncTest {
    @TempDir Path root;
    static final LocalDate FROM = LocalDate.parse("2026-10-16"),
            TO = LocalDate.parse("2026-10-19"),
            LOGICAL = LocalDate.parse("2026-10-20");

    static Stream<String> datasets() {
        return FuturesDayDefinition.DATASETS.stream().sorted();
    }

    static String code(String dataset) {
        return dataset.equals("fut_mapping") ? "IF.CFX" : "IF2612.CFX";
    }

    final List<String> requests = new ArrayList<>();
    final Map<LocalDate, Double> sourceRows = new HashMap<>(Map.of(FROM, 100d, TO, 110d));
    LocalDate failedDate;

    static Map<String, JsonNode> raw(String dataset, LocalDate date, double price) {
        var row = new LinkedHashMap<String, JsonNode>();
        for (var c : SourceContract.load(dataset).businessColumns())
            row.put(
                    c.source(),
                    switch (c.type()) {
                        case "SYMBOL" ->
                                new TextNode(
                                        c.target().equals("ts_code")
                                                ? code(dataset)
                                                : c.target().equals("mapping_ts_code")
                                                        ? price >= 200 ? "IF2701.CFX" : "IF2612.CFX"
                                                        : "CFFEX");
                        case "TIMESTAMP" ->
                                new TextNode(date.format(DateTimeFormatter.BASIC_ISO_DATE));
                        default ->
                                new DoubleNode(
                                        switch (c.target()) {
                                            case "high", "up_limit" -> price + 20;
                                            case "low", "down_limit" -> price - 20;
                                            default -> price;
                                        });
                    });
        return row;
    }

    Path ledger(String dataset) {
        return root.resolve(dataset + ".sqlite");
    }

    FuturesDaySyncOwner owner(String dataset, Memory target) {
        var pages = mock(TusharePageService.class);
        when(pages.fetcher(any(), any()))
                .thenReturn(
                        params -> {
                            assertEquals(code(dataset), params.get("ts_code"));
                            String wire = params.get("trade_date").toString();
                            requests.add(wire);
                            LocalDate date =
                                    LocalDate.parse(wire, DateTimeFormatter.BASIC_ISO_DATE);
                            if (date.equals(failedDate)) throw new IOException("指定来源日读取失败");
                            Double value = sourceRows.get(date);
                            return new PageExecutor.Page(
                                    value == null ? List.of() : List.of(raw(dataset, date, value)),
                                    null,
                                    false,
                                    null);
                        });
        return new FuturesDaySyncOwner(dataset, target, pages, ledger(dataset));
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void fullWindowUsesSourceDatesIncludingWeekendAndPreservesEveryValueAndFrozenObservation(
            String dataset) throws Exception {
        var target = new Memory(dataset);
        var owner = owner(dataset, target);
        var plan = owner.plan(code(dataset), FROM, TO, LOGICAL, Mode.RECONCILE);
        assertFalse(Files.exists(ledger(dataset)));
        assertTrue(requests.isEmpty());
        target.beforeSend =
                () ->
                        assertTrue(
                                requests.containsAll(
                                        List.of("20261016", "20261017", "20261018", "20261019")));
        var result = owner.run(plan);
        assertEquals(SyncRunState.VERIFIED, result.state(), () -> failure(dataset, result.runId()));
        assertEquals(2, target.rows.size());
        for (var row : target.rows.values()) {
            assertEquals(plan.parameters().get("observedAt"), row.get("update_time"));
            LocalDate date =
                    Instant.parse(row.get("trade_date").toString())
                            .atOffset(ZoneOffset.UTC)
                            .toLocalDate();
            var expected = raw(dataset, date, sourceRows.get(date));
            assertEquals(SourceContract.load(dataset).columns().size(), row.size());
            for (var c : SourceContract.load(dataset).businessColumns()) {
                Object v = row.get(c.target());
                if (c.type().equals("DOUBLE"))
                    assertEquals(expected.get(c.source()).doubleValue(), v, c.target());
                if (c.type().equals("SYMBOL"))
                    assertEquals(expected.get(c.source()).textValue(), v, c.target());
            }
        }
        sourceRows.put(TO, 200d);
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(2, target.rows.size());
        assertTrue(
                dataset.equals("fut_mapping")
                        ? target.rows.values().stream()
                                .anyMatch(r -> "IF2701.CFX".equals(r.get("mapping_ts_code")))
                        : target.rows.values().stream().anyMatch(r -> r.values().contains(200d)));
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void lastSourceFailureDoesNotPublishPrefixAndResumeRetainsOriginalClockAndRange(String dataset)
            throws Exception {
        var target = new Memory(dataset);
        var owner = owner(dataset, target);
        failedDate = TO;
        var plan = owner.plan(code(dataset), FROM, TO, LOGICAL, Mode.BACKFILL);
        var failed = owner.run(plan);
        assertNotEquals(SyncRunState.VERIFIED, failed.state());
        assertEquals(0, target.sends);
        String original = new SyncRunLedger(ledger(dataset)).getRun(failed.runId()).frozenJson();
        failedDate = null;
        var restored = owner.resume(failed.runId());
        assertEquals(
                SyncRunState.VERIFIED, restored.state(), () -> failure(dataset, restored.runId()));
        assertEquals(
                original, new SyncRunLedger(ledger(dataset)).getRun(failed.runId()).frozenJson());
        assertTrue(
                target.rows.values().stream()
                        .allMatch(
                                r ->
                                        r.get("update_time")
                                                .equals(plan.parameters().get("observedAt"))));
        target.identity = "replacement";
        assertThrows(IllegalStateException.class, () -> owner.resume(failed.runId()));
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void emptyResponseNeedsTargetKeyAbsenceAndCannotEraseOldData(String dataset) throws Exception {
        var target = new Memory(dataset);
        var owner = owner(dataset, target);
        sourceRows.clear();
        var plan = owner.plan(code(dataset), FROM, FROM, LOGICAL, Mode.INCREMENTAL);
        assertEquals(SyncRunState.VERIFIED_EMPTY, owner.run(plan).state());
        assertEquals(0, target.sends);
        var old =
                FuturesDayDefinition.physical(
                        dataset,
                        SourceContract.load(dataset)
                                .normalize(raw(dataset, FROM, 100), FROM, Set.of(code(dataset))),
                        Instant.parse(plan.parameters().get("observedAt").toString()));
        target.rows.put(
                VerifiedMarketDataStore.codec(FuturesDayDefinition.from(dataset)).key(old), old);
        assertNotEquals(SyncRunState.VERIFIED_EMPTY, owner.run(plan).state());
        assertEquals(1, target.rows.size());
        assertEquals(0, target.sends);
    }

    @Test
    void unknownWriteKeepsLeaseAndCannotBeReplayed() throws Exception {
        String dataset = "fut_daily";
        var target = new Memory(dataset);
        target.interruptAfterSend = true;
        var owner = owner(dataset, target);
        var plan = owner.plan(code(dataset), FROM, FROM, LOGICAL, Mode.BACKFILL);
        var executor = Executors.newSingleThreadExecutor();
        String id;
        try {
            var result = executor.submit(() -> owner.run(plan)).get(5, TimeUnit.SECONDS);
            assertEquals(SyncRunState.IN_DOUBT, result.state());
            id = result.runId();
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, target.sends);
        assertEquals("DATASET_INTERVAL_BUSY", owner.run(plan).errorCode());
        assertThrows(IllegalStateException.class, () -> owner.resume(id));
        assertEquals(1, target.sends);
    }

    @Test
    void planRejectsBadScopeAndSourceCheckNeverCreatesFormalLedgerOrWrites() throws Exception {
        var target = new Memory("fut_daily");
        var owner = owner("fut_daily", target);
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan("IF.CFX", FROM, FROM, LOGICAL, Mode.BACKFILL));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        owner.plan(
                                "IF2612.CFX",
                                FROM,
                                FROM.plusDays(31),
                                LOGICAL.plusMonths(2),
                                Mode.BACKFILL));
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan("IF2612.CFX", TO, FROM, LOGICAL, Mode.BACKFILL));
        var result =
                owner.checkSource(owner.plan("IF2612.CFX", FROM, FROM, LOGICAL, Mode.BACKFILL));
        assertEquals(true, result.get("sourceCoverageVerified"));
        assertEquals(false, result.get("questdbWritten"));
        assertEquals(false, result.get("targetDataVerified"));
        assertEquals(0, target.sends);
        assertFalse(Files.exists(ledger("fut_daily")));
    }

    String failure(String dataset, String id) {
        try {
            return new SyncRunLedger(ledger(dataset)).get(id).payloadJson();
        } catch (Exception e) {
            return e.toString();
        }
    }

    static final class Memory implements FuturesDayTarget {
        final String dataset;
        final Map<String, Map<String, Object>> rows = new HashMap<>();
        String identity = "original";
        int sends;
        boolean interruptAfterSend;
        Runnable beforeSend = () -> {};

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
            beforeSend.run();
            sends++;
            var codec = VerifiedMarketDataStore.codec(FuturesDayDefinition.from(dataset));
            values.forEach(row -> rows.put(codec.key(row), row));
            if (interruptAfterSend) Thread.currentThread().interrupt();
        }

        public List<Map<String, Object>> readback(List<String> keys) {
            return keys.stream().filter(rows::containsKey).map(rows::get).toList();
        }
    }
}
