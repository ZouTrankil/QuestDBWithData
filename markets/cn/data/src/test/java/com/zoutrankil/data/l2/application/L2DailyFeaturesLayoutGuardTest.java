package com.zoutrankil.data.l2.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.IntervalLockStore;
import com.zoutrankil.data.l2.port.FormalL2FrontierReader;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;

import org.junit.jupiter.api.Test;

import java.util.List;

class L2DailyFeaturesLayoutGuardTest {
    @Test
    void oldSourceAndFormalIngestionRejectV2BeforeAnyIo() throws Exception {
        var source = mock(L2DailyFeaturesParquetSource.class);
        var inspection = mock(L2DailyFeaturesParquetSource.Inspection.class);
        var writer = mock(L2DailyFeaturesWriteSession.Versioned.class);
        when(writer.datasetVersion()).thenReturn(2);
        var frontier = mock(FormalL2FrontierReader.class);
        var locks = mock(IntervalLockStore.class);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new L2DailyFeaturesSyncAdapter(
                                source,
                                inspection,
                                List.of(),
                                L2DailyFeaturesJobService.definition(),
                                writer));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FormalL2DailyFeaturesIngestion(source, writer, frontier, locks));
        verify(writer, never()).preflight();
        verify(writer, never()).send(anyList());
        verifyNoInteractions(source, inspection, frontier, locks);
    }
}
