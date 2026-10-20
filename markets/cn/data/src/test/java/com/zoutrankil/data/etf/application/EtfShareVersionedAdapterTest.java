package com.zoutrankil.data.etf.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.etf.port.EtfShareWriteSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class EtfShareVersionedAdapterTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    private static final String TARGET = "static-v2-" + "a".repeat(64);
    @TempDir Path temp;

    @Test
    void newLayoutKeepsTheOriginalV3JobAndFreezesADistinctV4Identity() {
        assertSame(EtfShareSyncJobOwner.DEFINITION, EtfShareSyncJobOwner.forLayout(1));
        assertEquals(
                List.of(EtfShareSyncJobOwner.DEFINITION),
                new EtfShareSyncJobOwner().syncJobDefinitions());
        assertEquals(2, EtfShareSyncJobOwner.forLayout(2).datasetVersion());
        assertEquals(3, EtfShareSyncJobOwner.forLayout(1).version());
        assertEquals(4, EtfShareSyncJobOwner.forLayout(2).version());
        assertNotEquals(
                SyncRequestIdentity.fingerprint(request(1), TARGET),
                SyncRequestIdentity.fingerprint(request(2), TARGET));
        assertThrows(IllegalArgumentException.class, () -> EtfShareSyncJobOwner.definition(1));
    }

    @Test
    void matchingVersionUsesTheOriginalCalendarAndPreflightWithoutFetchingSource() {
        for (int version : List.of(1, 2)) {
            var source = mock(EtfShareSource.class);
            var calendar = mock(EtfShareTradingDates.class);
            when(calendar.read(DAY, DAY)).thenReturn(List.of(DAY));
            var port = mock(EtfShareWriteSession.Versioned.class);
            when(port.datasetVersion()).thenReturn(version);
            new EtfShareSyncAdapter(source, calendar, port, temp).preflight(request(version));
            verify(port).preflight();
            verifyNoInteractions(source);
        }
    }

    @Test
    void versionMismatchFailsBeforeCalendarSourceOrPhysicalPreflight() {
        var source = mock(EtfShareSource.class);
        var calendar = mock(EtfShareTradingDates.class);
        var legacyPort = mock(EtfShareWriteSession.class);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EtfShareSyncAdapter(source, calendar, legacyPort, temp)
                                .preflight(request(2)));
        verifyNoInteractions(source, calendar, legacyPort);
        var newPort = mock(EtfShareWriteSession.Versioned.class);
        when(newPort.datasetVersion()).thenReturn(2);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EtfShareSyncAdapter(source, calendar, newPort, temp)
                                .preflight(request(1)));
        verify(newPort, never()).preflight();
        verifyNoInteractions(source, calendar);
    }

    private static SyncJobDefinition.FrozenRequest request(int version) {
        return EtfShareSyncJobOwner.forLayout(version)
                .freeze(
                        Mode.BACKFILL,
                        Map.of(
                                "observedAt",
                                "2026-09-28T12:34:56.123456Z",
                                "targetId",
                                TARGET,
                                "trade_dates",
                                EtfShareSyncAdapter.encodeTradeDates(List.of(DAY))),
                        DAY,
                        DAY,
                        DAY);
    }
}
