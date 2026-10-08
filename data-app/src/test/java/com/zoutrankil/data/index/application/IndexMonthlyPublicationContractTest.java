package com.zoutrankil.data.index.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.domain.IndexMonthlyState.*;
import com.zoutrankil.data.index.mapper.IndexMonthlyMapper;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual D022 staging evidence, SQLite publication and owner recovery over in-memory physical tables. */
class IndexMonthlyPublicationContractTest {
    @TempDir Path temp;
    static final String TABLE="java_d022_index_monthly_contract", RUN="monthly-contract", CODE="000300.SH";
    static final String LOGICAL="static-v2-"+"a".repeat(64);
    static final LocalDate FROM=LocalDate.of(2026,1,1), TO=LocalDate.of(2026,1,31);
    static final Instant OBSERVED=Instant.parse("2026-02-01T01:02:03.123456Z");
    enum Fault { BEFORE_FIRST, AFTER_FIRST, AFTER_SECOND }
    static final class Stop extends Error {}

    @ParameterizedTest @EnumSource(Fault.class)
    void hardStopLayoutsFinishThroughOwnerWithoutRepeatingRenames(Fault fault)throws Exception {
        var f=new Fixture(temp);f.tables.fault=fault;
        assertThrows(Stop.class,f::publish);f.operation.close();
        int renamed=f.tables.renames;
        assertThrows(IllegalStateException.class,()->f.owner.finishInterrupted(RUN,false));
        assertEquals(renamed,f.tables.renames);f.tables.fault=null;
        var result=f.owner.finishInterrupted(RUN,true);
        assertEquals(ReferencePublicationJournal.State.VERIFIED,result.entry().state());
        assertEquals(f.page.rows(),result.target().rows());
        assertEquals(f.before.rows(),result.backup().rows());
        assertEquals(2,f.tables.renames);f.assertFinished();
        assertEquals(1,f.pages.calls);
        assertEquals(1,f.tables.stageCreates);
    }
    @Test void stageOnlyRecoveryRequiresReadyIntentAndFinishesExactExistingStage()throws Exception {
        var f=new Fixture(temp);f.operation.close();
        assertTrue(f.publisher.findForRun(RUN).isEmpty());
        var result=f.owner.finishInterrupted(RUN,true);
        assertEquals(f.page.rows(),result.target().rows());
        assertEquals(2,f.tables.renames);assertEquals(1,f.tables.stageCreates);f.assertFinished();
        assertEquals(1,f.pages.calls);
    }
    @Test void changedRawSourceAfterJournalCommitCannotAuthorizeRecoveryRename()throws Exception {
        var f=new Fixture(temp);f.tables.fault=Fault.AFTER_FIRST;
        assertThrows(Stop.class,f::publish);f.operation.close();
        var entry=f.publisher.findForRun(RUN).orElseThrow();
        Files.writeString(Path.of(f.page.responseEvidence()),"\n",StandardOpenOption.APPEND);f.tables.fault=null;
        assertThrows(IllegalStateException.class,()->f.owner.finishInterrupted(RUN,true));
        assertEquals(entry,f.publisher.findForRun(RUN).orElseThrow());assertEquals(1,f.tables.renames);
        assertNotNull(f.locks.findOwned(RUN,new DatasetIntervalLock.Scope("index_monthly",FROM,TO)));
        assertEquals(SyncRunState.RUNNING,f.ledger.get(RUN).state());
    }
    @Test void partialStageIsNotPublishedByStageOnlyRecovery()throws Exception {
        var f=new Fixture(temp);f.operation.close();f.tables.rows.put(f.prepared.stage(),List.of());
        assertThrows(IllegalStateException.class,()->f.owner.finishInterrupted(RUN,true));
        assertEquals(0,f.tables.renames);assertTrue(f.publisher.findForRun(RUN).isEmpty());
        assertNotNull(f.locks.findOwned(RUN,new DatasetIntervalLock.Scope("index_monthly",FROM,TO)));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void emptySourceSkipsPublicationOnlyWhenItDoesNotOmitExistingKeys(boolean existing)throws Exception {
        Path path=temp.resolve("empty.sqlite"), evidence=temp.resolve("sync-evidence/empty");
        var tables=new FakeTables();var target=mock(IndexMonthlyTarget.class);
        when(target.newPublicationTables()).thenReturn(tables);
        var session=mock(IndexMonthlyWriteSession.class);
        when(session.readRange(CODE,FROM,TO)).thenReturn(existing?List.of(sample()):List.of());
        var source=new IndexMonthlySource(new Pages(true),new IndexMonthlyMapper(),evidence.resolve("source"));
        var adapter=new IndexMonthlySyncAdapter(source,session,evidence,"empty",path,TABLE,LOGICAL,target);
        var seen=new ArrayList<SyncJobRunner.Page<IndexMonthly>>();
        if(existing){
            var failure=assertThrows(IllegalStateException.class,()->adapter.fetch(request(),seen::add,()->false));
            assertEquals("D022 source omitted an existing physical business key; fail closed for deletion review",failure.getMessage());
            assertTrue(seen.isEmpty());
        }else{
            var completion=adapter.fetch(request(),seen::add,()->false);
            assertEquals(0,completion.rows());assertTrue(completion.complete());assertEquals(1,seen.size());
            assertEquals("no-op-empty-window",JobDefinitionJson.mapper().readTree(Path.of(completion.evidence()).toFile()).path("publicationId").asText());
        }
        assertEquals(0,tables.renames);verify(target,never()).newStaging();verify(session).readRange(CODE,FROM,TO);verifyNoMoreInteractions(session);
    }
    static SyncJobDefinition.FrozenRequest request(){return IndexMonthlySyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.BACKFILL,
            Map.of("targetId",LOGICAL,"physicalTargetId",physical(1),"tsCode",CODE,"observedAt",OBSERVED.toString()),FROM,TO,TO.plusDays(1));}
    static final class Fixture {
        final Path path,evidence;final SyncRunLedger ledger;final DatasetIntervalLock locks;
        final FakeTables tables=new FakeTables();final Pages pages=new Pages(false);
        final IndexMonthlyTarget target=mock(IndexMonthlyTarget.class);final IndexMonthlyJobService owner;
        final IndexMonthlyPublication publisher;final IndexMonthlyPublication.Operation operation;final Snapshot before;final String physical=physical(1);
        final Prepared prepared;final Verified stage;final SyncJobRunner.Page<IndexMonthly> page;
        Fixture(Path temp)throws Exception {
            path=temp.resolve("ledger.sqlite");evidence=temp.resolve("sync-evidence").resolve(RUN);
            ledger=new SyncRunLedger(path);locks=new DatasetIntervalLock(path);
            tables.rows.put(TABLE,List.of());tables.identities.put(TABLE,new Identity(1,"old",0));before=tables.open(TABLE).snapshot();
            when(target.tableName()).thenReturn(TABLE);when(target.targetId()).thenReturn(LOGICAL);when(target.physicalTargetId()).thenReturn(physical);
            when(target.newPublicationTables()).thenReturn(tables);when(target.newStaging()).thenReturn(tables);
            owner=new IndexMonthlyJobService(mock(SyncJobRegistry.class),pages,target,path);
            ledger.createRun(RUN,null,LOGICAL,request());running(RUN);
            ledger.createChild(RUN+"-attempt",SyncRunLedger.Kind.ATTEMPT,RUN,RUN);running(RUN+"-attempt");
            ledger.createChild(RUN+"-slice",SyncRunLedger.Kind.SLICE,RUN,RUN+"-attempt");running(RUN+"-slice");
            locks.acquire(RUN,new DatasetIntervalLock.Scope("index_monthly",FROM,TO));
            page=new IndexMonthlySource(pages,new IndexMonthlyMapper(),evidence.resolve("source")).fetch(CODE,FROM,TO,OBSERVED,()->false);
            ledger.transition(RUN+"-slice",ledger.get(RUN+"-slice").revision(),SyncRunState.FETCHED,JobDefinitionJson.mapper().writeValueAsString(Map.of(
                    "responseEvidence",page.responseEvidence(),"sourceFingerprint",page.sourceFingerprint(),"returnedRows",page.rows().size(),"cursor",CODE)));
            publisher=new IndexMonthlyPublication(tables,path);operation=publisher.beginOperation();
            var staging=new IndexMonthlyStaging(tables);
            prepared=staging.prepare(TABLE,LOGICAL,physical,RUN,request(),CODE,FROM,TO,page.responseEvidence(),page.sourceFingerprint(),page.rows().size(),evidence.resolve("stage"),()->false);
            tables.rows.put(prepared.stage(),page.rows());
            stage=staging.verify(prepared,page.rows(),page.sourceFingerprint(),page.responseEvidence(),evidence.resolve("stage"),()->false);
        }
        IndexMonthlyPublication.Result publish()throws Exception{return publisher.publish(operation,RUN,LOGICAL,physical,TABLE,prepared,stage,()->false);}
        void running(String id)throws Exception{ledger.transition(id,ledger.get(id).revision(),SyncRunState.RUNNING,"{}");}
        void assertFinished()throws Exception{
            for(String id:List.of(RUN+"-slice",RUN+"-attempt",RUN))assertEquals(SyncRunState.VERIFIED,ledger.get(id).state());
            assertNull(locks.findOwned(RUN,new DatasetIntervalLock.Scope("index_monthly",FROM,TO)));
            try(var ignored=publisher.beginOperation()){} // Dataset OS lock and pending-stage exclusion both released.
        }
    }
    static final class FakeTables implements IndexMonthlyStagingPort {
        final Map<String,List<IndexMonthly>> rows=new HashMap<>();final Map<String,Identity> identities=new HashMap<>();
        int renames,stageCreates;Fault fault;
        public Table open(String table){return new Table(){
            public Identity preflight(){return Objects.requireNonNull(identities.get(table));}
            public Snapshot snapshot()throws Exception{return snap(preflight(),rows.get(table));}
            public Snapshot window(String code,LocalDate from,LocalDate to)throws Exception{return snap(preflight(),rows.get(table).stream().filter(r->r.tsCode().equals(code)&&!r.tradeDate().isBefore(from)&&!r.tradeDate().isAfter(to)).toList());}
            public Snapshot outside(String code,LocalDate from,LocalDate to)throws Exception{return snap(preflight(),rows.get(table).stream().filter(r->!r.tsCode().equals(code)||r.tradeDate().isBefore(from)||r.tradeDate().isAfter(to)).toList());}
        };}
        public String logicalTargetId(String table){return LOGICAL;}
        public String physicalTargetId(String table,Identity identity){return physical(identity.id());}
        public Snapshot snapshotIfPresent(String table)throws Exception{return rows.containsKey(table)?open(table).snapshot():null;}
        public int tableCount(String table){return rows.containsKey(table)?1:0;}
        public void createOutsideStage(String stage,String target,String code,String lower,String end){
            assertEquals(FROM+"T00:00:00.000000Z",lower);assertEquals(TO.plusDays(1)+"T00:00:00.000000Z",end);
            assertFalse(rows.containsKey(stage));rows.put(stage,List.of());identities.put(stage,new Identity(2,"stage",0));stageCreates++;
        }
        public void awaitWal(String table,BooleanSupplier cancelled){assertFalse(cancelled.getAsBoolean());assertTrue(rows.containsKey(table));}
        public void rename(String from,String to){
            if(fault==Fault.BEFORE_FIRST&&renames==0)throw new Stop();assertFalse(rows.containsKey(to));
            rows.put(to,Objects.requireNonNull(rows.remove(from)));identities.put(to,identities.remove(from));renames++;
            if(fault==Fault.AFTER_FIRST&&renames==1||fault==Fault.AFTER_SECOND&&renames==2)throw new Stop();
        }
    }
    static final class Pages extends TusharePageService {
        final boolean empty;int calls;Pages(boolean empty){super(null);this.empty=empty;}
        @Override public PageExecutor.Fetcher fetcher(PageContract contract,BooleanSupplier cancelled){return params->{
            calls++;var json=JobDefinitionJson.mapper();var row=new LinkedHashMap<String,JsonNode>();
            for(String field:IndexMonthlySource.FIELDS)row.put(field,json.nullNode());
            row.put("ts_code",json.valueToTree(CODE));row.put("trade_date",json.valueToTree("20260130"));row.put("close",json.valueToTree(1.0));
            return new PageExecutor.Page(empty?List.of():List.of(row),null,false,null);
        };}
    }
    static String physical(long id){return "static-v2-"+String.format(Locale.ROOT,"%064x",id);}
    static Snapshot snap(Identity identity,List<IndexMonthly> rows)throws Exception{
        int bytes=0;for(var row:rows)bytes+=IndexMonthlyRows.canonicalBytes(row).length+1;
        return new Snapshot(identity,rows,IndexMonthlyStaging.fingerprint(rows),bytes);
    }
    static IndexMonthly sample(){return new IndexMonthly(CODE,TO.minusDays(1),1.0,null,null,null,null,null,null,null,null,"macro_core","broad_base",OBSERVED);}
}
