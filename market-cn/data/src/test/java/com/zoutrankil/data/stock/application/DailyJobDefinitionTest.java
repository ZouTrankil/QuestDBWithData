package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.stock.application.DailyJobService;

import com.zoutrankil.data.domain.SyncJobDefinition;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DailyJobDefinitionTest {
    @Test void jobUsesExactTradeDateSlicesAndVerifiedCalendarDependency() {
        var definition = DailyJobService.definition();
        assertEquals("data.daily", definition.jobId());
        assertEquals("daily", definition.datasetId());
        assertEquals("daily_owner", definition.owner());
        assertEquals(SyncJobDefinition.Mode.INCREMENTAL, definition.defaultMode());
        assertEquals(5, definition.revisionDays());
        assertEquals("daily.trade_date", definition.slicePolicyRef());
        assertEquals(List.of(new SyncJobDefinition.JobRef("data.exchange_calendar", 1)), definition.dependencies());
        assertEquals(366, definition.budget().maxWindowDays());
        assertEquals(366, definition.budget().maxSlices());
        assertEquals(3_660_000, definition.budget().maxRows());
    }

    @Test void dailyOwnerRejectsFormalAndNonDedicatedTargets() {
        assertThrows(IllegalStateException.class, () -> DailyJobService.requireIsolatedTableName("daily"));
        assertThrows(IllegalStateException.class, () -> DailyJobService.requireIsolatedTableName("java_test_daily"));
        assertThrows(IllegalStateException.class, () -> DailyJobService.requireIsolatedTableName("java_d007_daily_"));
        assertDoesNotThrow(() -> DailyJobService.requireIsolatedTableName("java_d007_daily_test_123"));
    }
}
