package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyBasicJobDefinitionTest {
    @Test void jobIsBoundedIncrementalWithRevisionWindowAndCalendarDependency() {
        var definition = DailyBasicJobService.definition();
        assertEquals("data.daily_basic", definition.jobId());
        assertEquals(Mode.INCREMENTAL, definition.defaultMode());
        assertEquals(30, definition.revisionDays());
        assertEquals(List.of(new com.zoutrankil.data.domain.SyncJobDefinition.JobRef("data.exchange_calendar", 1)),
                definition.dependencies());
        assertEquals("daily_basic.trade_date", definition.slicePolicyRef());
        assertEquals(366, definition.budget().maxWindowDays());
        var anchor = definition.parameters().get("checkpointAnchor");
        assertEquals(com.zoutrankil.data.domain.SyncJobDefinition.ParameterType.DATE, anchor.type());
        assertFalse(anchor.required());
    }

    @Test void frozenCalendarIsSortedUniqueAndBounded() {
        var start = LocalDate.of(2026, 9, 25);
        var dates = List.of(start, start.plusDays(1), start.plusDays(3));
        String encoded = DailyBasicSyncAdapter.encodeTradeDates(dates);
        var request = DailyBasicJobService.definition().freeze(Mode.INCREMENTAL, Map.of("trade_dates", encoded),
                start, start.plusDays(3), start.plusDays(3));
        assertEquals(dates, DailyBasicSyncAdapter.decodeTradeDates(request));
        assertEquals("NONE", DailyBasicSyncAdapter.encodeTradeDates(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> DailyBasicSyncAdapter.encodeTradeDates(List.of(dates.get(1), dates.get(0))));
    }
}
