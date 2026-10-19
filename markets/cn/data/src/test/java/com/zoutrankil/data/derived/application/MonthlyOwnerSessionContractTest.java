package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite ownership and proof transitions; every factory call supplies a different sender session. */
class MonthlyOwnerSessionContractTest {
    @TempDir Path temp;
    static final LocalDate JUNE=LocalDate.of(2026,6,1),JULY=LocalDate.of(2026,7,1),LOGICAL=LocalDate.of(2026,10,6);

    @Test void equityFreshWritersDoNotReplaceTheOriginalUncertainRunSession()throws Exception {
        var source=new EquityStyleMonthlySourceTest.MutableSource();var writers=new ArrayList<EquityStyleMonthlyMaterializeAdapterTest.MemoryPort>();
        var path=temp.resolve("equity.sqlite");var owner=new EquityStyleMonthlyJobService(path,source,()->{var p=new EquityStyleMonthlyMaterializeAdapterTest.MemoryPort();writers.add(p);p.interruptUnknown=writers.size()==2;return p;});
        var plan=owner.plan(JUNE,JULY,LOGICAL,null);EquityStyleMonthlyJobService.MaterializationResult result;
        try{result=owner.run(plan);}finally{Thread.interrupted();}
        assertEquals(SyncRunState.IN_DOUBT,result.result().state());assertEquals(2,writers.size());assertNotSame(writers.get(0),writers.get(1));
        var original=writers.get(1);assertEquals(1,original.sends);assertThrows(IllegalStateException.class,()->owner.reconcile(result.result().runId()));
        assertEquals(EquityStyleMonthlyMaterializeAdapterTest.TARGET,owner.targetId());assertEquals(3,writers.size());assertNotSame(original,writers.getLast());
        writers.getLast().stopped=true;
        assertThrows(IllegalStateException.class,()->owner.reconcile(result.result().runId()));
        assertNotNull(new DatasetIntervalLock(path).findOwned(result.result().runId(),DatasetIntervalLock.Scope.allDates("equity_style_monthly")));
        original.stopped=true;assertEquals(SyncRunState.VERIFIED,owner.reconcile(result.result().runId()).state());assertEquals(1,original.sends);
        assertEquals(SyncRunState.VERIFIED,SyncRunLedger.openReadOnly(path).get(result.result().runId()).state());
        assertNull(new DatasetIntervalLock(path).findOwned(result.result().runId(),DatasetIntervalLock.Scope.allDates("equity_style_monthly")));
    }

    @Test void macroFreshWritersDoNotReplaceTheOriginalUncertainRunSession()throws Exception {
        var source=new MacroCoreMonthlySourceTest.MutableSource();var writers=new ArrayList<MacroCoreMonthlyMaterializeAdapterTest.MemoryPort>();
        var path=temp.resolve("macro.sqlite");var owner=new MacroCoreMonthlyJobService(path,source,()->{var p=new MacroCoreMonthlyMaterializeAdapterTest.MemoryPort();writers.add(p);p.interruptUnknown=writers.size()==2;return p;});
        var plan=owner.plan(JUNE,JULY,LOGICAL,null);MacroCoreMonthlyJobService.MaterializationResult result;
        try{result=owner.run(plan);}finally{Thread.interrupted();}
        assertEquals(SyncRunState.IN_DOUBT,result.result().state());assertEquals(2,writers.size());assertNotSame(writers.get(0),writers.get(1));
        var original=writers.get(1);assertEquals(1,original.sends);assertThrows(IllegalStateException.class,()->owner.reconcile(result.result().runId()));
        assertEquals(MacroCoreMonthlyMaterializeAdapterTest.TARGET,owner.targetId());assertEquals(3,writers.size());assertNotSame(original,writers.getLast());
        writers.getLast().stopped=true;
        assertThrows(IllegalStateException.class,()->owner.reconcile(result.result().runId()));
        assertNotNull(new DatasetIntervalLock(path).findOwned(result.result().runId(),DatasetIntervalLock.Scope.allDates("macro_core_monthly")));
        original.stopped=true;assertEquals(SyncRunState.VERIFIED,owner.reconcile(result.result().runId()).state());assertEquals(1,original.sends);
        assertEquals(SyncRunState.VERIFIED,SyncRunLedger.openReadOnly(path).get(result.result().runId()).state());
        assertNull(new DatasetIntervalLock(path).findOwned(result.result().runId(),DatasetIntervalLock.Scope.allDates("macro_core_monthly")));
    }
}
