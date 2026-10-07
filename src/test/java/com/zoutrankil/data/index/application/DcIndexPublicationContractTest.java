package com.zoutrankil.data.index.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.domain.DcIndexState.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.storage.DcIndexTargetTransitionStore;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real source receipts, journal, physical lineage and ledger recovery with offline physical tables. */
class DcIndexPublicationContractTest {
    @TempDir Path temp;
    static final String TABLE="java_d023_dc_index_contract", STAGE="java_dc_index_stage_"+"a".repeat(32),RUN="dc-contract";
    static final String LOGICAL="static-v2-"+"b".repeat(64);
    static final LocalDate DAY=LocalDate.of(2026,9,17);
    enum Fault { BEFORE_FIRST, AFTER_FIRST, AFTER_SECOND }
    static final class Stop extends Error {}
    static Stream<Arguments> layouts(){return Arrays.stream(Fault.values()).flatMap(f->Stream.of(Arguments.of(f,false),Arguments.of(f,true)));}

    @ParameterizedTest @MethodSource("layouts")
    void hardStopPublishesFrozenNonemptyOrEmptyWindowAndClosesLedger(Fault fault,boolean empty)throws Exception {
        var f=new Fixture(temp,empty);f.tables.fault=fault;
        assertThrows(Stop.class,f::publish);
        int renamed=f.tables.renames;
        assertThrows(IllegalStateException.class,()->f.publisher.finish(RUN,false));
        assertEquals(renamed,f.tables.renames);f.assertMutexFree();f.tables.fault=null;
        f.publisher.finish(RUN,true);
        assertEquals(ReferencePublicationJournal.State.VERIFIED,f.journal().state());
        assertEquals("VERIFIED",new DcIndexTargetTransitionStore(f.path).forRun(RUN,LOGICAL).orElseThrow().state());
        // Publication proves the physical state first; generic run completion is a separate ordered step.
        assertEquals(SyncRunState.FETCHED,f.ledger.get(RUN+"-slice").state());
        assertNotNull(f.locks.findOwned(RUN,f.scope()));
        DcIndexRunRecovery.finishInterrupted(f.target,f.path,RUN,true);
        var expected=empty?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
        for(String id:List.of(RUN+"-slice",RUN+"-attempt",RUN))assertEquals(expected,f.ledger.get(id).state());
        assertNull(f.locks.findOwned(RUN,f.scope()));assertEquals(2,f.tables.renames);
        assertEquals(f.expected,f.tables.rows.get(TABLE));assertEquals(f.before.rows(),f.tables.rows.get(f.journal().intent().backup()));
        assertEquals(1,f.pages.calls);verify(f.target,never()).newStaging();verify(f.target,never()).newWriter(anyString());
        var events=f.ledger.events(RUN+"-slice",-1,100).stream().map(SyncRunLedger.Event::state).toList();
        assertEquals(empty?List.of(SyncRunState.PENDING,SyncRunState.RUNNING,SyncRunState.FETCHED,SyncRunState.VERIFIED_EMPTY)
                :List.of(SyncRunState.PENDING,SyncRunState.RUNNING,SyncRunState.FETCHED,SyncRunState.VALIDATED,SyncRunState.VERIFIED),events);
        try(var ignored=f.publisher.acquire()){}
        // Reopening a completed publication is idempotent and never repeats physical mutations.
        f.publisher.finish(RUN,true);DcIndexRunRecovery.finishInterrupted(f.target,f.path,RUN,true);
        assertEquals(2,f.tables.renames);
    }
    @Test void damagedRawReceiptCannotAuthorizeTheRemainingRename()throws Exception {
        var f=new Fixture(temp,false);f.tables.fault=Fault.AFTER_FIRST;assertThrows(Stop.class,f::publish);
        var saved=f.journal();Files.writeString(Path.of(f.page.responseEvidence()),"\n",StandardOpenOption.APPEND);f.tables.fault=null;
        assertThrows(IllegalStateException.class,()->f.publisher.finish(RUN,true));
        assertEquals(saved,f.journal());assertEquals(1,f.tables.renames);assertFalse(f.tables.rows.containsKey(TABLE));
        assertNotNull(f.locks.findOwned(RUN,f.scope()));assertEquals(SyncRunState.FETCHED,f.ledger.get(RUN+"-slice").state());f.assertMutexFree();
    }
    @Test void changedCompletedStageCannotAuthorizeRecoveryRename()throws Exception {
        var f=new Fixture(temp,false);f.tables.fault=Fault.BEFORE_FIRST;assertThrows(Stop.class,f::publish);
        f.tables.rows.put(STAGE,List.of());f.tables.fault=null;
        assertThrows(IllegalStateException.class,()->f.publisher.finish(RUN,true));
        assertEquals(0,f.tables.renames);assertNotNull(f.locks.findOwned(RUN,f.scope()));f.assertMutexFree();
    }
    @Test void recoveryRejectsUnstoppedWriterBeforeConsultingAnyTarget() {
        var target=mock(DcIndexTarget.class);Path path=temp.resolve("not-created.sqlite");
        assertThrows(IllegalStateException.class,()->DcIndexRunRecovery.finishStageOnly(target,path,RUN,false));
        assertThrows(IllegalStateException.class,()->DcIndexRunRecovery.finishInterrupted(target,path,RUN,false));
        verifyNoInteractions(target);assertFalse(Files.exists(path));
    }
    static final class Fixture {
        final Path path,evidence,completion;final FakeTables tables=new FakeTables();final Pages pages;
        final SyncRunLedger ledger;final DatasetIntervalLock locks;final DcIndexTarget target=mock(DcIndexTarget.class);
        final Snapshot before;final Complete complete;final List<DcIndex> expected;final SyncJobRunner.Page<DcIndex> page;
        final String combined;final DcIndexPublication publisher;
        Fixture(Path temp,boolean empty)throws Exception {
            path=temp.resolve("ledger.sqlite");evidence=temp.resolve("sync-evidence").resolve(RUN);completion=evidence.resolve("complete-window.json");
            pages=new Pages(empty);ledger=new SyncRunLedger(path);locks=new DatasetIntervalLock(path);
            tables.rows.put(TABLE,List.of(sample("OLD.DC",DAY),sample("KEEP.DC",DAY.minusDays(1))));tables.identities.put(TABLE,new Identity(1,"old"));
            before=tables.open(TABLE).snapshot();
            when(target.tableName()).thenReturn(TABLE);when(target.targetId()).thenReturn(LOGICAL);when(target.physicalTargetId()).thenReturn(physical(1));
            when(target.newPublicationTables()).thenReturn(tables);
            var request=DcIndexSyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.INCREMENTAL,
                    Map.of("targetId",LOGICAL,"physicalTargetId",physical(1),"trade_dates","20260917","checkpointAnchor",DAY),DAY,DAY,DAY);
            ledger.createRun(RUN,null,LOGICAL,request);running(RUN);
            ledger.createChild(RUN+"-attempt",SyncRunLedger.Kind.ATTEMPT,RUN,RUN);running(RUN+"-attempt");
            ledger.createChild(RUN+"-slice",SyncRunLedger.Kind.SLICE,RUN,RUN+"-attempt");running(RUN+"-slice");locks.acquire(RUN,scope());
            page=new DcIndexSource(pages,evidence.resolve("source")).fetch(DAY,()->false);
            ledger.transition(RUN+"-slice",ledger.get(RUN+"-slice").revision(),SyncRunState.FETCHED,JobDefinitionJson.mapper().writeValueAsString(Map.of(
                    "responseEvidence",page.responseEvidence(),"sourceFingerprint",page.sourceFingerprint(),"returnedRows",page.rows().size(),"cursor",page.cursor())));
            var digest=java.security.MessageDigest.getInstance("SHA-256");digest.update(page.sourceFingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);combined=HexFormat.of().formatHex(digest.digest());
            expected=DcIndexRows.prepare(before,before,page.rows(),DAY,DAY).expected();tables.rows.put(STAGE,expected);tables.identities.put(STAGE,new Identity(2,"stage"));
            complete=new Complete(STAGE,tables.open(STAGE).snapshot(),page.rows().size(),combined,evidence.resolve("stage-complete.json").toString());
            var body=new LinkedHashMap<String,Object>();body.put("dataset","dc_index");body.put("endpoint","dc_index");body.put("mode","INCREMENTAL");
            body.put("fromInclusive",DAY.toString());body.put("toInclusive",DAY.toString());body.put("tradeDates",List.of(DAY));body.put("completedDateSlices",1);
            body.put("sourceRows",page.rows().size());body.put("returnedRows",page.rows().size());body.put("submittedRows",page.rows().size());
            body.put("sourceFingerprint",combined);body.put("sourceReceipts",List.of(page.responseEvidence()));body.put("stage",STAGE);body.put("stageReceipt",complete.receipt());
            body.put("snapshotProof",DcIndexRows.snapshotProof(complete.snapshot()));body.put("dedup",false);body.put("sourceComplete",true);body.put("complete",true);
            FileEvidenceStore.writeNew(completion,JobDefinitionJson.mapper().writeValueAsBytes(body));publisher=new DcIndexPublication(tables,path,evidence,TABLE,LOGICAL,RUN);
        }
        void publish()throws Exception {try(var ignored=publisher.acquire()){publisher.publishWindow(before,complete,page.rows(),DAY,DAY,physical(1),combined,completion.toString(),()->false);}}
        void running(String id)throws Exception{ledger.transition(id,ledger.get(id).revision(),SyncRunState.RUNNING,"{}");}
        ReferencePublicationJournal.Entry journal()throws Exception{return new ReferencePublicationJournal(path,"dc_index").forRun(RUN);}
        DatasetIntervalLock.Scope scope(){return new DatasetIntervalLock.Scope("dc_index",DAY,DAY);}
        void assertMutexFree()throws Exception{try(var channel=FileChannel.open(path.resolveSibling(path.getFileName()+".dc-index-publication.lock"),StandardOpenOption.WRITE);var lock=channel.tryLock()){assertNotNull(lock);}}
    }
    static final class FakeTables implements DcIndexTables {
        final Map<String,List<DcIndex>> rows=new HashMap<>();final Map<String,Identity> identities=new HashMap<>();int renames;Fault fault;
        public Table open(String name){return new Table(){public Identity preflight(){return Objects.requireNonNull(identities.get(name));}
            public Snapshot snapshot()throws Exception {var values=rows.get(name);byte[] bytes=DcIndexRows.canonical(values);return new Snapshot(preflight(),values,FileEvidenceStore.sha256(bytes),bytes.length);}};}
        public String logicalTargetId(String name){return LOGICAL;}
        public String physicalTargetId(String name,Identity identity){return physical(identity.id());}
        public Snapshot snapshotIfPresent(String name)throws Exception{return rows.containsKey(name)?open(name).snapshot():null;}
        public void rename(String from,String to){if(fault==Fault.BEFORE_FIRST&&renames==0)throw new Stop();assertFalse(rows.containsKey(to));
            rows.put(to,Objects.requireNonNull(rows.remove(from)));identities.put(to,identities.remove(from));renames++;
            if(fault==Fault.AFTER_FIRST&&renames==1||fault==Fault.AFTER_SECOND&&renames==2)throw new Stop();}
    }
    static final class Pages extends TusharePageService {
        final boolean empty;int calls;Pages(boolean empty){super(null);this.empty=empty;}
        @Override public PageExecutor.Fetcher fetcher(PageContract contract,BooleanSupplier cancelled){return params->{calls++;var json=JobDefinitionJson.mapper();var row=new LinkedHashMap<String,JsonNode>();
            for(String field:DcIndexSource.FIELDS)row.put(field,json.nullNode());row.put("ts_code",json.valueToTree("NEW.DC"));row.put("trade_date",json.valueToTree("20260917"));
            return new PageExecutor.Page(empty?List.of():List.of(row),null,false,null);};}
    }
    static DcIndex sample(String code,LocalDate day){return new DcIndex(code,day,null,null,null,null,null,null,null,null,null);}
    static String physical(long id){return "static-v2-"+String.format(Locale.ROOT,"%064x",id);}
}
