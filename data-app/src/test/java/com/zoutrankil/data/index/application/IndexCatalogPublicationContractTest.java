package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import com.zoutrankil.data.index.domain.IndexCatalogState.*;
import com.zoutrankil.data.index.mapper.IndexCatalogMapper;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.storage.IndexCatalogStaging;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;
import static org.junit.jupiter.api.Assertions.*;

/** Real application recovery, evidence, SQLite journal and leases; physical tables are offline fakes. */
class IndexCatalogPublicationContractTest {
    @TempDir Path temp;
    private static final String TABLE="java_index_contract";
    private static final Instant OBSERVED=Instant.parse("2026-09-29T01:02:03.000004Z");
    private static final class AbruptStop extends Error {}
    private enum RenameFault { BEFORE_FIRST, AFTER_FIRST, AFTER_SECOND }

    @ParameterizedTest @EnumSource(value=State.class,names={"PREPARED","OLD_MOVED","PUBLISHED"})
    void durablePhaseStopReopensAndCompletesWithoutRepeatingRename(State phase) throws Exception {
        var f=new Fixture(temp,false);
        var publisher=new IndexCatalogPublication(f.tables,f.path,state->{if(state==phase)throw new AbruptStop();});
        assertThrows(AbruptStop.class,()->publisher.publish(f.lease,TABLE,f.prepared,f.stage,()->false));
        assertEquals(phase,f.entry().state());
        assertEquals(SyncRunState.RUNNING,f.ledger.get(f.run).state());
        int completedRenames=f.tables.renames.size();
        assertThrows(IllegalStateException.class,()->IndexCatalogRunRecovery.finish(f.tables,f.path,TABLE,f.run,false));
        assertEquals(completedRenames,f.tables.renames.size());
        var result=f.finish();
        assertEquals(SyncRunState.VERIFIED,result.state());assertEquals(1,result.verifiedRows());
        f.assertCompleted();assertEquals(0,f.tables.stagingWrites);
    }

    @ParameterizedTest @EnumSource(RenameFault.class)
    void lostAcknowledgementRetainsLeaseAndRecoversExactLayout(RenameFault fault) throws Exception {
        var f=new Fixture(temp,false);f.tables.fault=fault;
        var publisher=new IndexCatalogPublication(f.tables,f.path);
        var failure=assertThrows(IndexCatalogPublication.Uncertain.class,
                ()->publisher.publish(f.lease,TABLE,f.prepared,f.stage,()->false));
        assertEquals(f.run,failure.runId());assertEquals(State.IN_DOUBT,f.entry().state());
        assertTrue(f.locks.findOwned(f.run,f.lease.scope()).inDoubt());
        assertEquals(switch(fault){case BEFORE_FIRST->IndexCatalogPublication.Layout.ORIGINAL;
            case AFTER_FIRST->IndexCatalogPublication.Layout.OLD_MOVED;case AFTER_SECOND->IndexCatalogPublication.Layout.PUBLISHED;},publisher.inspect(f.run));
        f.tables.fault=null;f.finish();f.assertCompleted();
    }

    @Test void physicalPublicationAloneDoesNotCompleteRunOrReleaseLease() throws Exception {
        var f=new Fixture(temp,false);var publisher=new IndexCatalogPublication(f.tables,f.path);
        var result=publisher.publish(f.lease,TABLE,f.prepared,f.stage,()->false);
        assertEquals(State.VERIFIED,result.publication().state());
        assertEquals(SyncRunState.RUNNING,f.ledger.get(f.run).state());
        assertNotNull(f.locks.findOwned(f.run,f.lease.scope()));
        assertFalse(Files.exists(f.evidence.resolve("completion.json")));
        assertEquals(result,publisher.finish(f.lease,true));
        assertEquals(2,f.tables.renames.size());
        f.finish();f.assertCompleted();
    }

    @Test void frozenSourceTamperingRejectsBeforeFurtherRename() throws Exception {
        var f=new Fixture(temp,false);
        var publisher=new IndexCatalogPublication(f.tables,f.path,state->{if(state==State.OLD_MOVED)throw new AbruptStop();});
        assertThrows(AbruptStop.class,()->publisher.publish(f.lease,TABLE,f.prepared,f.stage,()->false));
        Files.writeString(f.sourceReceipt,"{}");var before=f.entry();
        assertThrows(IllegalStateException.class,f::finish);
        assertEquals(before,f.entry());assertEquals(1,f.tables.renames.size());
        assertNotNull(f.locks.findOwned(f.run,f.lease.scope()));assertFalse(Files.exists(f.evidence.resolve("completion.json")));
    }

