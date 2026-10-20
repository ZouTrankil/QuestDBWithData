package com.zoutrankil.data.factor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.factor.domain.FactorDefinition;
import com.zoutrankil.data.factor.engine.L2FactorPanel;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

class L2FactorPanelTest {
    private static FactorDefinition definition(String id, String transform) {
        return new FactorDefinition(
                id,
                "v1",
                "stock",
                "l2_microstructure",
                "l2_daily_features",
                "x",
                transform,
                "positive",
                3,
                "asof",
                "none",
                "none",
                true,
                true);
    }

    @Test
    void retainsThreeObservedRowsAndSharesInputsWithoutChangingThem() {
        var date = LocalDate.parse("2026-09-30");
        var builder = new L2FactorPanel.Builder(date, date, List.of("x", "clean_amount"));
        builder.add("A", date.minusDays(100), new double[] {99, 2});
        builder.add("A", date.minusDays(70), new double[] {10, 2});
        builder.add("A", date.minusDays(30), new double[] {20, 2});
        builder.add("A", date.minusDays(1), new double[] {30, 2});
        double[] last = {50, 2};
        builder.add("A", date, last);
        last[0] = 999;
        assertEquals(4, builder.retainedRows());
        var result =
                builder.compute(
                                List.of(
                                        definition("delta", "delta3"),
                                        definition("raw", "raw"),
                                        definition("ratio", "to_clean_amount"),
                                        definition("bool", "bool")))
                        .getFirst();
        assertArrayEquals(new double[] {40}, result.factors().get("delta"));
        assertArrayEquals(new double[] {50}, result.factors().get("raw"));
        assertArrayEquals(new double[] {25}, result.factors().get("ratio"));
        assertArrayEquals(new double[] {50}, result.factors().get("bool"));
        result.factors().get("raw")[0] = 123;
        assertEquals(
                50,
                builder.compute(List.of(definition("raw", "raw")))
                        .getFirst()
                        .factors()
                        .get("raw")[0]);
    }

    @Test
    void missingHistoryAndTinyDenominatorRemainMissing() {
        var date = LocalDate.parse("2026-09-30");
        var builder = new L2FactorPanel.Builder(date, date, List.of("x", "clean_amount"));
        builder.add("A", date, new double[] {1, 1e-13});
        var result =
                builder.compute(
                                List.of(
                                        definition("delta", "delta3"),
                                        definition("ratio", "to_clean_amount")))
                        .getFirst();
        assertTrue(Double.isNaN(result.factors().get("delta")[0]));
        assertTrue(Double.isNaN(result.factors().get("ratio")[0]));
    }

    @Test
    void rejectsDuplicateDatesRatherThanSilentlyChoosingAVersion() {
        var date = LocalDate.parse("2026-09-30");
        var builder = new L2FactorPanel.Builder(date, date, List.of("x"));
        builder.add("A", date, new double[] {1});
        assertThrows(
                IllegalArgumentException.class, () -> builder.add("A", date, new double[] {2}));
    }
}
