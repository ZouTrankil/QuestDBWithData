package com.zoutrankil.market.cn.rates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.zoutrankil.data.domain.macro.interest.InterestRateDatasets;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.mapper.DateObservationCodec;
import com.zoutrankil.market.cn.observation.port.DateObservationTarget;
import com.zoutrankil.market.cn.rates.application.InterestObservationSyncOwner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

class InterestObservationSyncTest {
    @TempDir Path root;
    static final LocalDate DAY = LocalDate.parse("2026-10-20");

    static DatasetDefinition readDefinition(String dataset) {
        return switch (dataset) {
            case "shibor" -> InterestRateDatasets.SHIBOR;
            case "hibor" -> InterestRateDatasets.HIBOR;
            case "shibor_lpr" -> InterestRateDatasets.LPR;
            default -> throw new IllegalArgumentException();
        };
    }

    static Map<String, JsonNode> sample(String dataset, String date, double rate) {
        var row = new LinkedHashMap<String, JsonNode>();
        row.put("date", new TextNode(date));
        for (var column : SourceContract.load(dataset).columns())
            if (column.type().equals("DOUBLE")) row.put(column.source(), new DoubleNode(rate));
        return row;
    }

    InterestObservationSyncOwner owner(
            String dataset, Memory target, Function<String, List<Map<String, JsonNode>>> source) {
        var pages = mock(TusharePageService.class);
        when(pages.fetcher(any(), any()))
                .thenReturn(
                        parameters ->
                                new PageExecutor.Page(
                                        source.apply((String) parameters.get("start_date")),
                                        null,
                                        false,
                                        null));
        return new InterestObservationSyncOwner(
                SourceContract.load(dataset),
                target.definition,
                target,
                pages,
                root.resolve("ledger.sqlite3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"shibor", "hibor", "shibor_lpr"})
    void twoQuotesKeepTheirOwnDateKeysAndEmptyWeekendNeverBecomesAQuote(String dataset)
            throws Exception {
        var definition = InterestObservationSyncOwner.writable(readDefinition(dataset));
        var target = new Memory(definition);
        var owner =
                owner(
                        dataset,
                        target,
                        date ->
                                switch (date) {
                                    case "20261015" -> List.of(sample(dataset, date, 2.5));
                                    case "20261016" -> List.of(sample(dataset, date, 3.5));
                                    default -> List.of();
                                });
        var result =
                owner.run(
                        owner.plan(
                                LocalDate.parse("2026-10-15"),
                                LocalDate.parse("2026-10-18"),
                                DAY,
                                Mode.BACKFILL));
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(2, result.sourceRows());
        assertEquals(2, target.rows.size());
        String physicalDate = definition.designatedTimestamp();
        var dates =
                target.rows.values().stream()
                        .map(row -> row.get(physicalDate).toString())
                        .sorted()
                        .toList();
        assertEquals(List.of("2026-10-15T00:00:00Z", "2026-10-16T00:00:00Z"), dates);
        for (var row : target.rows.values()) assertEquals(definition.columns().size(), row.size());
        assertEquals(2, definition.schemaVersion());
        assertEquals(List.of("observation_date"), definition.businessKey());
        assertFalse(
                readDefinition(dataset)
                        .capabilities()
                        .contains(DatasetDefinition.Capability.WRITE));
    }

    @Test
    void fullEmptyLprWindowHasSourceProofAndNoSyntheticRows() throws Exception {
        var target = new Memory(InterestObservationSyncOwner.writable(InterestRateDatasets.LPR));
        var owner = owner("shibor_lpr", target, date -> List.of());
        var result =
                owner.run(
                        owner.plan(
                                LocalDate.parse("2026-10-17"),
                                LocalDate.parse("2026-10-18"),
                                DAY,
                                Mode.RECONCILE));
        assertEquals(SyncRunState.VERIFIED_EMPTY, result.state());
        assertEquals(0, target.sends);
        assertEquals(0, target.rows.size());
    }

    @Test
    void foreignDateCannotWriteAndOriginalResumeKeepsItsFrozenScope() throws Exception {
        var target = new Memory(InterestObservationSyncOwner.writable(InterestRateDatasets.SHIBOR));
        var supplied = new HashMap<String, List<Map<String, JsonNode>>>();
        supplied.put("20261015", List.of(sample("shibor", "20261014", 2.5)));
        var owner = owner("shibor", target, supplied::get);
        var date = LocalDate.parse("2026-10-15");
        var failed = owner.run(owner.plan(date, date, DAY, Mode.INCREMENTAL));
        assertEquals(SyncRunState.FAILED, failed.state());
        assertEquals(0, target.sends);
        supplied.put("20261015", List.of(sample("shibor", "20261015", 2.5)));
        assertEquals(SyncRunState.VERIFIED, owner.resume(failed.runId()).state());
        supplied.put("20261015", List.of(sample("shibor", "20261015", 9.5)));
        assertEquals(
                SyncRunState.VERIFIED,
                owner.run(owner.plan(date, date, DAY, Mode.RECONCILE)).state());
        assertEquals(1, target.rows.size());
        assertEquals(9.5, target.rows.values().iterator().next().get("on"));
    }

    @Test
    void sourceFailureDoesNotPermitSwitchingPhysicalTargetOrExtendingPastBudget() throws Exception {
        var target = new Memory(InterestObservationSyncOwner.writable(InterestRateDatasets.HIBOR));
        var owner = owner("hibor", target, date -> List.of(sample("hibor", "20261001", 2.5)));
        var date = LocalDate.parse("2026-10-15");
        var failed = owner.run(owner.plan(date, date, DAY, Mode.BACKFILL));
        target.identity = "recreated-target";
        assertThrows(IllegalStateException.class, () -> owner.resume(failed.runId()));
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan(DAY.minusDays(31), DAY, DAY, Mode.BACKFILL));
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan(DAY, DAY.plusDays(1), DAY, Mode.BACKFILL));
    }

    static class Memory implements DateObservationTarget {
        final DatasetDefinition definition;
        final DateObservationCodec codec;
        final Map<String, Map<String, Object>> rows = new HashMap<>();
        String identity = "original-rate-target";
        int sends;

        Memory(DatasetDefinition definition) {
            this.definition = definition;
            codec = new DateObservationCodec(definition);
        }

        public String targetId() {
            return identity;
        }

        public void preflight() {}

        public boolean walSettled() {
            return true;
        }

        public void send(List<Map<String, Object>> values) {
            sends++;
            for (var value : values) rows.put(codec.key(value), value);
        }

        public List<Map<String, Object>> readback(List<String> keys) {
            return keys.stream().filter(rows::containsKey).map(rows::get).toList();
        }
    }
}
