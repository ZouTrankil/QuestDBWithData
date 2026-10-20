package com.zoutrankil.data.strategy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.strategy.input.StrategyInputSchema;
import com.zoutrankil.data.strategy.score.ScoreRow;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class MainStrategyDataFramesTest {
    @Test
    void standardFramesKeepModelValuesAndPublishOnlyCanonicalIdentity() {
        var day = LocalDate.of(2026, 10, 16);
        var values = new LinkedHashMap<String, Object>();
        values.put("score", .625);
        values.put("missing", null);
        values.put("not_finite", Double.NaN);
        var row = new ScoreRow(day, "000001.SZ", values);
        var frame =
                MainStrategyDataService.frames(List.of(row), true, StrategyInputSchema.V2)
                        .getFirst();
        var expected = new LinkedHashMap<>(values);
        expected.put("trade_date", day);
        expected.put("instrument_code", "000001.SZ");
        assertEquals(expected, frame);
        assertFalse(frame.containsKey("ts_code"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        MainStrategyDataService.frames(
                                List.of(
                                        new ScoreRow(
                                                day, "000001.SZ", Map.of("ts_code", "another"))),
                                true,
                                StrategyInputSchema.V2));
        assertThrows(
                IllegalStateException.class,
                () ->
                        MainStrategyDataService.frames(
                                List.of(
                                        ScoreRow.aggregate(
                                                day, ScoreRow.AggregationScope.MARKET, values)),
                                true,
                                StrategyInputSchema.V2));
    }

    @Test
    void completeScoreFramesRetainExactOrderedValuesAndReplaceOnlyIdentityValues()
            throws Exception {
        var values = new LinkedHashMap<String, Object>();
        values.put("score", .625);
        values.put("trade_date", "old");
        values.put("nullable", null);
        values.put("flag", false);
        values.put("ts_code", "old");
        values.put("not_finite", Double.NaN);
        var day = LocalDate.of(2026, 10, 16);
        var row = new ScoreRow(day, "510300.SH", values);
        var expected = new LinkedHashMap<String, Object>(values);
        expected.put("trade_date", day);
        expected.put("ts_code", row.instrumentCode());
        var frame = MainStrategyDataService.legacyV1Frames(List.of(row), true).getFirst();
        assertEquals(expected, frame);
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(frame.keySet()));
        assertEquals(
                new ObjectMapper().findAndRegisterModules().writeValueAsString(expected),
                new ObjectMapper().findAndRegisterModules().writeValueAsString(frame));
        assertEquals(row.values().size(), frame.size());
        assertEquals("old", row.values().get("trade_date"));
    }

    @Test
    void coverageFramesRetainEveryFieldAndAppendOnlyTheSignalDate() {
        var day = LocalDate.of(2026, 10, 16);
        var row =
                ScoreRow.aggregate(
                        day,
                        ScoreRow.AggregationScope.MARKET,
                        Map.of("count", 243550L, "hit_rate", .9));
        var expected = new LinkedHashMap<String, Object>(row.values());
        expected.put("trade_date", day);
        var actual = MainStrategyDataService.legacyV1Frames(List.of(row), false).getFirst();
        assertEquals(expected, actual);
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()));
        assertFalse(actual.containsKey("ts_code"));
    }

    @Test
    void aggregateScopeCannotBePublishedAsAV1SecurityIdentity() {
        var aggregate =
                ScoreRow.aggregate(
                        LocalDate.of(2026, 9, 30),
                        ScoreRow.AggregationScope.ETF_UNIVERSE,
                        Map.of("count", 3));
        assertThrows(
                IllegalStateException.class,
                () -> MainStrategyDataService.legacyV1Frames(List.of(aggregate), true));
    }
}
