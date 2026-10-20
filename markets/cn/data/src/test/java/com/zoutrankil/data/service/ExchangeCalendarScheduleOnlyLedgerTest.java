package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.calendar.application.ExchangeCalendarCoverage;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SyncScheduleStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class ExchangeCalendarScheduleOnlyLedgerTest {
    @TempDir Path temporary;

    @Test
    void existingScheduleStoreDoesNotPretendToContainRunHistory() throws Exception {
        Path path = temporary.resolve("control.sqlite3");
        new SyncScheduleStore(path);
        assertFalse(ExchangeCalendarCoverage.hasHistorySchema(path));
        new SyncRunLedger(path);
        assertTrue(ExchangeCalendarCoverage.hasHistorySchema(path));
    }
}
