package com.zoutrankil.data.derived.application;
import com.zoutrankil.data.derived.domain.EquityStyleMonthlySourceData;
import com.zoutrankil.data.derived.domain.EquityStyleMonthlySourceData.*;
import com.zoutrankil.data.derived.port.EquityStyleMonthlySourceReadPort;
import com.zoutrankil.data.derived.storage.QuestDbEquityStyleMonthlySourceReader;
import com.zoutrankil.data.derived.storage.QuestDbEquityStyleMonthlyTarget;

import com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper;

import com.zoutrankil.data.service.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.storage.EquityStyleMonthlyWritePort;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import static com.zoutrankil.data.derived.application.EquityStyleMonthlySourceTest.*;

class EquityStyleMonthlyMaterializeAdapterTest {
    static final String TARGET="d103-"+"b".repeat(64);
    static final class MemoryPort implements com.zoutrankil.data.derived.port.EquityStyleMonthlyWriteSession {
        final TreeMap<YearMonth,EquityStyleMonthly> values=new TreeMap<>();long txn;int sends,preflights;boolean stopped,unknown;String target=TARGET;
        Runnable afterSend=()->{};boolean interruptUnknown;
        @Override public String table(){return "java_d103_equity_style_monthly_unit";}
        @Override public synchronized void preflight(){preflights++;if(unknown)throw new IllegalStateException("Original unknown sender requires reconciliation");}
        @Override public String targetId(){return target;}
        @Override public com.zoutrankil.data.domain.EquityStyleMonthlyTargetSnapshot targetSnapshot(){return new com.zoutrankil.data.domain.EquityStyleMonthlyTargetSnapshot(target,19,"java_d103_equity_style_monthly_unit~19","c".repeat(64),txn,txn,txn,txn,0,0,false,values.size(),(long)values.size());}
        @Override public void send(List<EquityStyleMonthly> rows)throws Exception{
            sends++;rows.forEach(r->values.put(r.month(),r));txn++;stopped=true;afterSend.run();
            if(interruptUnknown){unknown=true;stopped=false;Thread.currentThread().interrupt();throw new IllegalStateException("Unknown ACK");}
        }
        @Override public List<EquityStyleMonthly> readback(List<YearMonth> keys){return keys.stream().map(values::get).filter(Objects::nonNull).toList();}
        @Override public List<EquityStyleMonthly> readActualRange(YearMonth from,YearMonth to){return List.copyOf(values.subMap(from,true,to,true).values());}
        @Override public boolean walSettled(){return true;}
        @Override public boolean uncertainSenderStopped(){return stopped;}
        @Override public boolean unresolved(){return unknown;}
        @Override public void createIsolatedTarget(){preflight();}
    }
    static SyncJobDefinition.FrozenRequest request(EquityStyleMonthlySourceData.Batch batch,LocalDate from,LocalDate to,SyncJobDefinition.Mode mode){
        return EquityStyleMonthlyJobService.definition().freeze(mode,Map.of("source_version",batch.snapshot().version(),"source_hash",batch.rawFingerprint(),"target_id",TARGET,"bootstrap_from",JUNE),from,to,LocalDate.of(2026,10,6));
    }
    @Test void publishesOneCompleteBoundedPageAndVerifiesWholePrefixBeforeCompletion()throws Exception{
        var source=new MutableSource();var writer=new MemoryPort();var batch=source.read(JUNE,JULY);var adapter=new EquityStyleMonthlyMaterializeAdapter(source,writer,batch);var request=request(batch,JUNE,JULY,SyncJobDefinition.Mode.MATERIALIZE);
        adapter.preflight(request);var completion=adapter.fetch(request,page->{assertEquals(2,page.rows().size());assertTrue(page.responseEvidence().contains("fullPrefixExpected"));adapter.port().send(page.rows());},()->false);
        assertEquals(1,completion.pages());assertEquals(2,completion.rows());assertTrue(completion.complete());assertNotNull(adapter.verifiedTarget());assertEquals(1,writer.sends);assertEquals(batch,adapter.verifiedSource());
    }
    @Test void missingWrongKeyDuplicateAndSignedZeroMismatchAreExactFailures(){
        var source=new MutableSource();var rows=source.read(JUNE,JULY).rows();assertFalse(EquityStyleMonthlyMaterializeAdapter.exact(rows,List.of(rows.getFirst(),rows.getFirst())));
        assertFalse(EquityStyleMonthlyMaterializeAdapter.exact(rows,List.of(rows.getFirst())));assertFalse(EquityStyleMonthlyMaterializeAdapter.exact(rows,Arrays.asList(rows.getFirst(),null)));
        var changed=new LinkedHashMap<>(new com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper().values(rows.getFirst()).asMap());changed.put("month",AUGUST);
        assertFalse(EquityStyleMonthlyMaterializeAdapter.exact(rows,List.of(rows.getFirst(),new com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper().fromValues(changed))));
        changed.put("month",JUNE);changed.put("hs300_ret_1m",-0.0);var negative=new com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper().fromValues(changed);changed.put("hs300_ret_1m",0.0);var positive=new com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper().fromValues(changed);
        assertFalse(EquityStyleMonthlyMaterializeAdapter.exact(List.of(negative),List.of(positive)));
    }
    @Test void incrementalConsumerCannotHideAnUnverifiedOldPrefix()throws Exception{
        var source=new MutableSource();var writer=new MemoryPort();var batch=source.read(JUNE,JULY);var adapter=new EquityStyleMonthlyMaterializeAdapter(source,writer,batch);var request=request(batch,JULY,JULY,SyncJobDefinition.Mode.INCREMENTAL);
        adapter.preflight(request);assertThrows(IllegalStateException.class,()->adapter.fetch(request,page->adapter.port().send(page.rows()),()->false));assertEquals(1,writer.sends);assertNull(adapter.verifiedTarget());
    }
    @Test void fullPrefixMismatchAfterConsumerCannotReturnComplete()throws Exception{
        var source=new MutableSource();var writer=new MemoryPort();var batch=source.read(JUNE,JULY);var adapter=new EquityStyleMonthlyMaterializeAdapter(source,writer,batch);var request=request(batch,JUNE,JULY,SyncJobDefinition.Mode.MATERIALIZE);
        adapter.preflight(request);assertThrows(IllegalStateException.class,()->adapter.fetch(request,page->{adapter.port().send(page.rows());writer.values.remove(YearMonth.from(JUNE));},()->false));assertNull(adapter.verifiedTarget());
    }
    @Test void sourceRevisionAfterWriteFailsBeforeVerifiedSourceOrCompletion()throws Exception{
        var source=new MutableSource();var writer=new MemoryPort();writer.afterSend=source::revisePrefix;var batch=source.read(JUNE,JULY);var adapter=new EquityStyleMonthlyMaterializeAdapter(source,writer,batch);var request=request(batch,JUNE,JULY,SyncJobDefinition.Mode.MATERIALIZE);
        adapter.preflight(request);assertThrows(IllegalStateException.class,()->adapter.fetch(request,page->adapter.port().send(page.rows()),()->false));assertNull(adapter.verifiedSource());
    }
    @Test void sourcePlanDriftAndTargetRebuildRejectBeforeSend()throws Exception{
        var source=new MutableSource();var writer=new MemoryPort();var batch=source.read(JUNE,JULY);var adapter=new EquityStyleMonthlyMaterializeAdapter(source,writer,batch);var request=request(batch,JUNE,JULY,SyncJobDefinition.Mode.MATERIALIZE);
        source.revisePrefix();var initial=adapter;assertThrows(IllegalStateException.class,()->initial.preflight(request));assertEquals(0,writer.sends);
        var fresh=source.read(JUNE,JULY);adapter=new EquityStyleMonthlyMaterializeAdapter(source,writer,fresh);writer.target="d103-"+"d".repeat(64);var rebuilt=adapter;
        assertThrows(IllegalStateException.class,()->rebuilt.preflight(request(fresh,JUNE,JULY,SyncJobDefinition.Mode.MATERIALIZE)));assertEquals(0,writer.sends);
    }
    @Test void readonlyReconcileUsesRealReadbackAndNeverPublisher()throws Exception{
        var source=new MutableSource();var writer=new MemoryPort();var batch=source.read(JUNE,JULY);batch.rows().forEach(r->writer.values.put(r.month(),r));var adapter=new EquityStyleMonthlyMaterializeAdapter(source,writer,batch);var request=request(batch,JUNE,JULY,SyncJobDefinition.Mode.RECONCILE);
        adapter.preflight(request);var completion=adapter.fetch(request,page->{adapter.port().send(page.rows());assertTrue(adapter.port().uncertainSenderStopped());},()->false);
        assertEquals(2,completion.rows());assertEquals(0,writer.sends);assertNotNull(adapter.verifiedTarget());
    }
    @Test void cancellationAndFiniteBudgetsDoNotReachPublisher()throws Exception{
        var source=new MutableSource();var writer=new MemoryPort();var batch=source.read(JUNE,JULY);var adapter=new EquityStyleMonthlyMaterializeAdapter(source,writer,batch);var request=request(batch,JUNE,JULY,SyncJobDefinition.Mode.MATERIALIZE);adapter.preflight(request);
        assertThrows(CancellationException.class,()->adapter.fetch(request,page->fail("No page expected"),()->true));assertEquals(0,writer.sends);
        assertEquals(DatasetIntervalLock.Scope.allDates("equity_style_monthly"),adapter.conflictScope(request));assertEquals(Duration.ofSeconds(20),adapter.visibilityTimeout());
    }
    @Test void emptySourceRequiresStableEmptyActualTargetAndReturnsExplicitZero()throws Exception{
        var source=new MutableSource();source.raw.clear();var writer=new MemoryPort();var batch=source.read(JUNE,JULY);var adapter=new EquityStyleMonthlyMaterializeAdapter(source,writer,batch);var request=request(batch,JUNE,JULY,SyncJobDefinition.Mode.MATERIALIZE);adapter.preflight(request);
        var result=adapter.fetch(request,page->assertTrue(page.rows().isEmpty()),()->false);assertEquals(0,result.rows());assertTrue(result.complete());assertNotNull(adapter.verifiedTarget());assertEquals(0,writer.sends);
    }
}
