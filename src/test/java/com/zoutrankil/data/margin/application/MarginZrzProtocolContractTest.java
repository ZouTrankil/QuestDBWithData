package com.zoutrankil.data.margin.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.margin.domain.MarginZrzRows;
import com.zoutrankil.data.margin.domain.MarginZrzState.*;
import com.zoutrankil.data.margin.port.*;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite and durable source/stage evidence; only physical table operations are fake. */
class MarginZrzProtocolContractTest {
    static final String RUN="owned-run",TARGET="java_d031_margin_zrz_contract",LOGICAL="static-v2-"+"a".repeat(64),PHYSICAL="static-v2-"+"b".repeat(64),STAGED="static-v2-"+"c".repeat(64);
    static final LocalDate DAY=LocalDate.of(2025,7,1);
    @TempDir Path temp;
    enum Fault { BEFORE_FIRST,AFTER_FIRST,BEFORE_SECOND,AFTER_SECOND }
    enum Tamper { LOGICAL,REQUEST,PHYSICAL,TARGET,RUN,DATE,MODE,PHASE,STAGE_ID,WRITER_TXN,FINGERPRINT }

    @ParameterizedTest @EnumSource(Fault.class)
    void everyRenameFailureRecoversItsActualLayoutWithoutCompletingTheRun(Fault fault)throws Exception {
        var h=new Harness(temp);h.tables.fault=fault;
        var failure=assertThrows(MarginZrzPublication.Uncertain.class,()->h.publish(()->false));assertEquals(RUN,failure.runId());
        var entry=h.journal.forRun(RUN);assertEquals(ReferencePublicationJournal.State.IN_DOUBT,entry.state());
        switch(fault) {
            case BEFORE_FIRST -> {assertTrue(h.tables.data.containsKey(TARGET));assertFalse(h.tables.data.containsKey(entry.intent().backup()));}
            case AFTER_FIRST,BEFORE_SECOND -> {assertFalse(h.tables.data.containsKey(TARGET));assertTrue(h.tables.data.containsKey(entry.intent().backup()));}
            case AFTER_SECOND -> {assertTrue(h.tables.data.containsKey(TARGET));assertFalse(h.tables.data.containsKey(h.prepared.stage()));}
        }
        h.tables.fault=null;var result=h.publication.finish(RUN,true);
        assertEquals(ReferencePublicationJournal.State.VERIFIED,result.entry().state());assertEquals(MarginZrzPublication.Layout.PUBLISHED,result.layout());
        assertEquals(h.staged,result.target());assertEquals(h.before,result.backup());assertEquals(SyncRunState.RUNNING,h.ledger.get(RUN).state());
        int renames=h.tables.renames;assertEquals(result,h.publication.finish(RUN,true));assertEquals(renames,h.tables.renames);
    }
    @Test void stoppedProofPrecedesAllPhysicalReadsAndForeignLayoutIsRejected()throws Exception {
        var h=new Harness(temp);h.tables.reads=0;
        assertThrows(IllegalStateException.class,()->h.publication.finish(RUN,false));assertEquals(0,h.tables.reads);
        h.tables.fault=Fault.BEFORE_FIRST;assertThrows(MarginZrzPublication.Uncertain.class,()->h.publish(()->false));h.tables.fault=null;
        h.tables.data.put(TARGET,snapshot(new Identity(99,"foreign",7),h.before.rows()));int n=h.tables.renames;
        assertThrows(IllegalStateException.class,()->h.publication.finish(RUN,true));assertEquals(n,h.tables.renames);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void alteredSourceOrStageReceiptCannotAuthorizeRecovery(boolean stageProof)throws Exception {
        var h=new Harness(temp);h.tables.fault=Fault.BEFORE_FIRST;assertThrows(MarginZrzPublication.Uncertain.class,()->h.publish(()->false));h.tables.fault=null;
        Files.writeString(stageProof?Path.of(h.verified.receipt()):h.raw,"{}");int n=h.tables.renames;
        assertThrows(IllegalStateException.class,()->h.publication.finish(RUN,true));assertEquals(n,h.tables.renames);
        assertEquals(ReferencePublicationJournal.State.IN_DOUBT,h.journal.forRun(RUN).state());
    }
    @Test void cancellationBeforeAndBetweenRenamesRetainsEvidenceAndReleasesOsLock()throws Exception {
        var h=new Harness(temp);assertThrows(CancellationException.class,()->h.publish(()->true));assertTrue(h.journal.findForRun(RUN).isEmpty());
        var checks=new AtomicInteger();assertThrows(MarginZrzPublication.Uncertain.class,()->h.publish(()->checks.incrementAndGet()==3));
        assertFalse(h.tables.data.containsKey(TARGET));assertTrue(Files.isRegularFile(Path.of(h.verified.receipt())));
        assertEquals(MarginZrzPublication.Layout.PUBLISHED,h.publication.finish(RUN,true).layout());
    }
    @ParameterizedTest @EnumSource(Tamper.class)
    void discardRejectsForeignFrozenProofAndGenerationDrift(Tamper tamper)throws Exception {
        var h=new Harness(temp);var json=(ObjectNode)JobDefinitionJson.mapper().readTree(h.intent.toFile());
        switch(tamper) {
            case LOGICAL -> json.put("logicalTargetId","static-v2-"+"f".repeat(64));
            case REQUEST -> json.put("requestFingerprint","f".repeat(64));
            case PHYSICAL -> json.put("physicalTargetBefore",STAGED);
            case TARGET -> json.put("target","java_d031_margin_zrz_foreign");
            case RUN -> json.put("runId","foreign-run");
            case DATE -> json.put("windowFrom",DAY.minusDays(1).toString());
            case MODE -> json.put("mode","RECONCILE");
            case PHASE -> json.put("phase","PUBLISHED");
            case STAGE_ID -> json.put("stagePhysicalTarget",PHYSICAL);
            case WRITER_TXN -> ((ObjectNode)json.path("before").path("identity")).put("writerTxn",99);
            case FINGERPRINT -> ((ObjectNode)json.path("before")).put("fingerprint","f".repeat(64));
        }
        Files.write(h.intent,JobDefinitionJson.canonicalMapper().writeValueAsBytes(json));h.tables.reads=0;
        assertThrows(IllegalStateException.class,()->MarginZrzStaging.discardUnpublished(h.tables,TARGET,RUN,h.root,h.path,true));
        assertEquals(0,h.tables.drops);assertTrue(h.tables.data.containsKey(h.prepared.stage()));
        if(tamper.ordinal()<Tamper.STAGE_ID.ordinal())assertEquals(0,h.tables.reads);
    }
    @Test void discardOnlyDropsOwnedUnpublishedStageAndReleasesLeaseForRefetch()throws Exception {
        var h=new Harness(temp);h.ledger.transition(RUN,1,SyncRunState.IN_DOUBT,"{}");
        var locks=new DatasetIntervalLock(h.path);var lease=locks.acquire(RUN,new DatasetIntervalLock.Scope("margin_zrz",DAY,DAY));locks.retainInDoubt(lease);
        h.publication.discardStageOnly(TARGET,RUN,true);
        assertEquals(1,h.tables.drops);assertEquals(0,h.tables.renames);assertEquals(h.before,h.tables.data.get(TARGET));assertNull(locks.findOwned(RUN,lease.scope()));
        assertEquals(SyncRunState.IN_DOUBT,h.ledger.get(RUN).state());assertFalse(MarginZrzStaging.hasStageIntent(h.root));
        var proof=JobDefinitionJson.mapper().readTree(h.intent.toFile());assertEquals("DISCARDED",proof.path("phase").asText());assertTrue(proof.path("discardedWithStoppedWriter").asBoolean());
        MarginZrzStaging.discardUnpublished(h.tables,TARGET,RUN,h.root,h.path,true);assertEquals(1,h.tables.drops);
    }
    @Test void discardedMarkerStillRequiresValidFrozenAuthorityAndStoppedProof()throws Exception {
        assertThrows(IllegalStateException.class,()->MarginZrzStaging.discardUnpublished(null,null,null,temp.resolve("missing"),temp.resolve("missing.sqlite3"),false));
        var h=new Harness(temp);var proof=(ObjectNode)JobDefinitionJson.mapper().readTree(h.intent.toFile());proof.put("phase","DISCARDED");proof.put("logicalTargetId","foreign");Files.write(h.intent,JobDefinitionJson.canonicalMapper().writeValueAsBytes(proof));
        assertThrows(IllegalStateException.class,()->MarginZrzStaging.discardUnpublished(h.tables,TARGET,RUN,h.root,h.path,true));assertEquals(0,h.tables.drops);
    }
    @Test void physicalOriginalAndStageDriftCannotBeDiscarded()throws Exception {
        var h=new Harness(temp);h.tables.data.put(TARGET,snapshot(new Identity(1,"generation-1",99),h.before.rows()));
        assertThrows(IllegalStateException.class,()->MarginZrzStaging.discardUnpublished(h.tables,TARGET,RUN,h.root,h.path,true));assertEquals(0,h.tables.drops);
        h.tables.data.put(TARGET,h.before);h.tables.data.put(h.prepared.stage(),snapshot(new Identity(99,"foreign",9),h.staged.rows()));
        assertThrows(IllegalStateException.class,()->MarginZrzStaging.discardUnpublished(h.tables,TARGET,RUN,h.root,h.path,true));assertEquals(0,h.tables.drops);
    }
    @Test void preparePersistsIntentBeforeDdlAndVerifyAuthenticatesRawReceiptBeforeTargetRead()throws Exception {
        var h=new Harness(temp);assertTrue(h.tables.sawPreparingIntent);assertEquals(2,h.tables.walChecks);assertEquals(1,h.verified.authoritativeRows().size());
        Files.writeString(h.raw,"{}");h.tables.reads=0;
        assertThrows(IllegalStateException.class,()->h.staging.verify(h.prepared,List.of(h.page),()->false));assertEquals(0,h.tables.reads);
    }
    @Test void anotherPublicationInstanceCannotEnterWhileOperationHoldsOsLock()throws Exception {
        var h=new Harness(temp);var other=new MarginZrzPublication(h.tables,h.path);
        try(var operation=h.publication.beginOperation(RUN)){assertThrows(IllegalStateException.class,()->other.beginOperation(RUN));}
        try(var released=other.beginOperation(RUN)){assertNotNull(released);}
    }

    static MarginZrz row(LocalDate day,double value){return new MarginZrz(new MarginZrzKey(day),value,value,null,value,value);}
    static Snapshot snapshot(Identity id,List<MarginZrz> input)throws Exception {
        var rows=MarginZrzRows.ordered(input);var digest=java.security.MessageDigest.getInstance("SHA-256");int size=0;
        for(var row:rows){byte[] bytes=MarginZrzRows.canonicalBytes(row);digest.update(bytes);digest.update((byte)'\n');size+=bytes.length+1;}
        return new Snapshot(id,rows,HexFormat.of().formatHex(digest.digest()),size);
    }
    static final class Harness {
        final Path path,root,raw,intent;final SyncRunLedger ledger;final ReferencePublicationJournal journal;final FakeTables tables=new FakeTables();
        final MarginZrzStaging staging;final MarginZrzPublication publication;final Prepared prepared;final Verified verified;final Snapshot before,staged;final SyncJobRunner.Page<MarginZrz> page;
        Harness(Path temp)throws Exception {
            path=temp.resolve("ledger.sqlite3");root=temp.resolve("sync-evidence").resolve(RUN);Files.createDirectories(root.resolve("source"));tables.root=root;
            before=snapshot(new Identity(1,"generation-1",7),List.of(row(DAY.minusDays(1),2),row(DAY,3)));tables.data.put(TARGET,before);
            var request=MarginZrzSyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.BACKFILL,Map.of("targetId",LOGICAL,"physicalTargetId",PHYSICAL,"targetRowsBefore",before.rows().size(),"targetFingerprint",before.fingerprint()),DAY,DAY,DAY);
            ledger=new SyncRunLedger(path);ledger.createRun(RUN,null,LOGICAL,request);ledger.transition(RUN,0,SyncRunState.RUNNING,"{}");
            staging=new MarginZrzStaging(tables);publication=new MarginZrzPublication(tables,path);journal=new ReferencePublicationJournal(path,"margin_zrz");
            prepared=staging.prepare(TARGET,LOGICAL,PHYSICAL,RUN,request,root,()->false);intent=root.resolve("stage").resolve(prepared.stage()+"-intent.json");
            var typed=row(DAY,5);var rawRow=new LinkedHashMap<String,Object>(MarginZrzRows.values(typed).asMap());rawRow.put("trade_date","20250701");
            var body=new LinkedHashMap<String,Object>();body.put("sourceKind","tushare");body.put("endpoint","slb_len");body.put("sourceContractVersion",1);body.put("parameters",Map.of("start_date","20250701","end_date","20250701"));body.put("fields",MarginZrzSource.FIELDS);
            body.put("fromInclusive",DAY);body.put("toInclusive",DAY);
            body.put("apiMaximumRows",MarginZrzSource.API_ROW_CAP);body.put("returnedRows",1);body.put("rawRows",List.of(rawRow));body.put("sourceComplete",true);
            byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(body);raw=root.resolve("source/raw.json");Files.write(raw,bytes);String sha=FileEvidenceStore.sha256(bytes);
            page=MarginZrzSource.reopen(raw,sha,DAY,DAY);
            staged=snapshot(new Identity(2,"generation-2",8),List.of(row(DAY.minusDays(1),2),typed));tables.data.put(prepared.stage(),staged);
            verified=staging.verify(prepared,List.of(page),()->false);
        }
        MarginZrzPublication.Result publish(BooleanSupplier cancelled)throws Exception {try(var operation=publication.beginOperation(RUN)){return publication.publish(operation,verified,cancelled);}}
    }
    static final class FakeTables implements MarginZrzStagingPort {
        final Map<String,Snapshot> data=new HashMap<>();Path root;Fault fault;int reads,renames,drops,walChecks;boolean sawPreparingIntent;
        @Override public Table open(String table){reads++;return new Table(){
            @Override public Identity preflight(){return data.get(table).identity();}
            @Override public Snapshot snapshot(){return Objects.requireNonNull(data.get(table),table);}
            @Override public Snapshot outside(LocalDate from,LocalDate to)throws Exception {return filtered(from,to,false);}
            @Override public Snapshot window(LocalDate from,LocalDate to)throws Exception {return filtered(from,to,true);}
            private Snapshot filtered(LocalDate from,LocalDate to,boolean inside)throws Exception {var s=snapshot();return MarginZrzProtocolContractTest.snapshot(s.identity(),s.rows().stream().filter(r->(!r.tradeDate().isBefore(from)&&!r.tradeDate().isAfter(to))==inside).toList());}
        };}
        @Override public String logicalTargetId(String table){return LOGICAL;}
        @Override public String physicalTargetId(String table,Identity id){return id.id()==1?PHYSICAL:id.id()==2?STAGED:"static-v2-"+"f".repeat(64);}
        @Override public int tableCount(String table){reads++;return data.containsKey(table)?1:0;}
        @Override public void rename(String source,String target){int n=++renames;if(fault==(n==1?Fault.BEFORE_FIRST:Fault.BEFORE_SECOND))throw new IllegalStateException("before rename");data.put(target,data.remove(source));if(fault==(n==1?Fault.AFTER_FIRST:Fault.AFTER_SECOND))throw new IllegalStateException("after rename");}
        @Override public DiscardSession newDiscardSession(){return new DiscardSession(){
            @Override public int namedStageCount(String stage){return data.containsKey(stage)?1:0;}
            @Override public void dropStage(String stage){drops++;data.remove(stage);}
            @Override public boolean stageExists(String stage){return data.containsKey(stage);}
        };}
        @Override public void createOutsideStage(String stage,String target,String lower,String upper){try {
            var proof=JobDefinitionJson.mapper().readTree(root.resolve("stage").resolve(stage+"-intent.json").toFile());sawPreparingIntent="PREPARING".equals(proof.path("phase").asText());assertTrue(sawPreparingIntent);
            assertEquals("2025-07-01T00:00:00.000000Z",lower);assertEquals("2025-07-02T00:00:00.000000Z",upper);
            var outside=open(target).outside(DAY,DAY);data.put(stage,snapshot(new Identity(2,"generation-2",8),outside.rows()));
        }catch(Exception error){throw new IllegalStateException(error);}}
        @Override public void awaitWal(String table,BooleanSupplier cancelled){walChecks++;}
    }
}