    @Test void endpointAndConflictingTableNeverAuthorizeRecoveryRename() throws Exception {
        var f=new Fixture(temp,false);
        var publisher=new IndexCatalogPublication(f.tables,f.path,state->{if(state==State.OLD_MOVED)throw new AbruptStop();});
        assertThrows(AbruptStop.class,()->publisher.publish(f.lease,TABLE,f.prepared,f.stage,()->false));
        f.tables.endpoint="jdbc:postgresql://other:8812/qdb";
        assertThrows(IllegalStateException.class,f::finish);assertEquals(1,f.tables.renames.size());
        f.tables.endpoint=FakeTarget.ENDPOINT;f.tables.tables.put(TABLE,snapshot(99,f.before.rows()));
        assertThrows(IllegalStateException.class,f::finish);assertEquals(1,f.tables.renames.size());
        assertEquals(State.OLD_MOVED,f.entry().state());assertNotNull(f.locks.findOwned(f.run,f.lease.scope()));
    }

    @Test void rebuildUsesFrozenRowsInFreshStageAndPreservesUnrelatedPartialTable() throws Exception {
        var f=new Fixture(temp,false);f.tables.tables.remove(f.stage.table());
        f.tables.tables.put("java_unrelated_partial",f.before);f.locks.retainInDoubt(f.lease);
        var result=f.finish();assertEquals(SyncRunState.VERIFIED,result.state());
        assertEquals(1,f.tables.stagingWrites);assertEquals(f.evidence,f.tables.stagingEvidence);
        assertEquals(f.before,f.tables.tables.get("java_unrelated_partial"));f.assertCompleted();
    }

    @Test void noWriteRecoveryVerifiesExistingRowsWithoutStageOrPublication() throws Exception {
        var f=new Fixture(temp,true);assertFalse(f.prepared.merge().requiresWrite());
        var result=f.finish();assertEquals(SyncRunState.VERIFIED,result.state());
        assertEquals(0,f.tables.stagingWrites);assertTrue(f.tables.renames.isEmpty());
        assertTrue(new ReferencePublicationJournal(f.path,"index").findForRun(f.run).isEmpty());
        assertNull(f.locks.findOwned(f.run,f.lease.scope()));assertEquals(f.before,f.tables.tables.get(TABLE));
    }

