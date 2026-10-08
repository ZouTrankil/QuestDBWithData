package com.zoutrankil.data.service;

import com.zoutrankil.data.calendar.application.ExchangeCalendarCoverage;
import com.zoutrankil.data.calendar.application.ExchangeCalendarSyncAdapter;

import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExchangeCalendarCoverageTest {
    private static final LocalDate FIRST=LocalDate.of(2026,9,1);
    private ExchangeCalendarCoverage.Interval interval(String exchange,int from,int to) {
        return new ExchangeCalendarCoverage.Interval(exchange,FIRST.plusDays(from-1),FIRST.plusDays(to-1));
    }
    @Test void checkpointsStopAtGapsAndRemainIndependentAcrossExchanges() {
        var intervals=List.of(interval("SSE",1,3),interval("SSE",5,9),interval("SZSE",1,2));
        var before=ExchangeCalendarCoverage.contiguous(intervals,List.of("SSE","SZSE"),FIRST);
        assertEquals(FIRST.plusDays(2),before.get("SSE"));
        assertEquals(FIRST.plusDays(1),before.get("SZSE"));
        var filled=new ArrayList<>(intervals);filled.add(interval("SSE",3,5));
        assertEquals(FIRST.plusDays(8),ExchangeCalendarCoverage.contiguous(filled,List.of("SSE"),FIRST).get("SSE"));
        assertTrue(ExchangeCalendarCoverage.contiguous(List.of(interval("SSE",2,9)),List.of("SSE"),FIRST).isEmpty());
    }
    @Test void failedRunsAndOtherPhysicalTargetsCannotAdvanceCoverage() throws Exception {
        var ledger=mock(SyncRunLedger.class);
        var summaries=List.of(summary("failed",SyncRunState.PARTIAL,"target"),
                summary("other",SyncRunState.VERIFIED,"other-target"),summary("accepted",SyncRunState.VERIFIED,"target"));
        when(ledger.history("data.exchange_calendar",null,100)).thenReturn(summaries);
        var request=ExchangeCalendarSyncAdapter.definition(true).freeze(null,Map.of("exchanges",List.of("SSE")),
                FIRST,FIRST.plusDays(2),FIRST);
        when(ledger.getRun("accepted")).thenReturn(new SyncRunLedger.Run("accepted",null,"data.exchange_calendar",1,
                FIRST.toString(),"target",SyncRequestIdentity.snapshotJson(request)));
        var coverage=ExchangeCalendarCoverage.load(ledger,"target",List.of("SSE","SZSE"),FIRST);
        assertEquals(Map.of("SSE",FIRST.plusDays(2)),coverage);
        verify(ledger,never()).getRun("failed");verify(ledger,never()).getRun("other");
    }
    private SyncRunLedger.RunSummary summary(String id,SyncRunState state,String target) {
        return new SyncRunLedger.RunSummary(id,null,"data.exchange_calendar",1,FIRST.toString(),target,state,1,"now");
    }
}
