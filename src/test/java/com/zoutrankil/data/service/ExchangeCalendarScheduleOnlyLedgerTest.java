package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SyncScheduleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeCalendarScheduleOnlyLedgerTest {
    @TempDir Path temporary;

    @Test void existingScheduleStoreDoesNotPretendToContainRunHistory() throws Exception {
        Path path=temporary.resolve("control.sqlite3");
        new SyncScheduleStore(path);
        assertFalse(ExchangeCalendarCoverage.hasHistorySchema(path));
        new SyncRunLedger(path);
        assertTrue(ExchangeCalendarCoverage.hasHistorySchema(path));
    }
}