    private static final class Fixture {
        final Path path,evidence,sourceReceipt;final String run="reference-contract";
        final SyncRunLedger ledger;final DatasetIntervalLock locks;final DatasetIntervalLock.Lease lease;
        final FakeTarget tables=new FakeTarget();final Snapshot before;final Prepared prepared;final Verified stage;
        Fixture(Path temp,boolean unchanged) throws Exception {
            path=temp.resolve("ledger.sqlite");evidence=temp.resolve("sync-evidence").resolve(run);Files.createDirectories(evidence);
            var source=List.of(row("000001","new"));var mapper=new IndexCatalogMapper();
            before=snapshot(11,(unchanged?source:List.of(row("000002","retained"))).stream().map(mapper::toStorage).toList());
            tables.tables.put(TABLE,before);prepared=tables.prepare(before,source);
            var replacement=snapshot(22,prepared.rows());stage=verified("java_index_stage_contract",replacement,evidence);
            tables.tables.put(stage.table(),replacement);
            var batch=DatasetWritePreparation.prepareWalReplace(IndexCatalogDataset.DEFINITION,source,mapper::values,
                    new DatasetWritePreparation.Limits(3999,8*1024*1024));
            var parameter=new Parameter(ParameterType.STRING,true,128,1,Set.of());
            var definition=new SyncJobDefinition("write.index",1,"index",1,"index_catalog_owner",Set.of(Mode.INGEST),Mode.INGEST,
                    Map.of("groupBatch",parameter,"memberBatch",parameter,"planFingerprint",parameter,"payloadFingerprint",parameter),
                    "prepared.local","prepared.static","questdb.full_key_values",
                    new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(30)),Duration.ofMinutes(20),
                    new Budget(1,1,1,1,8*1024*1024),0,List.of(),Frequency.MANUAL,ZoneOffset.UTC,true,false);
            var request=definition.freeze(null,Map.of("groupBatch","group","memberBatch","member","planFingerprint","p".repeat(64),
                    "payloadFingerprint",batch.fingerprint()),null,null,LocalDate.of(2026,9,29));
            String target=tables.identify(TABLE,before.identity().id(),before.identity().directory());
            sourceReceipt=evidence.resolve("prepared-input.json");var json=JobDefinitionJson.mapper();
            byte[] sourceBytes=json.writeValueAsBytes(Map.of("sourceKind","prepared-write-request","targetId",target,
                    "fingerprint",batch.fingerprint(),"rows",batch.rows()));Files.write(sourceReceipt,sourceBytes);
            String sourceHash=FileEvidenceStore.sha256(sourceBytes);
            var input=new IndexCatalogFileSource.Input(sourceReceipt.toString(),sourceHash,sourceBytes.length,source);
            Files.writeString(evidence.resolve("prepared.json"), json.writeValueAsString(Map.of(
                    "runId",run,"targetId",target,"request",SyncRequestIdentity.snapshotJson(request),
                    "source",input,"prepared",prepared)));
            ledger=new SyncRunLedger(path);locks=new DatasetIntervalLock(path);ledger.createRun(run,null,target,request);running(run);
            ledger.createChild(run+"-attempt",SyncRunLedger.Kind.ATTEMPT,run,run);running(run+"-attempt");
            ledger.createChild(run+"-snapshot",SyncRunLedger.Kind.SLICE,run,run+"-attempt");running(run+"-snapshot");
            lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("index"));assertNotNull(lease);
        }
        void running(String id) throws Exception {ledger.transition(id,ledger.get(id).revision(),SyncRunState.RUNNING,"{}");}
        ReferencePublicationJournal.Entry entry() throws Exception {return new ReferencePublicationJournal(path,"index").forRun(run);}
        IndexCatalogJobService.Result finish() throws Exception {return IndexCatalogRunRecovery.finish(tables,path,TABLE,run,true);}
        void assertCompleted() throws Exception {
            assertEquals(State.VERIFIED,entry().state());assertEquals(2,tables.renames.size());
            assertEquals(prepared.rows(),tables.tables.get(TABLE).rows());assertEquals(before,tables.tables.get(entry().intent().backup()));
            assertFalse(tables.tables.containsKey(entry().intent().stage()));
            for(String id:List.of(run,run+"-attempt",run+"-snapshot"))assertEquals(SyncRunState.VERIFIED,ledger.get(id).state());
            assertNull(locks.findOwned(run,lease.scope()));assertTrue(Files.isRegularFile(evidence.resolve("completion.json")));
        }
    }
    private static final class FakeTarget implements IndexCatalogTarget {
        static final String ENDPOINT="jdbc:postgresql://localhost:8812/qdb";
        String endpoint=ENDPOINT;final Map<String,Snapshot> tables=new HashMap<>();final List<String> renames=new ArrayList<>();
        RenameFault fault;int stagingWrites;Path stagingEvidence;
        public String tableName(){return TABLE;}
        public Table open(String table){return new Table(){public Identity preflight(){return tables.get(table).identity();}
            public Snapshot snapshot(){return Objects.requireNonNull(tables.get(table),table);}};}
        public String identify(String table,long id,String directory){return com.zoutrankil.data.domain.policy.StaticTargetIdentity.identify(endpoint,table,id,directory);}
        public boolean exists(String table){return tables.containsKey(table);}
        public boolean walSettled(String table){return true;}
        public IndexCatalogTables publicationTables(){return this;}
        public Prepared prepare(Snapshot before,List<IndexCatalogEntry> source) throws Exception {return IndexCatalogStaging.prepare(before, source);}

        public IndexCatalogStagingPort newStaging(){return (prepared,evidence,cancelled)->{
            assertFalse(cancelled.getAsBoolean());stagingWrites++;stagingEvidence=evidence;
            String name="java_rebuilt_"+stagingWrites;var snapshot=snapshot(100+stagingWrites,prepared.rows());tables.put(name,snapshot);
            return verified(name,snapshot,evidence);};}
        public void rename(String from,String to){
            if(fault==RenameFault.BEFORE_FIRST && renames.isEmpty())throw new IllegalStateException("before rename");
            assertFalse(tables.containsKey(to));tables.put(to,Objects.requireNonNull(tables.remove(from)));renames.add(from+"->"+to);
            if(fault==RenameFault.AFTER_FIRST && renames.size()==1 || fault==RenameFault.AFTER_SECOND && renames.size()==2)
                throw new IllegalStateException("rename response lost");
        }
    }
    private static Snapshot snapshot(long id,List<IndexRow> rows) throws Exception {
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(rows);
        return new Snapshot(new Identity(id,"generation-"+id),rows,FileEvidenceStore.sha256(bytes),bytes.length);
    }
    private static Verified verified(String name,Snapshot snapshot,Path evidence){return new Verified(name,snapshot,evidence.resolve("stage.json").toString(),1);}
    private static IndexCatalogEntry row(String code,String name){return new IndexCatalogEntry(code,"short",name,LocalDate.of(2000,1,1),100.0,"series",10.0,
                120.0,-0.0,"stock",null,"CNY",null,null,null,"category",LocalDate.of(2001,1,1),OBSERVED);}
}
