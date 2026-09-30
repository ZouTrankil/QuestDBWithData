package com.zoutrankil.questdbwithdata.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class DailyBasicPlanningBoundsTest {
    private static final LocalDate LOGICAL_DATE = LocalDate.of(2026, 9, 30);

    @Test void omittedUpperBoundDefaultsToLogicalDateAndFutureBoundIsRejected() {
        assertEquals(LOGICAL_DATE, DailyBasicJobService.resolveEnd(null, LOGICAL_DATE));
        assertEquals(LocalDate.of(2026, 9, 29),
                DailyBasicJobService.resolveEnd(LocalDate.of(2026, 9, 29), LOGICAL_DATE));
        assertThrows(IllegalArgumentException.class,
                () -> DailyBasicJobService.resolveEnd(LocalDate.of(2026, 10, 1), LOGICAL_DATE));
    }

    @Test void targetRowsMustFallInsideVerifiedPrefixOrTheNewBootstrapWindow() {
        var target = new DailyBasicJobService.TargetRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 20));
        assertThrows(IllegalStateException.class, () -> DailyBasicJobService.validateTargetRange(target, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), LOGICAL_DATE));
        assertDoesNotThrow(() -> DailyBasicJobService.validateTargetRange(
                new DailyBasicJobService.TargetRange(null, null), null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), LOGICAL_DATE));
    }

    @Test void ledgerlessRowsOutsideTheVerifiedOrRequestedRangeFailClosed() {
        var coverage = new DailyBasicCoverage.Coverage(LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 7, 25), LocalDate.of(2026, 9, 5), true,
                LocalDate.of(2026, 7, 28), LocalDate.of(2026, 9, 1));
        // The last covered trading dates can be legitimate all-empty source receipts.
        var coveredTarget = new DailyBasicJobService.TargetRange(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 9, 1));
        assertDoesNotThrow(() -> DailyBasicJobService.validateTargetRange(coveredTarget, coverage,
                null, LocalDate.of(2026, 9, 30), LOGICAL_DATE));
        assertThrows(IllegalStateException.class, () -> DailyBasicJobService.validateTargetRange(
                new DailyBasicJobService.TargetRange(LocalDate.of(2026, 7, 24), LocalDate.of(2026, 9, 1)),
                coverage, null, LocalDate.of(2026, 9, 30), LOGICAL_DATE));
        assertThrows(IllegalStateException.class, () -> DailyBasicJobService.validateTargetRange(
                new DailyBasicJobService.TargetRange(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 9, 2)),
                coverage, null, LocalDate.of(2026, 9, 30), LOGICAL_DATE));
        assertThrows(IllegalStateException.class, () -> DailyBasicJobService.validateTargetRange(
                new DailyBasicJobService.TargetRange(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 8, 31)),
                coverage, null, LocalDate.of(2026, 9, 30), LOGICAL_DATE));
    }

    @Test void disappearanceOfPreviouslyVerifiedRowsFailsClosed() {
        var coverage = new DailyBasicCoverage.Coverage(LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), true,
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 29));
        assertThrows(IllegalStateException.class, () -> DailyBasicJobService.validateTargetRange(
                new DailyBasicJobService.TargetRange(null, null), coverage, null,
                LocalDate.of(2026, 9, 30), LOGICAL_DATE));
        assertDoesNotThrow(() -> DailyBasicJobService.validateTargetRange(
                new DailyBasicJobService.TargetRange(null, null),
                new DailyBasicCoverage.Coverage(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 1), false, null, null), null, LocalDate.of(2026, 9, 30), LOGICAL_DATE));
    }
}
