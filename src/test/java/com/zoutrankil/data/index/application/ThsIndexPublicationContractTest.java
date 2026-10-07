package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.ThsIndexRow;
import com.zoutrankil.data.index.domain.ThsIndexState.*;
import com.zoutrankil.data.index.domain.policy.ThsIndexMerge;
import com.zoutrankil.data.index.mapper.ThsIndexMapper;
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
import static org.junit.jupiter.api.Assertions.*;

class ThsIndexPublicationContractTest {
    private static final String TARGET="java_ths_index_contract",STAGE="java_ths_index_stage_contract",RUN="index-run",ID="static-v2-"+"a".repeat(64);
    private static final Instant CLOCK=Instant.parse("2020-01-03T01:02:03.123456Z");
    @TempDir Path temp;
    enum Fault { BEFORE_FIRST,AFTER_FIRST,BEFORE_SECOND,AFTER_SECOND }
    @ParameterizedTest @EnumSource(Fault.class)
    void everyRenameFailureKeepsEvidenceAndRecoversFromItsPhysicalLayout(Fault fault)throws Exception {
        var h=new Harness(temp);h.tables.fault=fault;
        var error=assertThrows(ThsIndexPublication.Uncertain.class,()->h.publish(()->false));assertEquals(RUN,error.runId());
        assertEquals(ReferencePublicationJournal.State.IN_DOUBT,h.journal.forRun(RUN).state());
        assertTrue(h.locks.findOwned(RUN,h.lease.scope()).inDoubt());
        assertEquals(switch(fault){case BEFORE_FIRST->ThsIndexPublication.Layout.ORIGINAL;case AFTER_FIRST,BEFORE_SECOND->ThsIndexPublication.Layout.OLD_MOVED;case AFTER_SECOND->ThsIndexPublication.Layout.PUBLISHED;},h.publisher.inspect(RUN));
        h.tables.fault=null;var result=h.publisher.finish(h.lease,true);
        assertEquals(ReferencePublicationJournal.State.VERIFIED,result.publication().state());assertEquals(h.after,result.actual());
        int renamed=h.tables.renames;assertEquals(result,h.publisher.finish(h.lease,true));assertEquals(renamed,h.tables.renames);
        assertEquals(h.before,h.tables.data.get(result.publication().intent().backup()));
        assertTrue(h.locks.findOwned(RUN,h.lease.scope()).inDoubt()); // Owner releases after ledger reconciliation.
    }
    @Test void stoppedProofPrecedesInspectionAndForeignLayoutsNeverRename()throws Exception {
        var h=new Harness(temp);h.tables.fault=Fault.BEFORE_FIRST;assertThrows(ThsIndexPublication.Uncertain.class,()->h.publish(()->false));h.tables.fault=null;
        assertEquals("Stopped writer proof required",assertThrows(IllegalStateException.class,()->h.publisher.finish(null,false)).getMessage());
        h.tables.data.put(TARGET,snapshot(999,h.before.rows()));int calls=h.tables.renames;
        assertEquals(ThsIndexPublication.Layout.CONFLICT,h.publisher.inspect(RUN));
        assertThrows(IllegalStateException.class,()->h.publisher.finish(h.lease,true));assertEquals(calls,h.tables.renames);
    }
    @Test void changedStageAndInitialCancellationDoNotCreatePublicationIntents()throws Exception {
        var h=new Harness(temp);
        assertThrows(CancellationException.class,()->h.publish(()->true));assertTrue(h.journal.findForRun(RUN).isEmpty());assertEquals(0,h.tables.renames);
        h.tables.data.put(STAGE,snapshot(2,h.before.rows()));
        assertThrows(IllegalStateException.class,()->h.publish(()->false));assertTrue(h.journal.findForRun(RUN).isEmpty());assertEquals(0,h.tables.renames);
    }
    @Test void fullStoppedRunRecoveryReopensTheSourceAndCompletesAllLedgerEntries()throws Exception {
        var h=new Harness(temp);h.tables.fault=Fault.AFTER_FIRST;assertThrows(ThsIndexPublication.Uncertain.class,()->h.publish(()->false));h.tables.fault=null;
        h.evidence();var result=ThsIndexRunRecovery.finish(h.tables,h.path,TARGET,RUN,true);
        assertEquals(SyncRunState.VERIFIED,result.state());assertEquals(1,result.sourceRows());assertEquals(1,result.revised());
        for(String id:List.of(RUN,RUN+"-attempt",RUN+"-snapshot"))assertEquals(SyncRunState.VERIFIED,h.ledger.get(id).state());
        assertNull(h.locks.findOwned(RUN,h.lease.scope()));assertEquals(h.after,h.tables.data.get(TARGET));assertTrue(Files.isRegularFile(Path.of(result.evidence())));
    }
    @Test void corruptedRawReceiptIsRejectedBeforePhysicalInspectionOrRename()throws Exception {
        var h=new Harness(temp);h.tables.fault=Fault.BEFORE_FIRST;assertThrows(ThsIndexPublication.Uncertain.class,()->h.publish(()->false));h.tables.fault=null;
        var source=h.evidence();Files.writeString(source,"{}");int calls=h.tables.renames;h.tables.reads=0;
        assertEquals("THS source receipt hash changed",assertThrows(IllegalStateException.class,()->ThsIndexRunRecovery.finish(h.tables,h.path,TARGET,RUN,true)).getMessage());
        assertEquals(calls,h.tables.renames);assertEquals(0,h.tables.reads);assertTrue(h.locks.findOwned(RUN,h.lease.scope()).inDoubt());
    }
    @Test void stoppedRunProofIsCheckedBeforeAnyPathOrTargetAccess() {
        assertEquals("Stopped THS writer proof required",assertThrows(IllegalStateException.class,()->ThsIndexRunRecovery.finish(null,null,null,null,false)).getMessage());
    }
    private static Snapshot snapshot(long id,List<ThsIndexRow> rows)throws Exception {
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(rows);return new Snapshot(new Identity(id,"g"+id),rows,FileEvidenceStore.sha256(bytes),bytes.length);
    }
    private static final class Harness {
        final Path path;final SyncRunLedger ledger;final DatasetIntervalLock locks;final DatasetIntervalLock.Lease lease;
        final FakeTables tables=new FakeTables();final ThsIndexPublication publisher;final ReferencePublicationJournal journal;
        final ThsIndex row=new ThsIndex("885001.TI","new",2,"A",LocalDate.of(2010,1,1),"N",CLOCK);
        final Snapshot before,after;final Prepared prepared;final SyncJobDefinition.FrozenRequest request;
        Harness(Path folder)throws Exception {
            path=folder.resolve("ledger.sqlite3");request=ThsIndexJobService.definition().freeze(null,Map.of(),LocalDate.of(2020,1,3),LocalDate.of(2020,1,3),LocalDate.of(2020,1,3));
            ledger=new SyncRunLedger(path);ledger.createRun(RUN,null,ID,request);ledger.transition(RUN,0,SyncRunState.RUNNING,"{}");
            ledger.createChild(RUN+"-attempt",SyncRunLedger.Kind.ATTEMPT,RUN,RUN);ledger.transition(RUN+"-attempt",0,SyncRunState.RUNNING,"{}");
            ledger.createChild(RUN+"-snapshot",SyncRunLedger.Kind.SLICE,RUN,RUN+"-attempt");ledger.transition(RUN+"-snapshot",0,SyncRunState.RUNNING,"{}");
            locks=new DatasetIntervalLock(path);lease=locks.acquire(RUN,DatasetIntervalLock.Scope.allDates("ths_index"));assertNotNull(lease);
            before=snapshot(1,List.of(new ThsIndexRow("885001.TI","old",2,"A","20100101","N",CLOCK.minusSeconds(1))));
            prepared=tables.prepare(before,List.of(row),Scope.all());after=snapshot(2,prepared.rows());tables.data.put(TARGET,before);tables.data.put(STAGE,after);
            publisher=new ThsIndexPublication(tables,path);journal=new ReferencePublicationJournal(path,"ths_index");
        }
        ThsIndexPublication.Result publish(java.util.function.BooleanSupplier cancelled)throws Exception{return publisher.publish(lease,TARGET,prepared,new Verified(STAGE,after,"stage-receipt",1),cancelled);}
        Path evidence()throws Exception {
            Path folder=path.getParent().resolve("sync-evidence").resolve(RUN);Files.createDirectories(folder);Path source=folder.resolve("source.json");
            byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(Map.of("endpoint","ths_index","parameters",Map.of(),"observedAt",CLOCK,"completion",Map.of("pages",1,"rows",1),"rows",List.of(Map.of("ts_code",row.tsCode(),"name",row.name(),"count",row.memberCount(),"exchange",row.exchange(),"list_date","20100101","type",row.indexType()))));
            Files.write(source,bytes);var page=new SyncJobRunner.Page<>(List.of(row),FileEvidenceStore.sha256(bytes),source.toAbsolutePath().toString(),null);
            Files.write(folder.resolve("prepared.json"),JobDefinitionJson.mapper().writeValueAsBytes(Map.of("runId",RUN,"targetId",ID,"request",SyncRequestIdentity.snapshotJson(request),"source",page,"prepared",prepared)));return source;
        }
    }
    private static final class FakeTables implements ThsIndexTarget {
        final Map<String,Snapshot> data=new HashMap<>();Fault fault;int renames,reads;
        public String tableName(){return TARGET;}
        public Table open(String table){return new Table(){public Identity preflight(){return data.get(table).identity();}public Snapshot snapshot(){reads++;return Objects.requireNonNull(data.get(table));}};}
        public String identify(String table,long id,String dir){return ID;}
        public Snapshot snapshotIfPresent(String table,String message){reads++;return data.get(table);}
        public void rename(String from,String to){int call=++renames;if((call==1&&fault==Fault.BEFORE_FIRST)||(call==2&&fault==Fault.BEFORE_SECOND))throw new IllegalStateException("before rename");assertFalse(data.containsKey(to));data.put(to,Objects.requireNonNull(data.remove(from)));if((call==1&&fault==Fault.AFTER_FIRST)||(call==2&&fault==Fault.AFTER_SECOND))throw new IllegalStateException("after rename");}
        public Prepared prepare(Snapshot before,List<ThsIndex> source,Scope scope){var merge=ThsIndexMerge.merge(before.businessRows(),source,scope);return new Prepared(before,source,scope,merge.rows().stream().map(new ThsIndexMapper()::toStorage).toList(),merge);}
        public Prepared preparePrepared(Snapshot before,List<ThsIndex> source){var merge=ThsIndexMerge.mergePrepared(before.businessRows(),source);return new Prepared(before,source,null,merge.rows().stream().map(new ThsIndexMapper()::toStorage).toList(),merge);}
        public StageWriter newStaging(){throw new AssertionError("Existing publication recovery must not stage again");}
        public ThsIndexTables publicationTables(){return this;}
    }
}
