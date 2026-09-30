package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExchangeCalendarFailureTest {
    @TempDir Path root;
    private SyncJobDefinition.FrozenRequest request() {
        var day=LocalDate.of(2026,9,28);
        return ExchangeCalendarSyncAdapter.definition(true).freeze(null,Map.of("exchanges",List.of("SSE")),day,day,day);
    }
    @Test void cancelledCalendarRunNeverFetchesOrWrites() throws Exception {
        var path=root.resolve("cancel.sqlite");var ledger=new SyncRunLedger(path);
        var source=mock(ExchangeCalendarSource.class);var port=mock(ExchangeCalendarWritePort.class);
        var runner=new SyncJobRunner<ExchangeCalendar,ExchangeCalendar.Key>(ledger,new DatasetIntervalLock(path));
        var result=runner.run("cancelled",null,"calendar-target",request(),
                new ExchangeCalendarSyncAdapter(source,port,root),()->true);
        assertEquals(SyncRunState.CANCELLED,result.state());
        verifyNoInteractions(source);verify(port,never()).send(anyList());
    }
    @Test void uncertainCalendarSendIsHeldAndCannotBeAutomaticallyReplayed() throws Exception {
        var path=root.resolve("unknown.sqlite");var ledger=new SyncRunLedger(path);
        var source=mock(ExchangeCalendarSource.class);var port=mock(ExchangeCalendarWritePort.class);
        var day=LocalDate.of(2026,9,28);
        var row=new ExchangeCalendar("SSE",day,true,day.minusDays(4));
        when(source.fetch(any(),any())).thenReturn(new SyncJobRunner.Page<>(List.of(row),"source-hash","source-evidence",null));
        doThrow(new IllegalStateException("transport outcome unknown")).when(port).send(anyList());
        when(port.readback(anyList())).thenReturn(List.of(row));when(port.walSettled()).thenReturn(true);
        var adapter=new ExchangeCalendarSyncAdapter(source,port,root);
        var runner=new SyncJobRunner<ExchangeCalendar,ExchangeCalendar.Key>(ledger,new DatasetIntervalLock(path));
        var first=runner.run("unknown",null,"calendar-target",request(),adapter,()->false);
        assertEquals(SyncRunState.IN_DOUBT,first.state());
        assertEquals(SyncRunState.IN_DOUBT,SyncRunLedger.openReadOnly(path).get("unknown").state());
        var reopened=new SyncJobRunner<ExchangeCalendar,ExchangeCalendar.Key>(new SyncRunLedger(path),new DatasetIntervalLock(path));
        var replay=reopened.run("replay",null,"calendar-target",request(),adapter,()->false);
        assertEquals("DATASET_INTERVAL_BUSY",replay.errorCode());
        verify(source,times(1)).fetch(any(),any());verify(port,times(1)).send(anyList());
        assertTrue(ExchangeCalendarCoverage.load(ledger,"calendar-target",List.of("SSE"),day).isEmpty());
    }
}
