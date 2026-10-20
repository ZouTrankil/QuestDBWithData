package com.zoutrankil.data.strategy.score;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class ScoreRowIdentityTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @Test
    void aggregationTagsCannotBecomeInstrumentCodes() {
        assertThrows(IllegalArgumentException.class, () -> new ScoreRow(DAY, "MARKET", Map.of()));
        assertThrows(
                IllegalArgumentException.class, () -> new ScoreRow(DAY, "ETF_UNIVERSE", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScoreRow(DAY, "510300.SH", ScoreRow.AggregationScope.MARKET, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ScoreRow(DAY, null, null, Map.of()));
    }

    @Test
    void marketRowsRetainScopeAcrossFeaturePreparationAndFreeze() {
        var source = ScoreRow.aggregate(DAY, ScoreRow.AggregationScope.MARKET, Map.of("score", 3.));
        var changed = source.withValues(Map.of("score", 4.));
        var mutable = new ScoreMath.MutableRow(changed);
        mutable.put("prepared", true);
        var restored = mutable.freeze();
        assertNull(restored.instrumentCode());
        assertEquals(ScoreRow.AggregationScope.MARKET, restored.aggregationScope());
        assertEquals(4., restored.number("score"));
        assertEquals(3., source.number("score"));
        assertThrows(IllegalStateException.class, restored::requireInstrumentCode);
    }

    @Test
    void instrumentIdentityAndDuplicateRulesSurviveScoringRoundTrip() {
        var source = new ScoreRow(DAY, "510300.SH", Map.of("close", 2.5));
        var restored = new ScoreMath.MutableRow(source).freeze();
        assertEquals(source, restored);
        assertEquals("510300.SH", restored.requireInstrumentCode());
        assertNull(restored.aggregationScope());
        assertThrows(
                IllegalArgumentException.class, () -> ScoreMath.mutable(List.of(source, source)));
    }
}
