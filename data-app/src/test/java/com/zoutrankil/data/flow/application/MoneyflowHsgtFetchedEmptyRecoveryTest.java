package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.calendar.port.SseCalendarWindowReadPort;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.*;
import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.flow.storage.MoneyflowHsgtWritePort;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.*;
import static com.zoutrankil.data.flow.application.MoneyflowHsgtPublicationContractTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real owner plan/run and runner-produced FETCHED evidence, interrupted inside publication. */
class MoneyflowHsgtFetchedEmptyRecoveryTest {
    @TempDir Path temp;
    @ParameterizedTest @EnumSource(Fault.class)
    void actualEmptySourceHardStopRecoversOnlyAfterImmutableAndPhysicalProof(Fault fault)throws Exception {
        var f=new ActualRun(temp,true);f.stop(fault);
        assertEquals(SyncRunState.FETCHED,f.slice().state());assertEquals(SyncRunState.RUNNING,f.ledger.get(f.run).state());
        assertEquals(List.of(SyncRunState.PENDING,SyncRunState.RUNNING,SyncRunState.FETCHED),f.ledger.events(f.slice().id(),-1,100).stream().map(SyncRunLedger.Event::state).toList());
        var fetched=f.fetched();assertTrue(fetched.path("returnedRows").isIntegralNumber());assertEquals(0,fetched.path("returnedRows").intValue());
        var raw=MoneyflowHsgtSource.reopen(Path.of(fetched.path("responseEvidence").asText()),fetched.path("sourceFingerprint").asText(),DAY,DAY);assertTrue(raw.rows().isEmpty());
        int writes=f.sessions.stream().mapToInt(s->s.sends).sum();assertEquals(0,writes);f.tables.fault=null;
        f.job.finishInterrupted(f.run,true);
        assertEquals(SyncRunState.VERIFIED_EMPTY,f.ledger.get(f.run).state());
        for(var child:f.ledger.entries(f.run,null,100))assertEquals(SyncRunState.VERIFIED_EMPTY,child.state());
        assertEquals(ReferencePublicationJournal.State.VERIFIED,f.journal().state());assertEquals(2,f.tables.renames);
        assertNull(f.locks.findOwned(f.run,f.scope()));assertEquals(1,f.pages.calls);
        assertEquals(List.of(SyncRunState.PENDING,SyncRunState.RUNNING,SyncRunState.FETCHED,SyncRunState.VERIFIED_EMPTY),f.ledger.events(f.slice().id(),-1,100).stream().map(SyncRunLedger.Event::state).toList());
        f.job.finishInterrupted(f.run,true);assertEquals(2,f.tables.renames);assertEquals(1,f.pages.calls);
        assertEquals(writes,f.sessions.stream().mapToInt(s->s.sends).sum());
    }
    @Test void corruptedEmptyRawReceiptCannotAuthorizeAnyRemainingRename()throws Exception {
        var f=new ActualRun(temp,true);f.stop(Fault.BEFORE_FIRST);var fetched=f.fetched();
        Files.writeString(Path.of(fetched.path("responseEvidence").asText()),"\n",StandardOpenOption.APPEND);f.tables.fault=null;
        assertThrows(IllegalStateException.class,()->f.job.finishInterrupted(f.run,true));f.assertUnchangedRejected();
    }
    @Test void actualEmptyStageOnlyHardStopUsesOwnedLeaseAndFullProofBeforePublishing()throws Exception {
        var f=new ActualRun(temp,true);f.tables.stopAfterStageProof=true;f.stop(null);
        assertTrue(new ReferencePublicationJournal(f.path,"moneyflow_hsgt").findForRun(f.run).isEmpty());
        assertEquals(SyncRunState.FETCHED,f.slice().state());assertFalse(f.locks.findOwned(f.run,f.scope()).inDoubt());
        f.tables.stopAfterStageProof=false;f.job.finishInterrupted(f.run,true);
        assertEquals(SyncRunState.VERIFIED_EMPTY,f.ledger.get(f.run).state());assertEquals(SyncRunState.VERIFIED_EMPTY,f.slice().state());
        assertEquals(2,f.tables.renames);assertEquals(ReferencePublicationJournal.State.VERIFIED,f.journal().state());
        assertNull(f.locks.findOwned(f.run,f.scope()));assertEquals(1,f.pages.calls);assertEquals(0,f.sessions.stream().mapToInt(s->s.sends).sum());
    }
    @ParameterizedTest @ValueSource(strings={"endpoint","incomplete"})
    void stageOnlyRejectsRehashedWrongEndpointOrIncompleteSourceBeforeLeaseOrRename(String fault)throws Exception {
        var f=new ActualRun(temp,true);f.tables.stopAfterStageProof=true;f.stop(null);f.tables.stopAfterStageProof=false;
        var fetched=(com.fasterxml.jackson.databind.node.ObjectNode)f.fetched();Path path=Path.of(fetched.path("responseEvidence").asText());
        var raw=(com.fasterxml.jackson.databind.node.ObjectNode)JobDefinitionJson.mapper().readTree(path.toFile());
        if(fault.equals("endpoint"))raw.put("endpoint","margin_secs");else raw.put("sourceComplete",false);
        byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(raw);Files.write(path,bytes);
        fetched.put("sourceFingerprint",FileEvidenceStore.sha256(bytes));f.replaceFetched(fetched.toString());
        assertThrows(IllegalStateException.class,()->f.job.finishInterrupted(f.run,true));
        assertEquals(0,f.tables.renames);assertTrue(new ReferencePublicationJournal(f.path,"moneyflow_hsgt").findForRun(f.run).isEmpty());
        assertEquals(SyncRunState.FETCHED,f.slice().state());assertFalse(f.locks.findOwned(f.run,f.scope()).inDoubt());
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","RUNNING","VALIDATED","SUBMITTED","ACKNOWLEDGED","IN_DOUBT","CANCELLED"})
    void unexpectedEmptySliceStateCannotAcquireTheFetchedExemption(String state)throws Exception {
        var f=new ActualRun(temp,true);f.stop(Fault.BEFORE_FIRST);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+f.path);var s=c.prepareStatement("UPDATE sync_entries SET state=? WHERE id=?")){
            s.setString(1,state);s.setString(2,f.slice().id());assertEquals(1,s.executeUpdate());
        }
        f.tables.fault=null;assertThrows(IllegalStateException.class,()->f.job.finishInterrupted(f.run,true));
        assertEquals(0,f.tables.renames);assertEquals(ReferencePublicationJournal.State.PREPARED,f.journal().state());assertNotNull(f.locks.findOwned(f.run,f.scope()));
        assertEquals(SyncRunState.valueOf(state),f.slice().state());
    }
    @ParameterizedTest @ValueSource(strings={"duplicateFetched","parent","cursor"})
    void corruptedLedgerOwnershipAndFetchedWindowRejectBeforePublicationMutation(String corruption)throws Exception {
        var f=new ActualRun(temp,true);f.stop(Fault.BEFORE_FIRST);
        if(corruption.equals("duplicateFetched")){
            var event=f.ledger.events(f.slice().id(),-1,100).getLast();
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+f.path);var s=c.prepareStatement("INSERT INTO sync_events(entry_id,revision,state,payload_json,updated_at) VALUES(?,?,'FETCHED',?,?)")){
                s.setString(1,event.entryId());s.setLong(2,event.revision()+1);s.setString(3,event.payloadJson());s.setString(4,event.updatedAt());assertEquals(1,s.executeUpdate());
            }
        }else if(corruption.equals("parent")){
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+f.path);var s=c.prepareStatement("UPDATE sync_entries SET parent_id=? WHERE id=?")){
                s.setString(1,f.run);s.setString(2,f.slice().id());assertEquals(1,s.executeUpdate());
            }
        }else{var payload=(com.fasterxml.jackson.databind.node.ObjectNode)f.fetched();payload.put("cursor","20260916..20260917");f.replaceFetched(payload.toString());}
        f.tables.fault=null;assertThrows(IllegalStateException.class,()->f.job.finishInterrupted(f.run,true));f.assertUnchangedRejected();
    }
    @Test void nonemptyFetchedStateCannotAuthorizeRenameEvenWithMatchingPhysicalRows()throws Exception {
        var f=new ActualRun(temp,false);f.stop(Fault.BEFORE_FIRST);
        // A drifted ledger is not a completed slice; keep its genuine nonempty FETCHED event and raw proof.
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+f.path);var s=c.prepareStatement("UPDATE sync_entries SET state='FETCHED' WHERE id=?")){
            s.setString(1,f.slice().id());assertEquals(1,s.executeUpdate());
        }
        assertEquals(1,f.fetched().path("returnedRows").intValue());f.tables.fault=null;
        assertThrows(IllegalStateException.class,()->f.job.finishInterrupted(f.run,true));f.assertUnchangedRejected();
    }
    @ParameterizedTest @ValueSource(strings={"-0.5","\"0\"","false"})
    void emptyRecoveryExemptionDoesNotCoerceFetchedCount(String value)throws Exception {
        var f=new ActualRun(temp,true);f.stop(Fault.BEFORE_FIRST);var event=f.ledger.events(f.slice().id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).findFirst().orElseThrow();
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)JobDefinitionJson.mapper().readTree(event.payloadJson());body.set("returnedRows",JobDefinitionJson.mapper().readTree(value));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+f.path);var s=c.prepareStatement("UPDATE sync_events SET payload_json=? WHERE entry_id=? AND revision=?")){
            s.setString(1,body.toString());s.setString(2,event.entryId());s.setLong(3,event.revision());assertEquals(1,s.executeUpdate());
        }
        f.tables.fault=null;assertThrows(IllegalStateException.class,()->f.job.finishInterrupted(f.run,true));f.assertUnchangedRejected();
    }
    static final class ActualRun {
        final Path path;final FakeTables tables=new FakeTables();final Pages pages;
        final List<MemorySession> sessions=new ArrayList<>();final MoneyflowHsgtJobService job;final MoneyflowHsgtJobService.Plan plan;
        final SyncRunLedger ledger;final DatasetIntervalLock locks;String run;
        ActualRun(Path root,boolean empty)throws Exception {
            path=root.resolve("ledger.sqlite");tables.rows.put(TABLE,List.of());tables.ids.put(TABLE,new Identity(1,"old",0));pages=new Pages(empty);
            var owner=new MoneyflowHsgtSyncJobOwner();var definition=owner.syncJobDefinitions().getFirst();
            var registry=new SyncJobRegistry(List.of(definition),new DatasetRegistry(List.<DatasetImplementation>of(()->MoneyflowHsgtDataset.definition(TABLE))),
                    Map.of(owner.datasetId(),owner.supportedSyncModes()),new SyncJobRegistry.Policies(Set.of(definition.ratePolicyRef()),Set.of(definition.slicePolicyRef()),Set.of(definition.verificationPolicyRef())));
            var target=mock(MoneyflowHsgtTarget.class);when(target.tableName()).thenReturn(TABLE);when(target.targetId()).thenReturn(LOGICAL);
            when(target.physicalTargetId()).thenAnswer(c->physical(tables.ids.get(TABLE).id()));when(target.snapshot()).thenAnswer(c->tables.open(TABLE).snapshot());when(target.newTables()).thenReturn(tables);
            when(target.newWriter(anyString())).thenAnswer(c->{var session=new MemorySession(tables);sessions.add(session);return session;});
            job=new MoneyflowHsgtJobService(registry,pages,target,mock(SseCalendarWindowReadPort.class),path);
            plan=job.plan(SyncJobDefinition.Mode.INCREMENTAL,DAY,DAY,DAY);assertEquals(0,pages.calls);
            ledger=new SyncRunLedger(path);locks=new DatasetIntervalLock(path);
        }
        void stop(Fault fault)throws Exception {
            tables.fault=fault;assertThrows(Stop.class,()->job.run(plan));
            run=ledger.history(MoneyflowHsgtSyncJobOwner.DEFINITION.jobId(),null,100).getFirst().id();
            assertNotNull(locks.findOwned(run,scope()));assertEquals(1,pages.calls);
        }
        SyncRunLedger.Entry slice()throws Exception{return ledger.entries(run,null,100).stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).findFirst().orElseThrow();}
        com.fasterxml.jackson.databind.JsonNode fetched()throws Exception{return JobDefinitionJson.mapper().readTree(ledger.events(slice().id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).findFirst().orElseThrow().payloadJson());}
        void replaceFetched(String payload)throws Exception {
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.prepareStatement("UPDATE sync_events SET payload_json=? WHERE entry_id=? AND state='FETCHED'")){
                s.setString(1,payload);s.setString(2,slice().id());assertEquals(1,s.executeUpdate());
            }
        }
        DatasetIntervalLock.Scope scope(){return new DatasetIntervalLock.Scope("moneyflow_hsgt",DAY,DAY);}
        ReferencePublicationJournal.Entry journal()throws Exception{return new ReferencePublicationJournal(path,"moneyflow_hsgt").forRun(run);}
        void assertUnchangedRejected()throws Exception {
            assertEquals(0,tables.renames);assertEquals(ReferencePublicationJournal.State.PREPARED,journal().state());
            assertEquals(SyncRunState.FETCHED,slice().state());assertNotNull(locks.findOwned(run,scope()));
            try(var publication=new MoneyflowHsgtPublication(tables,path).beginOperation(run)){}
        }
    }
    static final class MemorySession implements MoneyflowHsgtWriteSession {
        final FakeTables tables;String stage=TABLE;int sends;
        MemorySession(FakeTables tables){this.tables=tables;}
        public String formalTable(){return TABLE;}public String stageTable(){return stage;}
        public void useStage(String stage,String id){this.stage=stage;}
        public void preflight(){}
        public VerifiedBatchExecutor.Codec<MoneyflowHsgt,MoneyflowHsgtKey> codec(){return MoneyflowHsgtWritePort.CODEC;}
        public void send(List<MoneyflowHsgt> rows){sends++;var all=new ArrayList<>(tables.rows.get(stage));all.addAll(rows);tables.rows.put(stage,List.copyOf(all));}
        public List<MoneyflowHsgt> readback(List<MoneyflowHsgtKey> keys){return tables.rows.get(stage).stream().filter(r->keys.contains(r.key())).toList();}
        public boolean walSettled(){return true;}public boolean uncertainSenderStopped(){return true;}
        public List<MoneyflowHsgt> readWindow(String table,LocalDate from,LocalDate to){try{return tables.open(table).window(from,to).rows();}catch(Exception failure){throw new IllegalStateException(failure);}}
        public TargetRange readTargetRange(){return new TargetRange(null,null,0);}
    }
}
