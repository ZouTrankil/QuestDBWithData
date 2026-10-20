package com.zoutrankil.data.factor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.factor.application.FactorDefinitionCatalog;
import com.zoutrankil.data.factor.engine.FactorFormulaEngine;
import com.zoutrankil.data.factor.engine.PointInTimeFactorJoin;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class EventFactorContractTest {
    @Test
    void eventAmountDoesNotReadOrOverwriteMarketAmount() throws Exception {
        var definition = FactorDefinitionCatalog.load().require("event_repurchase_amount");
        double[] market = {100, 200}, event = {5, 7};
        var columns = Map.of("amount", market, "event_pit:amount", event);
        assertArrayEquals(event, new FactorFormulaEngine().compute(definition, columns));
        assertArrayEquals(new double[] {100, 200}, market);
        assertThrows(
                IllegalArgumentException.class,
                () -> new FactorFormulaEngine().compute(definition, Map.of("amount", market)));
    }

    @Test
    void latestAnnouncementIncludingMissingValueWinsAndFutureNeverLeaks() {
        LocalDate first = LocalDate.parse("2026-09-01");
        var rows =
                List.of(
                        new PointInTimeFactorJoin.Observation(first, first, 2),
                        new PointInTimeFactorJoin.Observation(first, first, 3),
                        new PointInTimeFactorJoin.Observation(
                                first.plusDays(1), first.plusDays(1), Double.NaN),
                        new PointInTimeFactorJoin.Observation(
                                first.plusDays(3), first.plusDays(3), 8));
        var result =
                PointInTimeFactorJoin.join(
                        new LocalDate[] {
                            first.minusDays(1), first, first.plusDays(1), first.plusDays(2)
                        },
                        rows,
                        false);
        assertTrue(Double.isNaN(result[0]));
        assertEquals(3, result[1]);
        assertTrue(Double.isNaN(result[2]));
        assertTrue(Double.isNaN(result[3]));
    }

    @Test
    void marginIsUnavailableBeforeNextExchangeSessionAndRejectsSameDayAvailability() {
        LocalDate observed = LocalDate.parse("2026-09-30"),
                available = LocalDate.parse("2026-10-08");
        var result =
                PointInTimeFactorJoin.join(
                        new LocalDate[] {observed, available.minusDays(1), available},
                        List.of(new PointInTimeFactorJoin.Observation(observed, available, 9)),
                        true);
        assertTrue(Double.isNaN(result[0]));
        assertTrue(Double.isNaN(result[1]));
        assertEquals(9, result[2]);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PointInTimeFactorJoin.join(
                                new LocalDate[] {observed},
                                List.of(
                                        new PointInTimeFactorJoin.Observation(
                                                observed, observed, 9)),
                                true));
    }
}
