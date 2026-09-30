package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DailyCheckpointTest {
    @Test void checkpointRowsCompareByCompleteKeyWithoutRequiringComparableKeys() {
        var first = row("000001.SZ", 10.0);
        var second = row("600000.SH", null);
        assertTrue(DailyCheckpoint.sameRows(List.of(first, second), List.of(second, first)));
        assertFalse(DailyCheckpoint.sameRows(List.of(first, second), List.of(row("000001.SZ", 11.0), second)));
        assertFalse(DailyCheckpoint.sameRows(List.of(first, second), List.of(first, first)));
        assertFalse(DailyCheckpoint.sameRows(List.of(first, first), List.of(first, second)));
        assertFalse(DailyCheckpoint.sameRows(List.of(first, second), List.of(first)));
    }

    private static com.zoutrankil.data.domain.DailyMarketBar row(String code, Double close) {
        return new com.zoutrankil.data.domain.DailyMarketBar(code, LocalDate.of(2026, 9, 28),
                9.0, 11.0, 8.0, close, 9.5, 0.5, 5.0, 100.0, 1000.0, null, null);
    }

    @Test void latestValidatedReceiptSelectionUsesVerificationTimeNotRunIdBrowseOrder() {
        var date = LocalDate.of(2026, 9, 28);
        var older = verified("z-old", date, date, "2026-09-29T10:00:00Z", SyncJobDefinition.Mode.INCREMENTAL);
        var newer = verified("a-new", date, date, "2026-09-29T11:00:00Z", SyncJobDefinition.Mode.RECONCILE);

        var selected = DailyCheckpoint.latestRunsForDates(List.of(date), List.of(newer, older));

        assertEquals("a-new", selected.get(date).runId());
    }

    @Test void equalVerificationTimesHaveAStableRunIdTieBreak() {
        var date = LocalDate.of(2026, 9, 28);
        var first = verified("a-run", date, date, "2026-09-29T11:00:00Z", SyncJobDefinition.Mode.INCREMENTAL);
        var second = verified("z-run", date, date, "2026-09-29T11:00:00Z", SyncJobDefinition.Mode.INCREMENTAL);

        var selected = DailyCheckpoint.latestRunsForDates(List.of(date), List.of(second, first));

        assertEquals("z-run", selected.get(date).runId());
    }

    @Test void onlyIncrementalRunsOnTheExactBootstrapAnchorAdvanceCoverage() {
        var anchor = LocalDate.of(2026, 9, 25);
        var backfill = verified("backfill", anchor, anchor.plusDays(20), "2026-09-29T12:00:00Z",
                SyncJobDefinition.Mode.BACKFILL);
        var wrongAnchor = verified("wrong-anchor", anchor.minusDays(1), anchor.plusDays(30), "2026-09-29T13:00:00Z",
                SyncJobDefinition.Mode.INCREMENTAL);
        assertNull(DailyCheckpoint.contiguousIncrementalThrough(anchor, List.of(backfill, wrongAnchor)));

        var bootstrapRun = verified("bootstrap", anchor, anchor.plusDays(1), "2026-09-29T14:00:00Z",
                SyncJobDefinition.Mode.INCREMENTAL);
        var next = verified("next", anchor.plusDays(2), anchor.plusDays(3), "2026-09-29T15:00:00Z",
                SyncJobDefinition.Mode.INCREMENTAL);
        var newerReconcile = verified("reconcile", anchor.plusDays(4), anchor.plusDays(20), "2026-09-29T16:00:00Z",
                SyncJobDefinition.Mode.RECONCILE);

        assertEquals(anchor.plusDays(3), DailyCheckpoint.contiguousIncrementalThrough(anchor,
                List.of(backfill, wrongAnchor, bootstrapRun, next, newerReconcile)));
    }

    private static DailyCheckpoint.VerifiedRun verified(String id, LocalDate from, LocalDate through,
            String at, SyncJobDefinition.Mode mode) {
        return new DailyCheckpoint.VerifiedRun(id, from, through, Instant.parse(at), mode);
    }
}
