package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.calendar.port.SseCalendarWindowReadPort;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.*;
import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoneyflowHsgtAdmissionContractTest {
    @TempDir Path temp;
    static final LocalDate DAY=MoneyflowHsgtPublicationContractTest.DAY;
    static final String ID=MoneyflowHsgtPublicationContractTest.LOGICAL;
    @ParameterizedTest @ValueSource(strings={"missing","duplicate","openMissing"})
    void formalCalendarMustBeCompleteBeforeAnySourcePageIsConsumed(String fault)throws Exception {
        var calendars=mock(SseCalendarWindowReadPort.class);
        doAnswer(call->{BiConsumer<LocalDate,Integer> consumer=call.getArgument(2);
            if(!fault.equals("missing"))consumer.accept(DAY,fault.equals("openMissing")?1:0);
            if(fault.equals("duplicate"))consumer.accept(DAY,0);return null;
        }).when(calendars).readSseDates(eq(DAY),eq(DAY),any());
        var f=new Fixture(temp,"moneyflow_hsgt",true,calendars);
        var failure=assertThrows(IllegalStateException.class,()->f.adapter.fetch(f.request,p->fail("Unproven formal coverage cannot write"),()->false));
        assertTrue(failure.getMessage().contains(switch(fault){case "missing"->"Missing SSE";case "duplicate"->"Duplicate SSE";default->"SOURCE_INCOMPLETE";}));
        verify(f.port,never()).readWindow(anyString(),any(),any());verify(f.staging,never()).verify(any(),any(),any());
        verify(f.port).useStage(f.prepared.stage(),f.prepared.stagePhysicalTarget());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void formalCoveredOpenDateAndCertifiedClosedEmptyDayReachConsumer(boolean empty)throws Exception {
        var calendars=mock(SseCalendarWindowReadPort.class);
        doAnswer(call->{((BiConsumer<LocalDate,Integer>)call.getArgument(2)).accept(DAY,empty?0:1);return null;}).when(calendars).readSseDates(eq(DAY),eq(DAY),any());
        var f=new Fixture(temp,"moneyflow_hsgt",empty,calendars);
        assertThrows(ReachedConsumer.class,()->f.adapter.fetch(f.request,p->{assertEquals(empty?0:1,p.rows().size());throw new ReachedConsumer();},()->false));
        verify(calendars).readSseDates(eq(DAY),eq(DAY),any());
    }
    @Test void isolatedCollectionKeepsItsOriginalCalendarIndependence()throws Exception {
        var calendars=mock(SseCalendarWindowReadPort.class);var f=new Fixture(temp,"java_d027_moneyflow_hsgt_contract",true,calendars);
        assertThrows(ReachedConsumer.class,()->f.adapter.fetch(f.request,p->{throw new ReachedConsumer();},()->false));verifyNoInteractions(calendars);
    }
    @ParameterizedTest @ValueSource(strings={"INCREMENTAL","tooWide","missingFrom"})
    void formalPlanningRejectsUncertifiedModeOrRangeBeforeTargetIo(String kind)throws Exception {
        var target=mock(MoneyflowHsgtTarget.class);when(target.tableName()).thenReturn("moneyflow_hsgt");
        var pages=mock(TusharePageService.class);var calendars=mock(SseCalendarWindowReadPort.class);
        var job=new MoneyflowHsgtJobService(mock(SyncJobRegistry.class),pages,target,calendars,temp.resolve("ledger.sqlite"));clearInvocations(target);
        assertThrows(IllegalArgumentException.class,()->job.plan(kind.equals("INCREMENTAL")?SyncJobDefinition.Mode.INCREMENTAL:SyncJobDefinition.Mode.BACKFILL,
                kind.equals("missingFrom")?null:kind.equals("tooWide")?DAY.minusDays(31):DAY,DAY,DAY));
        verifyNoInteractions(target,pages,calendars);assertFalse(Files.exists(temp.resolve("ledger.sqlite")));
    }
    @Test void sourceRangeCapFailsBeforeNetworkAndInterruptedSourceHasNoCompleteReceipt()throws Exception {
        var pages=new MoneyflowHsgtPublicationContractTest.Pages(false);var source=new MoneyflowHsgtSource(pages,temp);
        assertThrows(IllegalArgumentException.class,()->source.fetch(DAY.minusDays(31),DAY,()->false));assertEquals(0,pages.calls);
        assertThrows(PageExecutor.Incomplete.class,()->source.fetch(DAY,DAY,()->true));assertEquals(0,pages.calls);
        if(Files.isDirectory(temp))try(var files=Files.list(temp)){for(Path file:files.toList())assertFalse(JobDefinitionJson.mapper().readTree(file.toFile()).path("sourceComplete").asBoolean());}
    }
    static final class ReachedConsumer extends RuntimeException {}
    static final class Fixture {
        final MoneyflowHsgtWriteSession port=mock(MoneyflowHsgtWriteSession.class);final MoneyflowHsgtStaging staging=mock(MoneyflowHsgtStaging.class);
        final MoneyflowHsgtSyncAdapter adapter;final SyncJobDefinition.FrozenRequest request;final Prepared prepared;
        Fixture(Path root,String table,boolean empty,SseCalendarWindowReadPort calendars)throws Exception {
            var tables=mock(MoneyflowHsgtStagingPort.class);Path evidence=root.resolve("sync-evidence").resolve("admission-run");
            var snapshot=new Snapshot(new Identity(1,"old",0),List.of(),"a".repeat(64),0);
            prepared=new Prepared(table,"java_d027_moneyflow_hsgt_stage_"+"b".repeat(32),ID,ID,MoneyflowHsgtPublicationContractTest.physical(2),"admission-run","c".repeat(64),DAY,DAY,snapshot,snapshot,evidence);
            request=MoneyflowHsgtSyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.BACKFILL,Map.of("targetId",ID,"physicalTargetId",ID,"targetRowsBefore",0,"targetFingerprint","a".repeat(64)),DAY,DAY,DAY);
            when(port.formalTable()).thenReturn(table);when(staging.prepare(anyString(),anyString(),anyString(),anyString(),any(),any(),any())).thenReturn(prepared);
            adapter=new MoneyflowHsgtSyncAdapter(new MoneyflowHsgtSource(new MoneyflowHsgtPublicationContractTest.Pages(empty),evidence.resolve("source")),port,staging,
                    new MoneyflowHsgtPublication(tables,root.resolve("ledger.sqlite")),evidence,tables,calendars);
        }
    }
}
