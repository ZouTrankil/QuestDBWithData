package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.ThsMemberRow;
import com.zoutrankil.data.index.domain.ThsMemberState.*;
import com.zoutrankil.data.index.mapper.ThsMemberMapper;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRunner;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ThsMemberPublicationContractTest {
    private static final String TARGET="java_ths_member_contract",STAGE="java_ths_member_stage_contract",BOARD="885001.TI",RUN="member-run",ID="static-v2-"+"a".repeat(64);
    private static final Instant CLOCK=Instant.parse("2020-01-03T01:02:03.123456Z");
    @TempDir Path temp;
    enum Fault { BEFORE_FIRST,AFTER_FIRST,BEFORE_SECOND,AFTER_SECOND }
    @ParameterizedTest @EnumSource(Fault.class)
    void everyRenameFailureRetainsTheLeaseAndResumesItsActualLayout(Fault fault)throws Exception {
        var h=new Harness(temp);h.tables.fault=fault;
        var error=assertThrows(ThsMemberBoardPublication.Uncertain.class,()->h.publish(()->false));assertEquals(RUN,error.runId());
        assertEquals(ReferencePublicationJournal.State.IN_DOUBT,h.journal.forRun(RUN).state());assertTrue(h.locks.findOwned(RUN,h.lease.scope()).inDoubt());
        assertEquals(switch(fault){case BEFORE_FIRST->ThsMemberBoardPublication.Layout.ORIGINAL;case AFTER_FIRST,BEFORE_SECOND->ThsMemberBoardPublication.Layout.OLD_MOVED;case AFTER_SECOND->ThsMemberBoardPublication.Layout.PUBLISHED;},h.publisher.inspect(RUN));
        h.tables.fault=null;var result=h.publisher.finish(h.lease,true);
        assertEquals(ReferencePublicationJournal.State.VERIFIED,result.publication().state());assertEquals(h.after,result.actual());
        int renamed=h.tables.renames;assertEquals(result,h.publisher.finish(h.lease,true));assertEquals(renamed,h.tables.renames);
        assertEquals(h.before,h.tables.data.get(result.publication().intent().backup()));assertEquals(3,result.actual().otherRows());
        assertTrue(h.locks.findOwned(RUN,h.lease.scope()).inDoubt());
    }
    @Test void stoppedProofPrecedesInspectionAndForeignLayoutNeverRenames()throws Exception {
        var h=new Harness(temp);h.tables.fault=Fault.BEFORE_FIRST;assertThrows(ThsMemberBoardPublication.Uncertain.class,()->h.publish(()->false));h.tables.fault=null;
        assertEquals("Stopped writer proof required",assertThrows(IllegalStateException.class,()->h.publisher.finish(null,false)).getMessage());
        h.tables.data.put(TARGET,new Snapshot(new Identity(999,"foreign",1),BOARD,h.before.boardRows(),3,"others"));int count=h.tables.renames;
        assertEquals(ThsMemberBoardPublication.Layout.CONFLICT,h.publisher.inspect(RUN));assertThrows(IllegalStateException.class,()->h.publisher.finish(h.lease,true));assertEquals(count,h.tables.renames);
    }
    @Test void cancellationAndChangedStageBeforeIntentHaveNoPublicationSideEffects()throws Exception {
        var h=new Harness(temp);assertThrows(CancellationException.class,()->h.publish(()->true));assertTrue(h.journal.findForRun(RUN).isEmpty());
        h.tables.data.put(STAGE,new Snapshot(h.after.identity(),BOARD,h.after.boardRows(),4,"changed"));
        assertThrows(IllegalStateException.class,()->h.publish(()->false));assertTrue(h.journal.findForRun(RUN).isEmpty());assertEquals(0,h.tables.renames);
    }
    @Test void fullStoppedRecoveryChecksSourceAndUnaffectedBoardsThenReleasesTheLease()throws Exception {
        var h=new Harness(temp);h.tables.fault=Fault.AFTER_FIRST;assertThrows(ThsMemberBoardPublication.Uncertain.class,()->h.publish(()->false));h.tables.fault=null;
        h.evidence();var result=ThsMemberRunRecovery.finish(h.tables,h.path,TARGET,RUN,true);
        assertEquals(SyncRunState.VERIFIED,result.state());assertEquals(1,result.sourceRows());assertEquals(3,result.copiedOtherRows());
        for(String id:List.of(RUN,RUN+"-attempt",RUN+"-board"))assertEquals(SyncRunState.VERIFIED,h.ledger.get(id).state());
        assertNull(h.locks.findOwned(RUN,h.lease.scope()));assertEquals(h.after,h.tables.data.get(TARGET));assertTrue(Files.isRegularFile(Path.of(result.evidence())));
    }
    @Test void corruptedProviderReceiptCannotReachPhysicalReconciliation()throws Exception {
        var h=new Harness(temp);h.tables.fault=Fault.BEFORE_FIRST;assertThrows(ThsMemberBoardPublication.Uncertain.class,()->h.publish(()->false));h.tables.fault=null;
        Path source=h.evidence();Files.writeString(source,"{}");int count=h.tables.renames;h.tables.reads=0;
        assertThrows(IllegalStateException.class,()->ThsMemberRunRecovery.finish(h.tables,h.path,TARGET,RUN,true));
        assertEquals(count,h.tables.renames);assertEquals(0,h.tables.reads);assertTrue(h.locks.findOwned(RUN,h.lease.scope()).inDoubt());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void stoppedEmptyNoWriteRecoveryAuthenticatesReceiptCompletesEveryEntryAndReleasesLease(boolean inDoubt)throws Exception {
        var h=new Harness(temp,true);Path source=h.evidence();
        if(inDoubt) {
            for(String id:List.of(RUN,RUN+"-attempt",RUN+"-board"))h.ledger.transition(id,h.ledger.get(id).revision(),SyncRunState.IN_DOUBT,"{}");
            h.locks.retainInDoubt(h.lease);
        }
        var result=ThsMemberRunRecovery.finish(h.tables,h.path,TARGET,RUN,true);
        assertEquals(SyncRunState.VERIFIED_EMPTY,result.state());assertEquals(0,result.sourceRows());assertEquals(0,result.verifiedBoardRows());assertEquals(3,result.copiedOtherRows());assertNull(result.publicationId());assertNull(result.errorCode());
        for(String id:List.of(RUN,RUN+"-attempt",RUN+"-board")) {
            var entry=h.ledger.get(id);assertEquals(SyncRunState.VERIFIED_EMPTY,entry.state());
            var payload=JobDefinitionJson.mapper().readTree(entry.payloadJson());
            assertTrue(payload.path("sourceComplete").isBoolean());assertTrue(payload.path("sourceComplete").booleanValue());
            assertTrue(payload.path("returnedRows").isIntegralNumber());assertEquals(0,payload.path("returnedRows").longValue());
            assertTrue(payload.path("submittedRows").isIntegralNumber());assertEquals(0,payload.path("submittedRows").longValue());
            assertEquals(source.toAbsolutePath().toString(),payload.path("responseEvidence").textValue());
            var verification=payload.path("verification");assertTrue(verification.path("passed").booleanValue());assertTrue(verification.path("writerStopped").booleanValue());
            for(String count:List.of("expectedRows","actualRows","matchedRows","mismatchedRows","duplicateKeys","missingKeys")) {
                assertTrue(verification.path(count).isIntegralNumber());assertEquals(0,verification.path(count).longValue());
            }
            assertEquals(FileEvidenceStore.sha256(Files.readAllBytes(source)),verification.path("sourceFingerprint").textValue());
            assertEquals(result.evidence(),verification.path("readbackEvidence").textValue());
        }
        assertNull(h.locks.findOwned(RUN,h.lease.scope()));assertTrue(h.journal.findForRun(RUN).isEmpty());
        assertEquals(Map.of(TARGET,h.before),h.tables.data);assertEquals(0,h.tables.renames);assertEquals(0,h.tables.stagePrepares);assertEquals(0,h.tables.stageWrites);assertEquals(0,h.tables.publicationSessions);
        var receipt=JobDefinitionJson.mapper().readTree(Path.of(result.evidence()).toFile());
        assertEquals(0,receipt.path("sourceRows").intValue());assertEquals(3,receipt.path("copiedOtherRows").intValue());
        assertFalse(receipt.has("sourceComplete"));assertFalse(receipt.has("returnedRows"));assertFalse(receipt.has("submittedRows"));assertFalse(receipt.has("responseEvidence"));
    }
    @Test void stoppedRunProofPrecedesTargetAndPathAccess() {
        assertEquals("Stopped THS member writer proof required",assertThrows(IllegalStateException.class,()->ThsMemberRunRecovery.finish(null,null,null,null,false)).getMessage());
    }
    private static final class Harness {
        final Path path;final SyncRunLedger ledger;final DatasetIntervalLock locks;final DatasetIntervalLock.Lease lease;
        final FakeTables tables=new FakeTables();final ThsMemberBoardPublication publisher;final ReferencePublicationJournal journal;
        final ThsMember row=new ThsMember(BOARD,"000001.SZ","new",2.0,LocalDate.of(2010,1,1),null,"Y",CLOCK);
        final Snapshot before,after;final Prepared prepared;final SyncJobDefinition.FrozenRequest request;
        Harness(Path folder)throws Exception {this(folder,false);}
        Harness(Path folder,boolean empty)throws Exception {
            path=folder.resolve("ledger.sqlite3");request=ThsMemberJobService.definition().freeze(null,Map.of("board_code",BOARD),LocalDate.of(2020,1,3),LocalDate.of(2020,1,3),LocalDate.of(2020,1,3));
            ledger=new SyncRunLedger(path);ledger.createRun(RUN,null,ID,request);ledger.transition(RUN,0,SyncRunState.RUNNING,"{}");
            ledger.createChild(RUN+"-attempt",SyncRunLedger.Kind.ATTEMPT,RUN,RUN);ledger.transition(RUN+"-attempt",0,SyncRunState.RUNNING,"{}");
            ledger.createChild(RUN+"-board",SyncRunLedger.Kind.SLICE,RUN,RUN+"-attempt");ledger.transition(RUN+"-board",0,SyncRunState.RUNNING,"{}");
            locks=new DatasetIntervalLock(path);lease=locks.acquire(RUN,DatasetIntervalLock.Scope.allDates("ths_member"));assertNotNull(lease);
            before=new Snapshot(new Identity(1,"g1",7),BOARD,empty?List.of():List.of(new ThsMemberRow(BOARD,"000001.SZ","old",1.0,"20100101",null,"Y",CLOCK.minusSeconds(1))),3,"other-board-hash");
            prepared=new Prepared(TARGET,BOARD,before,empty?List.of():List.of(row));after=empty?before:new Snapshot(new Identity(2,"g2",9),BOARD,List.of(new ThsMemberMapper().toStorage(row)),3,"other-board-hash");
            tables.data.put(TARGET,before);if(!empty)tables.data.put(STAGE,after);publisher=new ThsMemberBoardPublication(tables,path);journal=new ReferencePublicationJournal(path,"ths_member");
        }
        ThsMemberBoardPublication.Result publish(java.util.function.BooleanSupplier cancelled)throws Exception{return publisher.publish(lease,prepared,new Verified(STAGE,after,1,"stage-receipt"),cancelled);}
        Path evidence()throws Exception {
            Path folder=path.getParent().resolve("sync-evidence").resolve(RUN);Files.createDirectories(folder);Path source=folder.resolve("source.json");
            var raw=new LinkedHashMap<String,Object>();raw.put("ts_code",BOARD);raw.put("con_code","000001.SZ");raw.put("con_name","new");raw.put("weight",2.0);raw.put("in_date","20100101");raw.put("out_date",null);raw.put("is_new","Y");
            byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(Map.of("endpoint","ths_member","parameters",Map.of("ts_code",BOARD),"fields",ThsMemberSource.FIELDS,"observedAt",CLOCK,"completion",Map.of("pages",1,"rows",prepared.source().size()),"rows",prepared.source().isEmpty()?List.of():List.of(raw)));
            Files.write(source,bytes);var page=new SyncJobRunner.Page<>(prepared.source(),FileEvidenceStore.sha256(bytes),source.toAbsolutePath().toString(),null);
            Files.write(folder.resolve("prepared.json"),JobDefinitionJson.mapper().writeValueAsBytes(Map.of("runId",RUN,"targetId",ID,"request",SyncRequestIdentity.snapshotJson(request),"source",page,"prepared",prepared)));return source;
        }
    }
    private static final class FakeTables implements ThsMemberTarget {
        final Map<String,Snapshot> data=new HashMap<>();Fault fault;int renames,reads,stagePrepares,stageWrites,publicationSessions;
        public String tableName(){return TARGET;}
        public Table open(String table){return new Table(){public Identity preflight(){return data.get(table).identity();}public Snapshot snapshot(String board){assertEquals(BOARD,board);reads++;return Objects.requireNonNull(data.get(table));}};}
        public String identify(String table,long id,String dir){return ID;}
        public Snapshot snapshotIfPresent(String table,String board){assertEquals(BOARD,board);reads++;return data.get(table);}
        public void rename(String from,String to){int call=++renames;if((call==1&&fault==Fault.BEFORE_FIRST)||(call==2&&fault==Fault.BEFORE_SECOND))throw new IllegalStateException("before rename");assertFalse(data.containsKey(to));data.put(to,Objects.requireNonNull(data.remove(from)));if((call==1&&fault==Fault.AFTER_FIRST)||(call==2&&fault==Fault.AFTER_SECOND))throw new IllegalStateException("after rename");}
        public StageWriter newStaging(){return new StageWriter(){public Prepared prepare(String t,String b,List<ThsMember> rows){stagePrepares++;throw new AssertionError();}public boolean requiresWrite(Prepared p){return !p.before().boardRows().equals(p.source().stream().map(new ThsMemberMapper()::toStorage).toList());}public Verified write(Prepared p,Path path,java.util.function.BooleanSupplier c){stageWrites++;throw new AssertionError("Existing publication recovery must not stage again");}};}
        public ThsMemberTables publicationTables(){publicationSessions++;return this;}
    }
}
