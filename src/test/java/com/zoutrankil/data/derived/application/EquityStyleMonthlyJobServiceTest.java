package com.zoutrankil.data.derived.application;
import com.zoutrankil.data.derived.storage.QuestDbEquityStyleMonthlySourceReader;
import com.zoutrankil.data.derived.storage.QuestDbEquityStyleMonthlyTarget;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.index.application.IndexMonthlySyncJobOwner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.zoutrankil.data.derived.application.EquityStyleMonthlySourceTest.*;
import static com.zoutrankil.data.derived.application.EquityStyleMonthlyMaterializeAdapterTest.*;

class EquityStyleMonthlyJobServiceTest {
    @TempDir Path temporary;
    static final LocalDate LOGICAL=LocalDate.of(2026,10,6);
    record Setup(Path path,MutableSource source,MemoryPort writer,EquityStyleMonthlyJobService owner){}
    Setup setup(){var source=new MutableSource();var writer=new MemoryPort();var path=temporary.resolve(UUID.randomUUID()+".sqlite3");return new Setup(path,source,writer,new EquityStyleMonthlyJobService(path,source,()->writer));}
    @Test void canonicalDefinitionUsesD022OwnerAndFiniteIsolatedMaterializationPolicies(){
        var d=EquityStyleMonthlyJobService.definition();assertEquals("data.equity_style_monthly",d.jobId());assertEquals("equity_style_monthly",d.datasetId());
        assertEquals(List.of(new SyncJobDefinition.JobRef(IndexMonthlySyncJobOwner.DEFINITION.jobId(),IndexMonthlySyncJobOwner.DEFINITION.version())),d.dependencies());
        assertEquals(Set.of(SyncJobDefinition.Mode.INCREMENTAL,SyncJobDefinition.Mode.MATERIALIZE,SyncJobDefinition.Mode.RECONCILE),d.supportedModes());
        assertEquals("questdb.materialize",d.ratePolicyRef());assertEquals("equity_style_monthly.month_window",d.slicePolicyRef());assertEquals("questdb.full_key_values",d.verificationPolicyRef());
        assertEquals(12,d.budget().maxRows());assertEquals(1,d.budget().maxPages());assertEquals(1,d.retry().maxAttempts());assertFalse(d.dailyEligible());
    }
    @Test void catalogConstructionWithBlankTargetDoesNotConnectOrCreateLedger(){
        var ds=mock(DataSource.class);var path=temporary.resolve("catalog.sqlite3");var owner=new EquityStyleMonthlyJobService(new QuestDbEquityStyleMonthlySourceReader(new JdbcTemplate(ds),"index_monthly"),new QuestDbEquityStyleMonthlyTarget(new JdbcTemplate(ds),new QuestDbProperties(),""),path);
        assertEquals(1,owner.syncJobDefinitions().size());assertEquals("equity_style_monthly",owner.datasetId());assertFalse(Files.exists(path));verifyNoInteractions(ds);
        assertThrows(IllegalArgumentException.class,owner::tableName);verifyNoInteractions(ds);
    }
    @Test void firstRunIsDurablyVerifiedOnlyAfterFullPrefixAndSharedExclusionReleased()throws Exception{
        var s=setup();var plan=s.owner.plan(JUNE,JULY,LOGICAL,null);assertEquals(JUNE,plan.request().from());assertFalse(Files.exists(s.path));
        var result=s.owner.run(plan);assertEquals(SyncRunState.VERIFIED,result.result().state(),result.toString());assertEquals(2,result.result().verifiedRows());assertEquals(32,result.sourceRawRows());assertEquals(1,s.writer.sends);
        var ledger=SyncRunLedger.openReadOnly(s.path);assertEquals(SyncRunState.VERIFIED,ledger.get(result.result().runId()).state());
        assertNull(new DatasetIntervalLock(s.path).findOwned(result.result().runId(),DatasetIntervalLock.Scope.allDates("equity_style_monthly")));assertEquals(2,s.owner.status(result.result().runId()).verifiedRows());
    }
    @Test void onlyAppendedSourceMonthsReuseCheckpointWithRealOneMonthOverlap()throws Exception{
        var s=setup();var first=s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,null));s.source.appendAugust();
        var plan=s.owner.plan(JUNE,AUGUST,LOGICAL,null);assertEquals(JULY,plan.request().from());assertEquals(JULY,plan.request().parameters().get("checkpoint_before"));assertEquals("VERIFIED_PREFIX_APPEND",plan.request().parameters().get("checkpoint_reason"));assertEquals(first.result().runId(),plan.checkpointParent());
        assertNotEquals(first.source().snapshot().version(),plan.source().snapshot().version());
        var next=s.owner.run(plan);assertEquals(SyncRunState.VERIFIED,next.result().state(),next.toString());assertEquals(2,next.result().verifiedRows());assertEquals(3,s.writer.values.size());
        assertEquals(first.result().runId(),SyncRunLedger.openReadOnly(s.path).getRun(next.result().runId()).parentRunId());
    }
    @Test void anyOldRawFieldRevisionRebuildsWholeFinitePrefixEvenIfOutputUnchanged()throws Exception{
        var s=setup();var first=s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,null));s.source.revisePrefix();s.source.appendAugust();
        var plan=s.owner.plan(JUNE,AUGUST,LOGICAL,null);assertEquals(JUNE,plan.request().from());assertFalse(plan.request().parameters().containsKey("checkpoint_before"));assertEquals("SOURCE_PREFIX_REVISED",plan.request().parameters().get("checkpoint_reason"));assertEquals(first.result().runId(),plan.checkpointParent());
        assertEquals(SyncRunState.VERIFIED,s.owner.run(plan).result().state());
    }
    @Test void externallyChangedTargetPrefixRejectsCheckpointAndDoesNotWrite()throws Exception{
        var s=setup();s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,null));var row=s.writer.values.get(YearMonth.from(JUNE));var values=new LinkedHashMap<>(new EquityStyleMonthlyMapper().values(row).asMap());values.put("hs300_ret_1m",999.25);s.writer.values.put(row.month(),new EquityStyleMonthlyMapper().fromValues(values));s.writer.txn++;
        assertThrows(IllegalStateException.class,()->s.owner.plan(JUNE,JULY,LOGICAL,null));assertEquals(1,s.writer.sends);
    }
    @Test void incompleteMonthsAreHistoricalMaterializeRowsButNeverIncrementalCheckpoint()throws Exception{
        var s=setup();s.source.raw.removeIf(r->r.get("ts_code").equals("000920.SH")&&YearMonth.from((LocalDate)r.get("trade_date")).equals(YearMonth.from(JULY)));
        var materialized=s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,SyncJobDefinition.Mode.MATERIALIZE));assertEquals(SyncRunState.VERIFIED,materialized.result().state());assertNull(s.writer.values.get(YearMonth.from(JULY)).valueRet1m());
        assertThrows(IllegalStateException.class,()->s.owner.plan(JUNE,JULY,LOGICAL,null));assertEquals(1,s.writer.sends);
    }
    @Test void absentOrAllNullMonthCannotAdvanceAcrossARealMonthlyGap()throws Exception{
        var s=setup();s.source.raw.removeIf(r->YearMonth.from((LocalDate)r.get("trade_date")).equals(YearMonth.from(JULY)));s.source.appendAugust();
        assertThrows(IllegalStateException.class,()->s.owner.plan(JUNE,AUGUST,LOGICAL,null));assertEquals(0,s.writer.sends);
        var history=s.owner.run(s.owner.plan(JUNE,AUGUST,LOGICAL,SyncJobDefinition.Mode.MATERIALIZE));assertEquals(SyncRunState.VERIFIED,history.result().state());assertEquals(2,history.result().verifiedRows());
        assertThrows(IllegalStateException.class,()->s.owner.plan(JUNE,AUGUST,LOGICAL,null));
    }
    @Test void currentFutureNonFirstAndThirteenthMonthsFailBeforeMetadata(){
        var s=setup();int reads=s.source.reads;
        assertThrows(IllegalArgumentException.class,()->s.owner.plan(JUNE,LocalDate.of(2026,10,1),LOGICAL,null));
        assertThrows(IllegalArgumentException.class,()->s.owner.plan(JUNE,JUNE.plusMonths(12),LOGICAL,null));
        assertThrows(IllegalArgumentException.class,()->s.owner.plan(JUNE.plusDays(1),JULY,LOGICAL,null));
        assertEquals(reads,s.source.reads);assertEquals(0,s.writer.preflights);
    }
    @Test void emptyHistoricalWindowIsVerifiedEmptyAndNeverSuppliesCheckpoint()throws Exception{
        var s=setup();s.source.raw.clear();var result=s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,SyncJobDefinition.Mode.MATERIALIZE));assertEquals(SyncRunState.VERIFIED_EMPTY,result.result().state());assertEquals(0,s.writer.sends);
        assertFalse(s.owner.plan(JUNE,JULY,LOGICAL,SyncJobDefinition.Mode.MATERIALIZE).request().parameters().containsKey("checkpoint_before"));assertThrows(IllegalStateException.class,()->s.owner.plan(JUNE,JULY,LOGICAL,null));
    }
    @Test void frozenSourceChangeIsDurableFailureRatherThanAutomaticReplan()throws Exception{
        var s=setup();var plan=s.owner.plan(JUNE,JULY,LOGICAL,null);s.source.revisePrefix();var result=s.owner.run(plan);
        assertEquals(SyncRunState.FAILED,result.result().state());assertEquals(0,s.writer.sends);assertEquals(SyncRunState.FAILED,SyncRunLedger.openReadOnly(s.path).get(result.result().runId()).state());
    }
    @Test void readonlyReconcileJobChecksActualValuesAndNeverSends()throws Exception{
        var s=setup();var first=s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,null));var result=s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,SyncJobDefinition.Mode.RECONCILE));
        assertEquals(SyncRunState.VERIFIED,result.result().state(),result.toString());assertEquals(1,s.writer.sends);assertThrows(IllegalStateException.class,()->s.owner.resume(first.result().runId()));
    }
    @Test void cancelledFrozenRequestResumesSameIdentityAndUnknownNeverAutomaticallyResumes()throws Exception{
        var s=setup();var plan=s.owner.plan(JUNE,JULY,LOGICAL,null);var ledger=new SyncRunLedger(s.path);ledger.createRun("cancelled",null,TARGET,plan.request());ledger.transition("cancelled",0,SyncRunState.CANCELLED,"{}");
        var result=s.owner.resume("cancelled");assertEquals(SyncRunState.VERIFIED,result.result().state(),result.toString());assertEquals("cancelled",SyncRunLedger.openReadOnly(s.path).getRun(result.result().runId()).parentRunId());
    }
    @Test void unknownOutcomeRetainsDatasetLeaseAndRequiresActualSameInstanceStoppedProof()throws Exception{
        var s=setup();s.writer.interruptUnknown=true;EquityStyleMonthlyJobService.MaterializationResult unknown;
        try{unknown=s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,null));}finally{Thread.interrupted();}
        assertEquals(SyncRunState.IN_DOUBT,unknown.result().state(),unknown.toString());assertNotNull(new DatasetIntervalLock(s.path).findOwned(unknown.result().runId(),DatasetIntervalLock.Scope.allDates("equity_style_monthly")));
        assertThrows(IllegalStateException.class,()->s.owner.requireNoPendingPublication());assertThrows(IllegalStateException.class,()->s.owner.writePort());assertEquals(TARGET,s.owner.targetId());
        assertThrows(IllegalStateException.class,()->s.owner.reconcile(unknown.result().runId()));
        var foreign=new EquityStyleMonthlyJobService(s.path,s.source,()->s.writer);s.writer.stopped=true;assertThrows(IllegalStateException.class,()->foreign.reconcile(unknown.result().runId()));
        assertThrows(IllegalStateException.class,()->s.owner.resume(unknown.result().runId()));assertEquals(1,s.writer.sends);
        assertEquals(SyncRunState.VERIFIED,s.owner.reconcile(unknown.result().runId()).state());assertEquals(1,s.writer.sends);s.owner.requireNoPendingPublication();
    }
    @Test void unknownReconciliationRejectsSourceRevisionAndPreservesOldEvidence()throws Exception{
        var s=setup();s.writer.interruptUnknown=true;EquityStyleMonthlyJobService.MaterializationResult unknown;
        try{unknown=s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,null));}finally{Thread.interrupted();}
        var ledger=SyncRunLedger.openReadOnly(s.path);String before=ledger.get(unknown.result().runId()).payloadJson();s.writer.stopped=true;s.source.revisePrefix();
        assertThrows(IllegalStateException.class,()->s.owner.reconcile(unknown.result().runId()));assertEquals(before,ledger.get(unknown.result().runId()).payloadJson());assertEquals(SyncRunState.IN_DOUBT,ledger.get(unknown.result().runId()).state());assertEquals(1,s.writer.sends);
    }
    @Test void readonlyReconciliationCompletesAPreviouslyVerifiedSliceWithoutResend()throws Exception{
        var s=setup();s.writer.interruptUnknown=true;EquityStyleMonthlyJobService.MaterializationResult unknown;
        try{unknown=s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,null));}finally{Thread.interrupted();}
        s.writer.stopped=true;var ledger=new SyncRunLedger(s.path);var slice=ledger.entries(unknown.result().runId(),null,100).stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).findFirst().orElseThrow();
        String fp=ledger.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).map(e->{try{return JobDefinitionJson.mapper().readTree(e.payloadJson()).path("sourceFingerprint").asText();}catch(Exception error){throw new RuntimeException(error);}}).findFirst().orElseThrow();
        String proof=JobDefinitionJson.mapper().writeValueAsString(Map.of("verification",Map.of("passed",true,"expectedRows",2,"actualRows",2,"matchedRows",2,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"sourceFingerprint",fp,"readbackEvidence","prior-readonly-proof","writerStopped",true)));
        ledger.transition(slice.id(),slice.revision(),SyncRunState.VERIFIED,proof);
        assertEquals(SyncRunState.VERIFIED,s.owner.reconcile(unknown.result().runId()).state());assertEquals(1,s.writer.sends);s.owner.requireNoPendingPublication();
    }
    @Test void sameInstanceReconciliationCompletesRunningAttemptAfterItsDoubtCommitFailed()throws Exception{
        var s=setup();s.writer.interruptUnknown=true;EquityStyleMonthlyJobService.MaterializationResult unknown;
        try{unknown=s.owner.run(s.owner.plan(JUNE,JULY,LOGICAL,null));}finally{Thread.interrupted();}
        s.writer.stopped=true;var ledger=new SyncRunLedger(s.path);
        var attempt=ledger.entries(unknown.result().runId(),null,100).stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).findFirst().orElseThrow();
        var running=ledger.events(attempt.id(),-1,100).stream().filter(e->e.state()==SyncRunState.RUNNING).findFirst().orElseThrow();
        // Reproduce a failed attempt IN_DOUBT transaction while the owning run commit succeeded.
        // Its last durable entry and event are RUNNING, while the actual submitted slice/run remain in doubt.
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+s.path)){
            db.setAutoCommit(false);
            try(var delete=db.prepareStatement("DELETE FROM sync_events WHERE entry_id=? AND revision>?")){
                delete.setString(1,attempt.id());delete.setLong(2,running.revision());delete.executeUpdate();
            }
            try(var update=db.prepareStatement("UPDATE sync_entries SET state=?,revision=?,payload_json=?,updated_at=? WHERE id=?")){
                update.setString(1,SyncRunState.RUNNING.name());update.setLong(2,running.revision());update.setString(3,running.payloadJson());update.setString(4,running.updatedAt());update.setString(5,attempt.id());assertEquals(1,update.executeUpdate());
            }
            db.commit();
        }
        assertEquals(SyncRunState.RUNNING,ledger.get(attempt.id()).state());assertEquals(SyncRunState.IN_DOUBT,ledger.get(unknown.result().runId()).state());
        assertEquals(SyncRunState.VERIFIED,s.owner.reconcile(unknown.result().runId()).state());assertEquals(SyncRunState.VERIFIED,ledger.get(attempt.id()).state());assertEquals(1,s.writer.sends);s.owner.requireNoPendingPublication();
    }
    @Test void retainedPreparedWriteLeaseBlocksNewJobRegardlessOfItsJobId()throws Exception{
        var s=setup();var ledger=new SyncRunLedger(s.path);var plan=s.owner.plan(JUNE,JULY,LOGICAL,null);ledger.createRun("prepared",null,TARGET,plan.request());var locks=new DatasetIntervalLock(s.path);var lease=locks.acquire("prepared",DatasetIntervalLock.Scope.allDates("equity_style_monthly"));locks.retainInDoubt(lease);
        assertThrows(IllegalStateException.class,()->s.owner.plan(JUNE,JULY,LOGICAL,null));assertThrows(IllegalStateException.class,()->s.owner.installIsolated());assertEquals(0,s.writer.sends);assertEquals(TARGET,s.owner.targetId());
    }
}
