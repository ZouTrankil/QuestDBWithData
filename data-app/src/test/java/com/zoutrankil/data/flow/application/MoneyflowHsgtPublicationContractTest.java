package com.zoutrankil.data.flow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtRows;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.*;
import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.calendar.port.SseCalendarWindowReadPort;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Genuine source/stage proofs and SQLite journal/ledger, with offline physical generations. */
class MoneyflowHsgtPublicationContractTest {
    @TempDir Path temp;
    static final String TABLE="java_d027_moneyflow_hsgt_contract", RUN="hsgt-contract", LOGICAL=physical(99);
    static final LocalDate DAY=LocalDate.of(2026,9,17);
    enum Fault { BEFORE_FIRST, AFTER_FIRST, AFTER_SECOND }
    static final class Stop extends Error {}
    static Stream<Arguments> layouts(){return Arrays.stream(Fault.values()).flatMap(f->Stream.of(Arguments.of(f,false),Arguments.of(f,true)));}

    @ParameterizedTest @MethodSource("layouts")
    void hardStopsKeepEachRenameLayoutRecoverableAndCloseLedgerAfterPhysicalProof(Fault fault,boolean empty)throws Exception {
        var f=new Fixture(temp,empty);f.tables.fault=fault;assertThrows(Stop.class,f::publish);
        int mutations=f.tables.renames;assertThrows(IllegalStateException.class,()->f.publisher.finish(RUN,false));
        assertEquals(mutations,f.tables.renames);f.mutexFree();f.tables.fault=null;
        f.publisher.finish(RUN,true);
        assertEquals(ReferencePublicationJournal.State.VERIFIED,f.journal().state());
        assertEquals(SyncRunState.RUNNING,f.ledger.get(RUN).state());assertNotNull(f.locks.findOwned(RUN,f.scope()));
        f.job.finishInterrupted(RUN,true);
        var expected=empty?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
        for(String id:List.of(RUN,RUN+"-attempt",RUN+"-slice"))assertEquals(expected,f.ledger.get(id).state());
        assertNull(f.locks.findOwned(RUN,f.scope()));assertEquals(2,f.tables.renames);assertEquals(1,f.pages.calls);
        assertEquals(f.expected,f.tables.rows.get(TABLE));assertEquals(f.before.rows(),f.tables.rows.get(f.journal().intent().backup()));
        assertEquals(List.of(SyncRunState.PENDING,SyncRunState.RUNNING,SyncRunState.IN_DOUBT,expected),
                f.ledger.events(RUN,-1,100).stream().map(SyncRunLedger.Event::state).toList());
        verify(f.target,never()).newWriter(anyString());f.mutexFree();
        f.job.finishInterrupted(RUN,true);assertEquals(2,f.tables.renames);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void completeStageWithoutJournalUsesFrozenReceiptsAndRetainedLease(boolean empty)throws Exception {
        var f=new Fixture(temp,empty);f.locks.retainInDoubt(f.locks.findOwned(RUN,f.scope()));
        // The verified receipt was not yet created when this process stopped.
        Files.delete(Path.of(f.verified.receipt()));
        f.job.finishInterrupted(RUN,true);
        assertEquals(2,f.tables.renames);assertEquals(ReferencePublicationJournal.State.VERIFIED,f.journal().state());
        assertEquals(empty?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED,f.ledger.get(RUN).state());
        assertEquals(f.expected,f.tables.rows.get(TABLE));assertEquals(1,f.pages.calls);assertNull(f.locks.findOwned(RUN,f.scope()));f.mutexFree();
        verify(f.target,never()).newWriter(anyString());
    }
    @Test void rawReceiptDamageStopsRecoveryBeforeAnyRemainingRename()throws Exception {
        var f=new Fixture(temp,false);f.tables.fault=Fault.AFTER_FIRST;assertThrows(Stop.class,f::publish);
        Files.writeString(Path.of(f.page.responseEvidence()),"\n",StandardOpenOption.APPEND);f.tables.fault=null;
        var journal=f.journal();assertThrows(IllegalStateException.class,()->f.job.finishInterrupted(RUN,true));
        assertEquals(journal,f.journal());assertEquals(1,f.tables.renames);assertNotNull(f.locks.findOwned(RUN,f.scope()));f.mutexFree();
    }
    @Test void changedStageGenerationCannotAuthorizeRecovery()throws Exception {
        var f=new Fixture(temp,false);f.tables.fault=Fault.BEFORE_FIRST;assertThrows(Stop.class,f::publish);f.tables.fault=null;
        f.tables.ids.put(f.prepared.stage(),new Identity(3,"foreign",0));
        assertThrows(IllegalStateException.class,()->f.job.finishInterrupted(RUN,true));assertEquals(0,f.tables.renames);
        assertNotNull(f.locks.findOwned(RUN,f.scope()));f.mutexFree();
    }
    @Test void incompleteSliceIsNotPromotedByStageOnlyRecovery()throws Exception {
        var f=new Fixture(temp,false,false);f.locks.retainInDoubt(f.locks.findOwned(RUN,f.scope()));
        assertThrows(IllegalStateException.class,()->f.job.finishInterrupted(RUN,true));
        assertEquals(SyncRunState.FETCHED,f.ledger.get(RUN+"-slice").state());assertEquals(0,f.tables.renames);
        assertTrue(f.locks.findOwned(RUN,f.scope()).inDoubt());f.mutexFree();
    }
    @Test void cancellationBetweenRenamesRetainsJournalUntilExplicitFinish()throws Exception {
        var f=new Fixture(temp,false);
        try(var operation=f.publisher.beginOperation(RUN)){
            var failure=assertThrows(MoneyflowHsgtPublication.Uncertain.class,()->f.publisher.publish(operation,f.verified,()->f.tables.renames==1));
            assertInstanceOf(java.util.concurrent.CancellationException.class,failure.getCause());
        }
        assertEquals(ReferencePublicationJournal.State.IN_DOUBT,f.journal().state());assertEquals(1,f.tables.renames);
        assertNotNull(f.locks.findOwned(RUN,f.scope()));f.mutexFree();f.job.finishInterrupted(RUN,true);
        assertEquals(2,f.tables.renames);assertNull(f.locks.findOwned(RUN,f.scope()));
    }
    @Test void stoppedWriterIsCheckedBeforeReadingAnyStageOrLedger() {
        var tables=mock(MoneyflowHsgtStagingPort.class);Path ledger=temp.resolve("absent.sqlite");
        assertThrows(IllegalStateException.class,()->MoneyflowHsgtRunRecovery.finishStageOnly(tables,ledger,TABLE,RUN,false));
        verifyNoInteractions(tables);assertFalse(Files.exists(ledger));
    }
    @Test void ownedUnpublishedStageDiscardKeepsOriginalAndReleasesLeaseAfterDurableIntent()throws Exception {
        var f=new Fixture(temp,false);f.transition(RUN,SyncRunState.FAILED,"{}");
        f.publisher.discardStageOnly(TABLE,RUN,true);
        assertEquals(0,f.tables.renames);assertEquals(1,f.tables.drops);assertEquals(f.before.rows(),f.tables.rows.get(TABLE));
        assertFalse(MoneyflowHsgtStaging.hasStageIntent(f.evidence));assertNull(f.locks.findOwned(RUN,f.scope()));f.mutexFree();
    }
    @Test void baselineMutationRejectsDiscardAndKeepsOwnedLease()throws Exception {
        var f=new Fixture(temp,false);f.transition(RUN,SyncRunState.FAILED,"{}");f.tables.ids.put(TABLE,new Identity(1,"old",2));
        assertThrows(IllegalStateException.class,()->f.publisher.discardStageOnly(TABLE,RUN,true));
        assertEquals(0,f.tables.drops);assertTrue(MoneyflowHsgtStaging.hasStageIntent(f.evidence));
        assertNotNull(f.locks.findOwned(RUN,f.scope()));f.mutexFree();
    }

    static final class Fixture {
        final Path path,evidence;final FakeTables tables=new FakeTables();final Pages pages;
        final SyncRunLedger ledger;final DatasetIntervalLock locks;final Snapshot before;
        final MoneyflowHsgtTarget target=mock(MoneyflowHsgtTarget.class);final MoneyflowHsgtPublication publisher;
        final MoneyflowHsgtJobService job;final Prepared prepared;final Verified verified;
        final List<MoneyflowHsgt> expected;final SyncJobRunner.Page<MoneyflowHsgt> page;
        Fixture(Path temp,boolean empty)throws Exception{this(temp,empty,true);}
        Fixture(Path temp,boolean empty,boolean verifiedSlice)throws Exception {
            path=temp.resolve("ledger.sqlite");evidence=temp.resolve("sync-evidence").resolve(RUN);
            ledger=new SyncRunLedger(path);locks=new DatasetIntervalLock(path);pages=new Pages(empty);
            tables.rows.put(TABLE,List.of(sample(DAY.minusDays(1),7.0),sample(DAY,1.0)));tables.ids.put(TABLE,new Identity(1,"old",0));
            before=tables.open(TABLE).snapshot();
            var request=MoneyflowHsgtSyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.INCREMENTAL,
                    Map.of("targetId",LOGICAL,"physicalTargetId",physical(1),"targetRowsBefore",before.rows().size(),"targetFingerprint",before.fingerprint(),
                            "targetMinBefore",DAY.minusDays(1),"targetMaxBefore",DAY,"checkpointAnchor",DAY),DAY,DAY,DAY);
            ledger.createRun(RUN,null,LOGICAL,request);transition(RUN,SyncRunState.RUNNING,"{}");
            ledger.createChild(RUN+"-attempt",SyncRunLedger.Kind.ATTEMPT,RUN,RUN);transition(RUN+"-attempt",SyncRunState.RUNNING,"{}");
            ledger.createChild(RUN+"-slice",SyncRunLedger.Kind.SLICE,RUN,RUN+"-attempt");transition(RUN+"-slice",SyncRunState.RUNNING,"{}");locks.acquire(RUN,scope());
            prepared=new MoneyflowHsgtStaging(tables).prepare(TABLE,LOGICAL,physical(1),RUN,request,evidence,()->false);
            page=new MoneyflowHsgtSource(pages,evidence.resolve("source")).fetch(DAY,DAY,()->false);
            transition(RUN+"-slice",SyncRunState.FETCHED,JobDefinitionJson.mapper().writeValueAsString(Map.of("responseEvidence",page.responseEvidence(),
                    "sourceFingerprint",page.sourceFingerprint(),"returnedRows",page.rows().size(),"cursor",page.cursor())));
            var values=new ArrayList<>(prepared.outside().rows());values.addAll(page.rows());expected=MoneyflowHsgtRows.ordered(values);tables.rows.put(prepared.stage(),expected);
            if(verifiedSlice){
                String completion=completedSliceEvidence();
                if(!empty){transition(RUN+"-slice",SyncRunState.VALIDATED,"{}");transition(RUN+"-slice",SyncRunState.SUBMITTED,"{}");transition(RUN+"-slice",SyncRunState.ACKNOWLEDGED,"{}");}
                transition(RUN+"-slice",empty?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED,completion);
            }
            verified=new MoneyflowHsgtStaging(tables).verify(prepared,List.of(page),()->false);
            publisher=new MoneyflowHsgtPublication(tables,path);
            when(target.tableName()).thenReturn(TABLE);when(target.targetId()).thenReturn(LOGICAL);when(target.newTables()).thenReturn(tables);
            job=new MoneyflowHsgtJobService(mock(SyncJobRegistry.class),pages,target,mock(SseCalendarWindowReadPort.class),path);
        }
        void transition(String id,SyncRunState state,String payload)throws Exception{ledger.transition(id,ledger.get(id).revision(),state,payload);}
        String completedSliceEvidence()throws Exception {
            var raw=MoneyflowHsgtSource.reopen(Path.of(page.responseEvidence()),page.sourceFingerprint(),DAY,DAY);
            var readback=tables.open(prepared.stage()).window(DAY,DAY);
            assertTrue(MoneyflowHsgtRows.sameRows(raw.rows(),readback.rows()));
            Path proof=evidence.resolve("slice-readback.json");
            FileEvidenceStore.writeNew(proof,JobDefinitionJson.canonicalMapper().writeValueAsBytes(Map.of(
                    "stage",prepared.stage(),"snapshot",readback,"sourceFingerprint",raw.sourceFingerprint(),"responseEvidence",raw.responseEvidence())));
            int count=raw.rows().size();
            return JobDefinitionJson.mapper().writeValueAsString(Map.of("sourceComplete",true,"returnedRows",count,"submittedRows",count,
                    "responseEvidence",raw.responseEvidence(),"verification",Map.of("passed",true,"expectedRows",count,"actualRows",readback.rows().size(),
                            "matchedRows",count,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"sourceFingerprint",raw.sourceFingerprint(),
                            "readbackEvidence",proof.toString(),"writerStopped",true)));
        }
        void publish()throws Exception{try(var operation=publisher.beginOperation(RUN)){publisher.publish(operation,verified,()->false);}}
        DatasetIntervalLock.Scope scope(){return new DatasetIntervalLock.Scope("moneyflow_hsgt",DAY,DAY);}
        ReferencePublicationJournal.Entry journal()throws Exception{return new ReferencePublicationJournal(path,"moneyflow_hsgt").forRun(RUN);}
        void mutexFree()throws Exception{try(var channel=FileChannel.open(path.resolveSibling(path.getFileName()+".d027-moneyflow-hsgt.lock"),StandardOpenOption.WRITE);var lock=channel.tryLock()){assertNotNull(lock);}}
    }
    static final class FakeTables implements MoneyflowHsgtStagingPort {
        final Map<String,List<MoneyflowHsgt>> rows=new HashMap<>();final Map<String,Identity> ids=new HashMap<>();int renames,drops,walWaits;Fault fault;boolean stopAfterStageProof;
        public Table open(String name){return new Table(){
            public Identity preflight(){return Objects.requireNonNull(ids.get(name));}
            public Snapshot snapshot()throws Exception{return snapshotOf(rows.get(name));}
            public Snapshot outside(LocalDate from,LocalDate to)throws Exception{return snapshotOf(rows.get(name).stream().filter(r->r.tradeDate().isBefore(from)||r.tradeDate().isAfter(to)).toList());}
            public Snapshot window(LocalDate from,LocalDate to)throws Exception{return snapshotOf(rows.get(name).stream().filter(r->!r.tradeDate().isBefore(from)&&!r.tradeDate().isAfter(to)).toList());}
            Snapshot snapshotOf(List<MoneyflowHsgt> values)throws Exception{var ordered=MoneyflowHsgtRows.ordered(values);var bytes=new java.io.ByteArrayOutputStream();for(var row:ordered){bytes.write(MoneyflowHsgtRows.canonicalBytes(row));bytes.write('\n');}return new Snapshot(preflight(),ordered,FileEvidenceStore.sha256(bytes.toByteArray()),bytes.size());}
        };}
        public String logicalTargetId(String name){return LOGICAL;}
        public String physicalTargetId(String name,Identity identity){return physical(identity.id());}
        public int tableCount(String name){return rows.containsKey(name)?1:0;}
        public int discardTableCount(String name){return tableCount(name);}
        public void createOutsideStage(String target,String stage,LocalDate from,LocalDate to){rows.put(stage,rows.get(target).stream().filter(r->r.tradeDate().isBefore(from)||r.tradeDate().isAfter(to)).toList());ids.put(stage,new Identity(2,"stage",0));}
        public void awaitWal(String table,BooleanSupplier cancelled){if(++walWaits==2&&stopAfterStageProof)throw new Stop();}
        public void drop(String table){rows.remove(table);ids.remove(table);drops++;}
        public boolean dropSettled(String table){return !rows.containsKey(table);}
        public void rename(String from,String to){if(fault==Fault.BEFORE_FIRST&&renames==0)throw new Stop();assertFalse(rows.containsKey(to));rows.put(to,Objects.requireNonNull(rows.remove(from)));ids.put(to,ids.remove(from));renames++;
            if(fault==Fault.AFTER_FIRST&&renames==1||fault==Fault.AFTER_SECOND&&renames==2)throw new Stop();}
    }
    static final class Pages extends TusharePageService {
        final boolean empty;int calls;Pages(boolean empty){super(null);this.empty=empty;}
        @Override public PageExecutor.Fetcher fetcher(PageContract contract,BooleanSupplier cancelled){return params->{calls++;var json=JobDefinitionJson.mapper();var row=new LinkedHashMap<String,JsonNode>();
            for(String field:MoneyflowHsgtSource.FIELDS)row.put(field,json.nullNode());row.put("trade_date",json.valueToTree("20260917"));row.put("north_money",json.valueToTree(2.0));
            return new PageExecutor.Page(empty?List.of():List.of(row),null,false,null);};}
    }
    static MoneyflowHsgt sample(LocalDate date,Double north){return new MoneyflowHsgt(new MoneyflowHsgtKey(date),null,null,null,null,north,null);}
    static String physical(long id){return "static-v2-"+String.format(Locale.ROOT,"%064x",id);}
}
