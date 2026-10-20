package com.zoutrankil.data.stock.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.stock.port.StockStDailyWriteSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

class StockStDailyLayoutAdmissionTest {
    @TempDir Path root;

    @Test
    void oldPublicationProtocolRejectsNewCodecBeforeCalendarSourceAndPhysicalAccess() {
        var source = mock(StockStDailySource.class);
        var calendars = mock(StockStTradingDates.class);
        var writer = mock(StockStDailyWriteSession.Versioned.class);
        when(writer.datasetVersion()).thenReturn(2);
        var day = LocalDate.of(2026, 9, 28);
        var request =
                StockStDailySyncJobOwner.DEFINITION.freeze(
                        Mode.BACKFILL,
                        Map.of(
                                "targetId",
                                "d012-logical-v1-" + "a".repeat(64),
                                "physicalTargetId",
                                "static-v2-" + "b".repeat(64),
                                "trade_dates",
                                "20260928"),
                        day,
                        day,
                        day);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StockStDailySyncAdapter(source, calendars, writer, root)
                                .preflight(request));
        verify(writer).datasetVersion();
        verify(writer, never()).preflight();
        verifyNoInteractions(source, calendars);
    }
}
