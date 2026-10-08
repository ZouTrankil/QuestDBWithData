package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.policy.IndexDailyMarketUniverse;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked","rawtypes"})
class IndexJobServicesContractTest {
    private static final String ID="static-v2-"+"a".repeat(64),OTHER="static-v2-"+"b".repeat(64),CODE="000300.SH";
    private static final LocalDate DAY=LocalDate.of(2020,1,3);
    private static final Instant OBSERVED=Instant.parse("2020-01-03T01:02:03.123456Z");
    @TempDir Path temp;
    enum Family {
        MARKET(IndexDailyMarketSyncJobOwner.DEFINITION,"java_d019_index_daily_market_contract","D019"),
        BASIC(IndexDailyBasicSyncJobOwner.DEFINITION,"java_d020_index_daily_basic_contract","D020"),
        WEIGHT(IndexWeightSyncJobOwner.DEFINITION,"java_d021_index_weight_contract","D021");
        final SyncJobDefinition definition;final String table,code;
        Family(SyncJobDefinition definition,String table,String code){this.definition=definition;this.table=table;this.code=code;}
    }
    @ParameterizedTest @EnumSource(Family.class)
    void constructionOnlyReadsTheConfiguredTableName(Family f) {
        var h=new Harness(f,temp.resolve("ledger.sqlite3"));
        assertFalse(Files.exists(h.ledger));verifyNoInteractions(h.jobs,h.pages,h.names,h.writer);
        verifyNoMoreInteractions(h.target);
    }
    @ParameterizedTest @EnumSource(Family.class)
    void changedTargetFailsBeforeOpeningALedgerOrWriter(Family f) {
        var h=new Harness(f,temp.resolve("ledger.sqlite3"));h.identity(OTHER);
        assertThrows(IllegalStateException.class,()->h.run(h.plan(request(f,Mode.BACKFILL))));
        assertFalse(Files.exists(h.ledger));assertEquals(0,h.writerCalls());verifyNoInteractions(h.pages,h.writer);
    }
    @ParameterizedTest @EnumSource(Family.class)
    void freshExecutionKeepsBaselineLedgerWriterAndAdapterOrder(Family f) throws Exception {
        var h=new Harness(f,temp.resolve("ledger.sqlite3"));var phases=new ArrayList<String>();
        h.onWriter(()->{phases.add(Files.exists(h.ledger)?"writer-after-ledger":"baseline-writer-before-ledger");return h.writer;});
        try(var runners=mockConstruction(SyncJobRunner.class,(runner,context)->{
            phases.add("runner");
            doAnswer(call->{
                assertNull(call.getArgument(1));assertEquals(ID,call.getArgument(2));
                var adapter=(SyncJobRunner.Adapter)call.getArgument(4);assertSame(h.writer,adapter.port());
                assertFalse(((BooleanSupplier)call.getArgument(5)).getAsBoolean());
                return new SyncJobRunner.Result(call.getArgument(0),SyncRunState.FAILED,0,0,"test-stop");
            }).when(runner).run(anyString(),isNull(),eq(ID),any(),any(),any());
        })) {
            assertEquals("test-stop",h.run(h.plan(request(f,Mode.BACKFILL))).errorCode());
            assertEquals(1,runners.constructed().size());
        }
        assertEquals(f==Family.WEIGHT?List.of("baseline-writer-before-ledger","writer-after-ledger","runner"):List.of("writer-after-ledger","runner"),phases);
        if(f==Family.BASIC){var order=inOrder(h.writer);order.verify(h.writer).preflight();order.verify((IndexDailyBasicWriteSession)h.writer).readExistingRange(CODE);}
        else if(f==Family.WEIGHT)verify((IndexWeightWriteSession)h.writer).readExistingRange();
        else verifyNoInteractions(h.writer);
        verifyNoInteractions(h.pages,h.jobs);
    }
    @ParameterizedTest @EnumSource(Family.class)
    void resumeKeepsPriorAsBothParentAndRecoveryRunAndSkipsFreshBaseline(Family f) throws Exception {
        var h=new Harness(f,temp.resolve("ledger.sqlite3"));var request=request(f,Mode.BACKFILL);prior(h.ledger,request);
        h.rejectRangeRead();
        try(var runners=mockConstruction(SyncJobRunner.class,(runner,context)->{
            doAnswer(call->{assertEquals("prior",call.getArgument(1));assertEquals("prior",call.getArgument(2));assertEquals(request,call.getArgument(4));
                return new SyncJobRunner.Result(call.getArgument(0),SyncRunState.FAILED,0,0,"resume-stop");
            }).when(runner).resume(anyString(),eq("prior"),eq("prior"),eq(ID),any(),any(),any());
        })) {
            assertEquals("resume-stop",h.resume(h.plan(request)).errorCode());assertEquals(1,runners.constructed().size());
        }
        assertEquals(1,h.writerCalls());verify(h.writer,never()).preflight();verifyNoInteractions(h.pages,h.jobs);
    }
    @ParameterizedTest @EnumSource(value=Family.class,names={"MARKET","BASIC"})
    void groupChildKeepsTheExistingParentAsPriorResumeContract(Family f) throws Exception {
        var h=new Harness(f,temp.resolve("ledger.sqlite3"));var request=request(f,Mode.BACKFILL);h.rejectRangeRead();
        try(var runners=mockConstruction(SyncJobRunner.class,(runner,context)->{
            when(runner.resume(eq("child"),eq("parent"),eq("parent"),eq(ID),eq(request),any(),any())).thenReturn(new SyncJobRunner.Result("child",SyncRunState.FAILED,0,0,"group-stop"));
        })) {
            var result=f==Family.MARKET?((IndexDailyMarketJobService)h.service).runAsGroupChild("child","parent",ID,request):((IndexDailyBasicJobService)h.service).runAsGroupChild("child","parent",ID,request);
            assertEquals("group-stop",result.errorCode());verify(runners.constructed().getFirst(),never()).run(any(),any(),any(),any(),any(),any());
        }
        verify(h.writer,never()).preflight();verifyNoInteractions(h.pages,h.jobs);
    }
    @ParameterizedTest @EnumSource(Family.class)
    void cancellationChecksInterruptionThenItsOwnLedgerAndKeepsFamilyFailureCause(Family f) throws Exception {
        var h=new Harness(f,temp.resolve("ledger.sqlite3"));var supplier=new AtomicReference<BooleanSupplier>();var runId=new AtomicReference<String>();
        // Constructor mocking suppresses schema creation; the real interval-lock store still requires that schema.
        new SyncRunLedger(h.ledger);
        try(var ledgers=mockConstruction(SyncRunLedger.class);var runners=mockConstruction(SyncJobRunner.class,(runner,context)->{
            doAnswer(call->{runId.set(call.getArgument(0));supplier.set(call.getArgument(5));return new SyncJobRunner.Result(call.getArgument(0),SyncRunState.FAILED,0,0,"captured");})
                .when(runner).run(anyString(),isNull(),eq(ID),any(),any(),any());
        })) {
            h.run(h.plan(request(f,Mode.BACKFILL)));assertEquals(1,ledgers.constructed().size());var ledger=ledgers.constructed().getFirst();
            assertFalse(supplier.get().getAsBoolean());verify(ledger).cancellationRequested(runId.get());
            when(ledger.cancellationRequested(runId.get())).thenReturn(true);assertTrue(supplier.get().getAsBoolean());
            var sql=new SQLException("offline failure");when(ledger.cancellationRequested(runId.get())).thenThrow(sql);
            var failure=assertThrows(IllegalStateException.class,()->supplier.get().getAsBoolean());
            assertEquals("Cannot read "+f.code+" cancellation state",failure.getMessage());assertSame(sql,failure.getCause());
            clearInvocations(ledger);Thread.currentThread().interrupt();
            try{assertTrue(supplier.get().getAsBoolean());assertTrue(Thread.currentThread().isInterrupted());verifyNoInteractions(ledger);}finally{Thread.interrupted();}
        }
    }
    @Test void basicBaselineFailureHappensAfterLedgerAndBeforeSourceOrRunner() {
        var h=new Harness(Family.BASIC,temp.resolve("ledger.sqlite3"));when(((IndexDailyBasicWriteSession)h.writer).readExistingRange(CODE)).thenReturn(new IndexDailyBasicTargetRange(DAY,DAY));
        try(var runners=mockConstruction(SyncJobRunner.class)) {
            assertEquals("D020 physical target range changed after frozen plan; re-plan against current contents",assertThrows(IllegalStateException.class,()->h.run(h.plan(request(Family.BASIC,Mode.BACKFILL)))).getMessage());
            assertTrue(Files.exists(h.ledger));assertTrue(runners.constructed().isEmpty());
        }
        verify(h.writer).preflight();verifyNoInteractions(h.pages,h.jobs);
    }
    @Test void weightBaselineFailureHappensBeforeLedgerAndItsExecutionWriter() {
        var h=new Harness(Family.WEIGHT,temp.resolve("ledger.sqlite3"));when(((IndexWeightWriteSession)h.writer).readExistingRange()).thenReturn(new IndexWeightTargetRange(DAY,DAY));
        assertEquals("D021 physical target date range changed after plan; make a fresh plan",assertThrows(IllegalStateException.class,()->h.run(h.plan(request(Family.WEIGHT,Mode.BACKFILL)))).getMessage());
        assertFalse(Files.exists(h.ledger));assertEquals(1,h.writerCalls());verify(h.writer,never()).preflight();verifyNoInteractions(h.pages,h.jobs);
    }
    @Test void weightFrozenNotDuePlanRejectsBeforeIdentityAndWriter() {
        var h=new Harness(Family.WEIGHT,temp.resolve("ledger.sqlite3"));var request=request(Family.WEIGHT,Mode.SNAPSHOT);
        var plan=new IndexWeightJobService.Plan(request,ID,null,null,DAY,DAY.plusDays(7),true);
        assertThrows(IllegalStateException.class,()->h.run(plan));verifyNoInteractions(h.target,h.names,h.writer);assertFalse(Files.exists(h.ledger));
    }
    @Test void weightRefreshRecheckRunsAfterBaselineAndBeforeTheSecondWriter() throws Exception {
        var h=new Harness(Family.WEIGHT,temp.resolve("ledger.sqlite3"));Files.writeString(h.ledger,"not a database");
        try(var schema=mockStatic(SqliteLedgerSchema.class);var coverage=mockStatic(IndexWeightCoverage.class)) {
            schema.when(()->SqliteLedgerSchema.hasRunHistoryTable(h.ledger)).thenReturn(true);
            coverage.when(()->IndexWeightCoverage.latestVerifiedSnapshot(h.ledger,ID,ID)).thenReturn(Optional.of(new IndexWeightCoverage.Snapshot("prior",OBSERVED,DAY,ID,1)));
            assertEquals("D021 refresh became not due after planning; make a fresh plan",assertThrows(IllegalStateException.class,()->h.run(h.plan(request(Family.WEIGHT,Mode.SNAPSHOT)))).getMessage());
            assertEquals("not a database",Files.readString(h.ledger));assertEquals(1,h.writerCalls());verify((IndexWeightWriteSession)h.writer).readExistingRange();
        }
        verifyNoInteractions(h.pages,h.jobs);
    }
    private static FrozenRequest request(Family f,Mode mode) {
        var p=new LinkedHashMap<String,Object>();p.put("targetId",ID);
        if(f!=Family.WEIGHT)p.put("tsCode",CODE);
        if(f==Family.MARKET)p.put("route",IndexDailyMarketUniverse.resolve(CODE).route().name());
        if(f!=Family.BASIC)p.put("observedAt",OBSERVED.toString());
        if(f==Family.WEIGHT){p.put("stockDetailTargetId",ID);p.put("force",false);}
        return f.definition.freeze(mode,p,mode==Mode.SNAPSHOT?null:DAY,mode==Mode.SNAPSHOT?null:DAY,DAY);
    }
    private static void prior(Path path,FrozenRequest request)throws Exception{var ledger=new SyncRunLedger(path);ledger.createRun("prior",null,ID,request);ledger.transition("prior",0,SyncRunState.FAILED,"{}");}
    private static final class Harness {
        final Family family;final Path ledger;final Object target,service;
        final SyncJobRegistry jobs=mock(SyncJobRegistry.class);final TusharePageService pages=mock(TusharePageService.class);
        final IndexWeightNameResolver names=mock(IndexWeightNameResolver.class);final VerifiedWriteSession writer;
        Harness(Family family,Path ledger) {
            this.family=family;this.ledger=ledger;when(names.targetId()).thenReturn(ID);
            switch(family) {
                case MARKET->{var t=mock(IndexDailyMarketTarget.class);var w=mock(IndexDailyMarketWriteSession.class);target=t;writer=w;
                    when(t.tableName()).thenReturn(family.table);when(t.targetId()).thenReturn(ID);when(t.newWriter(ID)).thenReturn(w);when(w.readExistingRange(CODE)).thenReturn(new IndexDailyMarketTargetRange(null,null));
                    service=new IndexDailyMarketJobService(jobs,pages,t,ledger);}
                case BASIC->{var t=mock(IndexDailyBasicTarget.class);var w=mock(IndexDailyBasicWriteSession.class);target=t;writer=w;
                    when(t.tableName()).thenReturn(family.table);when(t.targetId()).thenReturn(ID);when(t.newWriter(ID)).thenReturn(w);when(w.readExistingRange(CODE)).thenReturn(new IndexDailyBasicTargetRange(null,null));
                    service=new IndexDailyBasicJobService(jobs,pages,t,ledger);}
                case WEIGHT->{var t=mock(IndexWeightTarget.class);var w=mock(IndexWeightWriteSession.class);target=t;writer=w;
                    when(t.tableName()).thenReturn(family.table);when(t.targetId()).thenReturn(ID);when(t.newWriter(ID)).thenReturn(w);when(w.readExistingRange()).thenReturn(new IndexWeightTargetRange(null,null));
                    service=new IndexWeightJobService(jobs,pages,t,names,ledger);}
                default->throw new AssertionError();
            }
            clearInvocations(target,names,writer);
        }
        void identity(String id){switch(family){case MARKET->when(((IndexDailyMarketTarget)target).targetId()).thenReturn(id);case BASIC->when(((IndexDailyBasicTarget)target).targetId()).thenReturn(id);case WEIGHT->when(((IndexWeightTarget)target).targetId()).thenReturn(id);}}
        void onWriter(java.util.function.Supplier<VerifiedWriteSession> next){switch(family){case MARKET->when(((IndexDailyMarketTarget)target).newWriter(ID)).thenAnswer(i->next.get());case BASIC->when(((IndexDailyBasicTarget)target).newWriter(ID)).thenAnswer(i->next.get());case WEIGHT->when(((IndexWeightTarget)target).newWriter(ID)).thenAnswer(i->next.get());}}
        int writerCalls(){return (int)mockingDetails(target).getInvocations().stream().filter(i->i.getMethod().getName().equals("newWriter")).count();}
        void rejectRangeRead(){switch(family){case MARKET->when(((IndexDailyMarketWriteSession)writer).readExistingRange(anyString())).thenThrow(new AssertionError("no fresh baseline"));case BASIC->when(((IndexDailyBasicWriteSession)writer).readExistingRange(anyString())).thenThrow(new AssertionError("no fresh baseline"));case WEIGHT->when(((IndexWeightWriteSession)writer).readExistingRange()).thenThrow(new AssertionError("no fresh baseline"));}}
        Object plan(FrozenRequest request){return switch(family){
            case MARKET->new IndexDailyMarketJobService.Plan(request,ID,CODE,IndexDailyMarketUniverse.resolve(CODE).route(),null,null,new IndexDailyMarketTargetRange(null,null),false);
            case BASIC->new IndexDailyBasicJobService.Plan(request,ID,CODE,null,null,new IndexDailyBasicTargetRange(null,null),false);
            case WEIGHT->new IndexWeightJobService.Plan(request,ID,null,null,null,null,false);};}
        SyncJobRunner.Result run(Object plan)throws Exception{return switch(family){case MARKET->((IndexDailyMarketJobService)service).run((IndexDailyMarketJobService.Plan)plan);case BASIC->((IndexDailyBasicJobService)service).run((IndexDailyBasicJobService.Plan)plan);case WEIGHT->((IndexWeightJobService)service).run((IndexWeightJobService.Plan)plan);};}
        SyncJobRunner.Result resume(Object plan)throws Exception{return switch(family){case MARKET->((IndexDailyMarketJobService)service).resume((IndexDailyMarketJobService.Plan)plan,"prior");case BASIC->((IndexDailyBasicJobService)service).resume((IndexDailyBasicJobService.Plan)plan,"prior");case WEIGHT->((IndexWeightJobService)service).resume((IndexWeightJobService.Plan)plan,"prior");};}
    }
}
