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
import com.zoutrankil.data.etf.port.EtfAdjWriteSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class EtfAdjVersionedAdapterTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    private static final String TARGET = "static-v2-" + "a".repeat(64);
    @TempDir Path temp;

    @Test
    void newFrozenDefinitionHasItsOwnIdentityAndDoesNotReplaceTheRegisteredV1() {
        assertSame(EtfAdjSyncJobOwner.DEFINITION, EtfAdjSyncJobOwner.definition(1));
        assertEquals(
                List.of(EtfAdjSyncJobOwner.DEFINITION),
                new EtfAdjSyncJobOwner().syncJobDefinitions());
        assertEquals(2, EtfAdjSyncJobOwner.definition(2).datasetVersion());
        assertNotEquals(
                SyncRequestIdentity.fingerprint(request(1), TARGET),
                SyncRequestIdentity.fingerprint(request(2), TARGET));
        assertThrows(IllegalArgumentException.class, () -> EtfAdjSyncJobOwner.definition(3));
    }

    @Test
    void matchingVersionUsesTheOriginalCalendarAndPreflightWithoutFetchingSource() {
        for (int version : List.of(1, 2)) {
            var source = mock(EtfAdjSource.class);
            var calendar = mock(EtfAdjTradingDates.class);
            when(calendar.read(DAY, DAY)).thenReturn(List.of(DAY));
            var port = mock(EtfAdjWriteSession.Versioned.class);
            when(port.datasetVersion()).thenReturn(version);
            new EtfAdjSyncAdapter(source, calendar, port, temp).preflight(request(version));
            verify(port).preflight();
            verifyNoInteractions(source);
        }
    }

    @Test
    void versionMismatchFailsBeforeCalendarSourceOrPhysicalPreflight() {
        var source = mock(EtfAdjSource.class);
        var calendar = mock(EtfAdjTradingDates.class);
        var legacyPort = mock(EtfAdjWriteSession.class);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EtfAdjSyncAdapter(source, calendar, legacyPort, temp)
                                .preflight(request(2)));
        verifyNoInteractions(source, calendar, legacyPort);
        var newPort = mock(EtfAdjWriteSession.Versioned.class);
        when(newPort.datasetVersion()).thenReturn(2);
        assertThrows(
                IllegalArgumentException.class,
                () -> new EtfAdjSyncAdapter(source, calendar, newPort, temp).preflight(request(1)));
        verify(newPort, never()).preflight();
        verifyNoInteractions(source, calendar);
    }

    private static SyncJobDefinition.FrozenRequest request(int version) {
        return EtfAdjSyncJobOwner.definition(version)
                .freeze(
                        Mode.BACKFILL,
                        Map.of(
                                "targetId",
                                TARGET,
                                "trade_dates",
                                EtfAdjSyncAdapter.encodeTradeDates(List.of(DAY))),
                        DAY,
                        DAY,
                        DAY);
    }
}
