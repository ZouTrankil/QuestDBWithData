package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.RegimeFeaturesMonitorDailyRow;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegimeFeaturesMonitorDailyOwnerContractTest {
    private static final String TABLE="regime_features_monitor_daily",PIN="a".repeat(64);
    private static final LocalDate DAY=LocalDate.of(2026,9,17);
    @TempDir Path temp;

    @Test void constructionAndInvalidRequestsKeepStorageAndLedgerUntouched()throws Exception {
        var reads=mock(RegimeFeaturesMonitorDailySourceReadPort.class);var target=mock(RegimeFeaturesMonitorDailyTarget.class);
        Path ledger=temp.resolve("ledger.sqlite3");Function<NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow>,NativeDailyWindowPublication<RegimeFeaturesMonitorDailyRow>> publications=writer->{throw new AssertionError("No publication construction expected");};
        var owner=new RegimeFeaturesMonitorDailyJobService(reads,target,publications,ledger.toString(),TABLE);
        assertEquals(TABLE,owner.datasetId());assertEquals(21,owner.definition().columns().size());
        assertTrue(owner.definition().capabilities().contains(DatasetDefinition.Capability.WAL_REPLACE));
        assertFalse(owner.definition().dependencies().contains("cn_bond_yield_curve"));
        assertThrows(IllegalArgumentException.class,()->owner.plan(DAY,DAY,DAY,SyncJobDefinition.Mode.INCREMENTAL));
        assertThrows(IllegalArgumentException.class,()->owner.plan(DAY,DAY.plusDays(366),DAY.plusDays(366)));
        assertFalse(Files.exists(ledger));verifyNoInteractions(reads,target);
    }

    @Test void planRetainsPendingThenFreshSnapshotThenSourcePinAndWarmupOrder()throws Exception {
        var events=new ArrayList<String>();var reads=mock(RegimeFeaturesMonitorDailySourceReadPort.class);var target=mock(RegimeFeaturesMonitorDailyTarget.class);
        var pending=writer();var snapshotWriter=writer();var tables=mock(NativeDailyPublicationTables.class);Path ledger=temp.resolve("ledger.sqlite3");
        var before=new NativeDailyWindowSnapshot<RegimeFeaturesMonitorDailyRow>("target",11,"formal~11",true,List.of(),"b".repeat(64));
        when(target.writer(TABLE)).thenAnswer(call->{events.add("writer");return events.size()==1?pending:snapshotWriter;});
        when(snapshotWriter.formalSnapshot()).thenAnswer(call->{events.add("snapshot");return before;});
        when(reads.sourcePin()).thenAnswer(call->{events.add("source-pin");return PIN;});
        doAnswer(call->{events.add("pending");return null;}).when(tables).requireNoPendingPublication(ledger.toAbsolutePath().normalize(),TABLE);
        var owner=new RegimeFeaturesMonitorDailyJobService(reads,target,session->{events.add("publication");assertSame(pending,session);return new NativeDailyWindowPublication<>(tables,ledger,TABLE,session);},ledger.toString(),TABLE);
        var plan=owner.plan(DAY,DAY,DAY);
        assertEquals(List.of("writer","publication","pending","writer","snapshot","source-pin"),events);
        assertSame(before,plan.targetBefore());assertEquals(DAY.minusDays(20),plan.warmupFrom());assertEquals(PIN,plan.sourcePin());
        assertTrue(plan.dependencies().contains("cn_bond_yield_curve"));assertEquals(366,plan.request().definition().budget().maxRows());assertFalse(Files.exists(ledger));
        verify(pending,never()).formalSnapshot();
    }

    @Test void runKeepsWriterBeforeLedgerAndUsesThatWriterForFrozenTargetCheck()throws Exception {
        var reads=mock(RegimeFeaturesMonitorDailySourceReadPort.class);var target=mock(RegimeFeaturesMonitorDailyTarget.class);var tables=mock(NativeDailyPublicationTables.class);
        Path ledger=temp.resolve("ledger.sqlite3");var actual=writer();var pending=writer();var created=new ArrayList<Boolean>();
        when(target.writer(TABLE)).thenAnswer(call->{created.add(Files.isRegularFile(ledger));return created.size()==1?actual:pending;});
        when(reads.sourcePin()).thenReturn("b".repeat(64));
        var before=new NativeDailyWindowSnapshot<RegimeFeaturesMonitorDailyRow>("target",11,"formal~11",true,List.of(),"c".repeat(64));
        var request=RegimeFeaturesMonitorDailyJobService.jobDefinition().freeze(SyncJobDefinition.Mode.MATERIALIZE,Map.of("target_id","target","source_pin",PIN,"target_hash",before.fingerprint(),"warmup_from",DAY.minusDays(20)),DAY,DAY,DAY);
        var plan=new RegimeFeaturesMonitorDailyJobService.Plan(request,"target",DAY.minusDays(20),RegimeFeaturesMonitorDailyJobService.SOURCES,PIN,before);
        var owner=new RegimeFeaturesMonitorDailyJobService(reads,target,session->{assertSame(pending,session);return new NativeDailyWindowPublication<>(tables,ledger,TABLE,session);},ledger.toString(),TABLE);
        var result=owner.run(plan);assertEquals(SyncRunState.FAILED,result.result().state());assertEquals(List.of(false,true),created);
        verify(actual).requireSame(before);verify(pending,never()).requireSame(any());verify(actual,never()).send(anyList());
        verify(reads,never()).calendar(any(),any(),any());assertNull(result.evidence());
    }

    @SuppressWarnings("unchecked") private static NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> writer(){return mock(NativeDailyWindowSession.class);}
}
