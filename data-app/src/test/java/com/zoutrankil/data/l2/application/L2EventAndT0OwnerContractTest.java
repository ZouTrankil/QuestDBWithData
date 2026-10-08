package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.port.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;
import static com.zoutrankil.data.l2.application.L2EventAndT0Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class L2EventAndT0OwnerContractTest {
    @TempDir Path directory;

    @ParameterizedTest @EnumSource(Family.class)
    void planHasOriginalIoOrderAndActualBudgets(Family family) throws Exception {
        var f=new Fixture(family,directory);assertFalse(Files.exists(f.ledgerPath));assertTrue(f.events.isEmpty());
        Object plan=f.plan(DAY,SyncJobDefinition.Mode.BACKFILL);
        assertTrue(Files.isRegularFile(f.ledgerPath));assertEquals(List.of("identity","writer","preflight","root","calendar","inspect:300000:10000","rowCount","latest","root"),f.events);
        assertEquals(0,plan.getClass().getMethod("targetDatesChecked").invoke(plan));assertEquals(1,f.sessions.size());
        assertEquals(300000,((SyncJobDefinition.FrozenRequest)plan.getClass().getMethod("request").invoke(plan)).definition().budget().maxRows());
    }

    @ParameterizedTest @EnumSource(Family.class)
    void invalidPlanAndChangedIdentityDoNotCreateLedgerOrWriter(Family family) throws Exception {
        var f=new Fixture(family,directory);
        assertThrows(IllegalArgumentException.class,()->f.plan(DAY.minusDays(31),SyncJobDefinition.Mode.BACKFILL));assertTrue(f.events.isEmpty());assertFalse(Files.exists(f.ledgerPath));
        Object plan=f.manualPlan();f.targetIdentity="changed";
        assertThrows(IllegalStateException.class,()->f.run(plan));assertEquals(List.of("identity"),f.events);assertFalse(Files.exists(f.ledgerPath));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void realRunnerVerifiesThenResumeRevalidatesWithoutSendingAndKeepsParent(Family family) throws Exception {
        var f=new Fixture(family,directory);Object plan=f.manualPlan();
        var first=f.run(plan);assertEquals(SyncRunState.VERIFIED,first.state());assertEquals(1,first.verifiedRows());assertEquals(1,f.sends);
        var ledger=new SyncRunLedger(f.ledgerPath);assertEquals(SyncRunState.VERIFIED,ledger.get(first.runId()).state());assertNull(ledger.getRun(first.runId()).parentRunId());
        var second=f.resume(plan,first.runId());assertEquals(SyncRunState.VERIFIED,second.state());assertEquals(1,second.reusedRows());assertEquals(1,f.sends);
        assertEquals(first.runId(),ledger.getRun(second.runId()).parentRunId());assertEquals(2,f.streams);assertEquals(2,f.sessions.size());assertNotSame(f.sessions.get(0),f.sessions.get(1));
        assertEquals(2,f.callbacks);assertTrue(f.events.indexOf("writer")<f.events.indexOf("root"));
        assertNull(new DatasetIntervalLock(f.ledgerPath).findOwned(first.runId(),new DatasetIntervalLock.Scope(family.dataset,DAY,DAY)));
        assertNull(new DatasetIntervalLock(f.ledgerPath).findOwned(second.runId(),new DatasetIntervalLock.Scope(family.dataset,DAY,DAY)));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void unknownAckWithMatchingReadbackReconcilesAndNeverResendsOnResume(Family family) throws Exception {
        var f=new Fixture(family,directory);f.unknownAck=true;Object plan=f.manualPlan();
        var first=f.run(plan);assertEquals(SyncRunState.VERIFIED,first.state());assertEquals(1,f.sends);
        var next=f.resume(plan,first.runId());assertEquals(SyncRunState.VERIFIED,next.state());assertEquals(1,next.reusedRows());assertEquals(1,f.sends);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void sourceRootChangeRejectsAfterRealRunCreationBeforePreflightOrStream(Family family) throws Exception {
        var f=new Fixture(family,directory);Object plan=f.manualPlan();f.rootIdentity="d".repeat(64);
        var result=f.run(plan);assertEquals(SyncRunState.FAILED,result.state());assertEquals("IllegalStateException",result.errorCode());assertEquals(List.of("identity","writer","root"),f.events);
        assertEquals(SyncRunState.FAILED,new SyncRunLedger(f.ledgerPath).get(result.runId()).state());assertEquals(0,f.streams);assertEquals(0,f.sends);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void actualCancellationUsesCurrentRunLedgerAndDoesNotSend(Family family) throws Exception {
        var f=new Fixture(family,directory);f.cancelDuringFetch=true;var result=f.run(f.manualPlan());
        assertEquals(SyncRunState.CANCELLED,result.state());assertEquals(0,f.sends);assertEquals(0,f.callbacks);
        assertTrue(new SyncRunLedger(f.ledgerPath).cancellationRequested(result.runId()));
        assertNull(new DatasetIntervalLock(f.ledgerPath).findOwned(result.runId(),new DatasetIntervalLock.Scope(family.dataset,DAY,DAY)));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void threadCancellationPrecedesSourceAndStillUsesFreshSession(Family family) throws Exception {
        var f=new Fixture(family,directory);Object plan=f.manualPlan();Thread.currentThread().interrupt();
        try {var result=f.run(plan);assertEquals(SyncRunState.CANCELLED,result.state());assertEquals(List.of("identity","writer"),f.events);assertEquals(0,f.streams);assertTrue(Thread.currentThread().isInterrupted());}
        finally {Thread.interrupted();}
    }

    @ParameterizedTest @EnumSource(Family.class)
    void verifiedCheckpointControlsThreeDayOverlapAndFrontierException(Family family) throws Exception {
        var f=new Fixture(family,directory);assertEquals(SyncRunState.VERIFIED,f.run(f.manualPlan()).state());f.events.clear();
        Object plan=f.plan(DAY.minusDays(7),SyncJobDefinition.Mode.INCREMENTAL);
        var request=(SyncJobDefinition.FrozenRequest)plan.getClass().getMethod("request").invoke(plan);
        assertEquals(DAY.minusDays(2),request.from());assertEquals(DAY,plan.getClass().getMethod("verifiedThrough").invoke(plan));
        f.latest=DAY.plusDays(1);
        assertThrows(IllegalStateException.class,()->f.plan(DAY,SyncJobDefinition.Mode.BACKFILL));
        assertDoesNotThrow(()->f.plan(DAY,SyncJobDefinition.Mode.RECONCILE));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void preparedCertificationRequiresExactFullRowsAndSeparateTenThousandBudget(Family family) throws Exception {
        var f=new Fixture(family,directory);
        assertDoesNotThrow(()->f.prepared(List.of(f.row)));
        assertEquals(List.of("calendar","inspect:10000:10000","stream:10000:10000"),f.events);assertFalse(Files.exists(f.ledgerPath));assertTrue(f.sessions.isEmpty());
        f.events.clear();assertThrows(IllegalArgumentException.class,()->f.prepared(List.of(f.row,f.row)));assertTrue(f.events.isEmpty());
        assertThrows(IllegalArgumentException.class,()->f.prepared(Collections.nCopies(10001,f.row)));assertTrue(f.events.isEmpty());
        var changed=family.input();changed.put("board","DIFFERENT");Object changedRow=family.row(changed);
        assertThrows(IllegalArgumentException.class,()->f.prepared(List.of(changedRow)));assertTrue(f.sessions.isEmpty());
        f.emitRows=List.of();assertThrows(IllegalArgumentException.class,()->f.prepared(List.of(f.row)));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void preflightFailureCreatesNoLedgerWhenPlanning(Family family) throws Exception {
        var f=new Fixture(family,directory);f.preflightFailure=new IllegalStateException("physical-rejection");
        assertSame(f.preflightFailure,assertThrows(IllegalStateException.class,()->f.plan(DAY,SyncJobDefinition.Mode.BACKFILL)));assertFalse(Files.exists(f.ledgerPath));assertEquals(List.of("identity","writer","preflight"),f.events);
    }

    private static final class Fixture {
        final Family family;final Path ledgerPath;final Object owner;final Object row;
        final List<String> events=new ArrayList<>();final List<Object> sessions=new ArrayList<>();final Map<Object,Object> physical=new LinkedHashMap<>();
        List<Object> emitRows;String targetIdentity="questdb-contract",rootIdentity=ROOT;LocalDate latest;RuntimeException preflightFailure;
        boolean unknownAck,cancelDuringFetch;int sends,streams,callbacks;
        @SuppressWarnings({"rawtypes","unchecked"}) Fixture(Family family,Path directory) throws Exception {
            this.family=family;ledgerPath=directory.resolve(family.code+".sqlite");row=family.row(family.input());emitRows=List.of(row);
            ExchangeCalendarReadPort calendar=query->{events.add("calendar");assertEquals(Map.of("exchange","SSE"),query.equalities());var dates=((LocalDate)query.fromInclusive()).datesUntil((LocalDate)query.toExclusive()).map(day->new ExchangeCalendar("SSE",day,day.equals(DAY),null)).toList();return new DatasetReadPage<>("exchange_calendar",1,null,Instant.EPOCH,dates,null);};
            Answer sourceAnswer=invocation->{
                switch(invocation.getMethod().getName()) {
                    case "sourceRootIdentity": events.add("root");return rootIdentity;
                    case "inspect": events.add("inspect:"+invocation.getArgument(3)+":"+invocation.getArgument(4));assertTrue(Files.exists(ledgerPath)||sessions.isEmpty());return inspection(invocation.getArgument(0),invocation.getArgument(1));
                    case "stream": {
                        streams++;events.add("stream:"+invocation.getArgument(2)+":"+invocation.getArgument(3));
                        var consumer=(SyncJobRunner.PageConsumer<Object>)invocation.getArgument(4);var cancelled=(BooleanSupplier)invocation.getArgument(5);
                        if(cancelDuringFetch){var ledger=new SyncRunLedger(ledgerPath);var active=ledger.history(family.definition().jobId(),null,1000).stream().filter(run->run.state()==SyncRunState.RUNNING).findFirst().orElseThrow();assertTrue(ledger.requestCancellation(active.id()));assertTrue(cancelled.getAsBoolean());throw new CancellationException("source stopped");}
                        if(!emitRows.isEmpty()){consumer.accept(new SyncJobRunner.Page<>(emitRows,family.pageFingerprint(emitRows),"{\"syntheticReceipt\":true}","20260921:1:0"));callbacks++;}
                        return new SyncJobRunner.SourceCompletion(emitRows.isEmpty()?0:1,emitRows.size(),true,"{\"complete\":true}");
                    }
                    default:return RETURNS_DEFAULTS.answer(invocation);
                }
            };
            Object source=mock(family.sourceType(),sourceAnswer);
            Class<?> targetClass=family==Family.EVENT?L2EventResponseFeaturesTarget.class:L2T0TrainingLabelsTarget.class;
            Object target=mock(targetClass,invocation->{switch(invocation.getMethod().getName()){
                case "targetId":events.add("identity");return targetIdentity;
                case "tableName":return family.table();
                case "newWriter":events.add("writer");return newSession();
                default:return RETURNS_DEFAULTS.answer(invocation);
            }});
            owner=family==Family.EVENT?new L2EventResponseFeaturesJobService(calendar,(L2EventResponseFeaturesTarget)target,(L2EventResponseFeaturesParquetSource)source,ledgerPath):new L2T0TrainingLabelsJobService(calendar,(L2T0TrainingLabelsTarget)target,(L2T0TrainingLabelsParquetSource)source,ledgerPath);
        }
        private Object inspection(LocalDate from,LocalDate to) {return family==Family.EVENT?new L2EventResponseFeaturesParquetSource.Inspection(from,to,List.of(DAY),1,1,1,1,1,SOURCE,SCHEMA,"l2-event-response-features-parquet-v1",ROOT,true):new L2T0TrainingLabelsParquetSource.Inspection(from,to,List.of(DAY),1,1,1,1,1,SOURCE,SCHEMA,"l2-t0-training-labels-parquet-v1",ROOT,true,coverage(1));}
        private Object newSession() {
            Class<?> type=family==Family.EVENT?L2EventResponseFeaturesWriteSession.class:L2T0TrainingLabelsWriteSession.class;
            Object session=mock(type,invocation->{switch(invocation.getMethod().getName()){
                case "codec":return family.codec();
                case "preflight":events.add("preflight");if(preflightFailure!=null)throw preflightFailure;return null;
                case "rowCount":events.add("rowCount");return physical.size();
                case "readLatestTradeDate":events.add("latest");return latest;
                case "tableName":return family.table();
                case "send":sends++;for(Object value:(List<?>)invocation.getArgument(0))physical.put(family.codec().key(value),value);latest=DAY;if(unknownAck)throw new IllegalStateException("ack unknown");return null;
                case "readback":return ((List<?>)invocation.getArgument(0)).stream().map(physical::get).filter(Objects::nonNull).toList();
                case "walSettled", "uncertainSenderStopped":return true;
                default:return RETURNS_DEFAULTS.answer(invocation);
            }});sessions.add(session);return session;
        }
        Object manualPlan() {var request=family.definition().freeze(SyncJobDefinition.Mode.BACKFILL,Map.of("symbols",List.of(SYMBOL),"source_root_id",ROOT),DAY,DAY,DAY);return family==Family.EVENT?new L2EventResponseFeaturesJobService.Plan(request,"questdb-contract",DAY,null,0,(L2EventResponseFeaturesParquetSource.Inspection)inspection(DAY,DAY)):new L2T0TrainingLabelsJobService.Plan(request,"questdb-contract",DAY,null,0,(L2T0TrainingLabelsParquetSource.Inspection)inspection(DAY,DAY));}
        Object plan(LocalDate from,SyncJobDefinition.Mode mode) throws Exception {return call(owner.getClass(),owner,"plan",new Class<?>[]{LocalDate.class,LocalDate.class,LocalDate.class,SyncJobDefinition.Mode.class,List.class},from,DAY,DAY,mode,List.of(SYMBOL));}
        SyncJobRunner.Result run(Object plan) throws Exception {return (SyncJobRunner.Result)call(owner.getClass(),owner,"run",new Class<?>[]{plan.getClass()},plan);}
        SyncJobRunner.Result resume(Object plan,String prior) throws Exception {return (SyncJobRunner.Result)call(owner.getClass(),owner,"resume",new Class<?>[]{plan.getClass(),String.class},plan,prior);}
        void prepared(List<Object> rows) throws Exception {call(owner.getClass(),owner,"verifyPreparedWriteRows",new Class<?>[]{List.class},rows);}
    }
}
