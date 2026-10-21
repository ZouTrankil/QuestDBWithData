package com.zoutrankil.market.cn.chips;

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
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.chips.application.CyqPerfSyncOwner;
import com.zoutrankil.market.cn.chips.cli.CyqPerfCommands;
import com.zoutrankil.market.cn.chips.mapper.CyqPerfDefinition;
import com.zoutrankil.market.cn.chips.port.CyqPerfTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class CyqPerfSyncTest {
    @TempDir Path root;
    static final LocalDate DAY = LocalDate.parse("2026-10-16"),
            LOGICAL = LocalDate.parse("2026-10-20");
    final Memory target = new Memory();
    final Map<String, Double> sourceValues =
            new HashMap<>(Map.of("600000.SH", 1d, "000001.SZ", 2d));
    final List<String> queries = new ArrayList<>();
    final TusharePageService pages = mock(TusharePageService.class);

    Path ledger() {
        return root.resolve("ledger.sqlite");
    }

    CyqPerfSyncOwner owner(boolean open) {
        when(pages.fetcher(any(), any()))
                .thenReturn(
                        params -> {
                            assertEquals("20261016", params.get("trade_date"));
                            String code = params.get("ts_code").toString();
                            queries.add(code);
                            var value = sourceValues.get(code);
                            return new PageExecutor.Page(
                                    value == null ? List.of() : List.of(sample(code, value)),
                                    null,
                                    false,
                                    null);
                        });
        ExchangeCalendarReadPort calendars =
                query ->
                        new DatasetReadPage<>(
                                "exchange_calendar",
                                1,
                                "calendar-original",
                                Instant.EPOCH,
                                List.of(
                                        new ExchangeCalendar(
                                                "SSE",
                                                (LocalDate) query.fromInclusive(),
                                                open,
                                                null)),
                                null);
        return new CyqPerfSyncOwner(target, pages, calendars, ledger());
    }

    static Map<String, JsonNode> sample(String code, double value) {
        var raw = new LinkedHashMap<String, JsonNode>();
        for (var c : SourceContract.load("cyq_perf").columns())
            raw.put(
                    c.source(),
                    switch (c.type()) {
                        case "SYMBOL" -> new TextNode(code);
                        case "TIMESTAMP" -> new TextNode("20261016");
                        default -> new DoubleNode(value++);
                    });
        return raw;
    }

    @Test
    void plansAreBoundedCanonicalAndNeverCreateLedgerOrCallSource() {
        var owner = owner(true);
        var request = owner.plan(List.of("600000.SH", "000001.SZ"), DAY, LOGICAL, Mode.BACKFILL);
        assertEquals(List.of("000001.SZ", "600000.SH"), request.parameters().get("codes"));
        assertEquals(DAY, request.from());
        assertEquals(DAY, request.to());
        assertFalse(Files.exists(ledger()));
        assertTrue(queries.isEmpty());
        assertEquals(0, target.sends);
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan(List.of(), DAY, LOGICAL, Mode.BACKFILL));
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan(List.of("600000.SH", "600000.SH"), DAY, LOGICAL, Mode.BACKFILL));
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan(List.of("600000"), DAY, LOGICAL, Mode.BACKFILL));
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan(List.of("600000.SH"), DAY, DAY.minusDays(1), Mode.BACKFILL));
        assertThrows(
                IllegalArgumentException.class,
                () -> owner(false).plan(List.of("600000.SH"), DAY, LOGICAL, Mode.BACKFILL));
    }

    @Test
    void eachExplicitSecurityMustBeCollectedBeforeFirstSendAndAllFieldsSurviveRevision()
            throws Exception {
        var owner = owner(true);
        var request = owner.plan(List.of("600000.SH", "000001.SZ"), DAY, LOGICAL, Mode.RECONCILE);
        target.beforeSend =
                () -> assertEquals(Set.of("600000.SH", "000001.SZ"), new HashSet<>(queries));
        var first = owner.run(request);
        assertEquals(SyncRunState.VERIFIED, first.state(), () -> failure(first.runId()));
        assertEquals(2, target.rows.size());
        for (var row : target.rows.values()) {
            assertEquals("2026-10-16T00:00:00Z", row.get("trade_date"));
            var raw = sample(row.get("ts_code").toString(), sourceValues.get(row.get("ts_code")));
            assertEquals(11, row.size());
            for (var c : CyqPerfDefinition.DEFINITION.columns())
                if (c.storageType().name().equals("DOUBLE"))
                    assertEquals(raw.get(c.sourceName()).doubleValue(), row.get(c.storageName()));
        }
        sourceValues.put("600000.SH", 100d);
        assertEquals(SyncRunState.VERIFIED, owner.run(request).state());
        assertEquals(2, target.rows.size());
        assertEquals(
                100d,
                target.rows.get("[\"600000.SH\",\"2026-10-16T00:00:00Z\"]").get("winner_rate"));
    }

    String failure(String id) {
        try {
            return new SyncRunLedger(ledger()).get(id).payloadJson();
        } catch (Exception e) {
            return e.toString();
        }
    }

    @Test
    void missingLastSecurityNeverPublishesEarlierSecurityAndResumeKeepsOriginalScope()
            throws Exception {
        var owner = owner(true);
        sourceValues.remove("600000.SH");
        var original = owner.plan(List.of("600000.SH", "000001.SZ"), DAY, LOGICAL, Mode.BACKFILL);
        var failed = owner.run(original);
        assertNotEquals(SyncRunState.VERIFIED, failed.state());
        assertEquals(0, target.sends);
        String saved = new SyncRunLedger(ledger()).getRun(failed.runId()).frozenJson();
        sourceValues.put("600000.SH", 3d);
        assertEquals(SyncRunState.VERIFIED, owner.resume(failed.runId()).state());
        assertEquals(saved, new SyncRunLedger(ledger()).getRun(failed.runId()).frozenJson());
        assertEquals(2, target.rows.size());
    }

    @Test
    void sourceOnlyCheckDoesNotCreateFormalLedgerOrWriteAndChangedTargetCannotResume()
            throws Exception {
        var owner = owner(true);
        var plan = owner.plan(List.of("600000.SH"), DAY, LOGICAL, Mode.BACKFILL);
        var checked = owner.checkSource(plan);
        assertEquals(true, checked.get("sourceCoverageVerified"));
        assertEquals(false, checked.get("targetDataVerified"));
        assertEquals(false, checked.get("questdbWritten"));
        assertEquals(0, target.sends);
        assertFalse(Files.exists(ledger()));
        sourceValues.clear();
        var failed = owner.run(plan);
        target.identity = "replacement-generation";
        assertThrows(IllegalStateException.class, () -> owner.resume(failed.runId()));
        assertEquals(0, target.sends);
    }

    @Test
    void unknownSendKeepsOriginalLockAndNormalRunOrResumeCannotResend() throws Exception {
        var owner = owner(true);
        target.interruptAfterSend = true;
        var request = owner.plan(List.of("600000.SH"), DAY, LOGICAL, Mode.INCREMENTAL);
        var executor = Executors.newSingleThreadExecutor();
        String id;
        try {
            var result = executor.submit(() -> owner.run(request)).get(5, TimeUnit.SECONDS);
            assertEquals(SyncRunState.IN_DOUBT, result.state());
            id = result.runId();
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, target.sends);
        assertEquals("DATASET_INTERVAL_BUSY", owner.run(request).errorCode());
        assertThrows(IllegalStateException.class, () -> owner.resume(id));
        assertEquals(1, target.sends);
    }

    @Test
    void unknownOrExtraCliArgumentsAreRejectedBeforeAnySourceCall() {
        var commands = new CyqPerfCommands(owner(true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        commands.execute(
                                "resume-cyq-perf",
                                Map.of("--run", "old", "--date", DAY.toString())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        commands.execute(
                                "run-cyq-perf",
                                Map.of("--codes", "600000.SH", "--date", DAY.toString())));
        assertThrows(IllegalArgumentException.class, () -> commands.execute("other", Map.of()));
        assertEquals(0, target.sends);
        assertTrue(queries.isEmpty());
    }

    static final class Memory implements CyqPerfTarget {
        final Map<String, Map<String, Object>> rows = new HashMap<>();
        String identity = "original-cyq-target";
        int sends;
        boolean interruptAfterSend;
        Runnable beforeSend = () -> {};

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
            var codec = VerifiedMarketDataStore.codec(CyqPerfDefinition.DEFINITION);
            values.forEach(row -> rows.put(codec.key(row), row));
            if (interruptAfterSend) Thread.currentThread().interrupt();
        }

        public List<Map<String, Object>> readback(List<String> keys) {
            return keys.stream().filter(rows::containsKey).map(rows::get).toList();
        }
    }
}
