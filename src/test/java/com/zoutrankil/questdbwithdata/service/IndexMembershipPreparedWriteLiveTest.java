package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.IndexMembershipMapper;
import com.zoutrankil.questdbwithdata.repository.*;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexMembershipPreparedWriteLiveTest {
    @Test void fullFieldPreparedRowsPublishAndResumeWithoutSecondWrite() throws Exception {
        verify(false,false,false);
    }
    @Test void twoWalReplacementDatasetsPublishAndResumeWithoutSecondWrite() throws Exception {
        verify(true,false,false);
    }
    @Test void secondDatasetLockFailureResumesWithoutRewritingFirstDataset() throws Exception {
        verify(true,true,false);
    }
    @Test void cancellationBeforeSecondChildCreationResumesFromFrozenGroupTarget() throws Exception {
        verify(true,false,true);
    }
    private void verify(boolean combined,boolean conflict,boolean cancelBeforeChild) throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            String table="java_d005_prepared_"+nonce;Path folder=Path.of("artifacts/java-migration/D005","prepared-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");
            var production=new IndexMembershipStorage(jdbc,"index_member").snapshot();
            var thsProduction=combined?new ThsIndexStorage(jdbc,"ths_index").snapshot():null;
            String thsTable="java_d005_prepared_ths_"+nonce;
            if(combined) jdbc.execute("CREATE TABLE "+thsTable+" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                    +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                    +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            var columns=IndexMembershipDataset.DEFINITION.columns();
            jdbc.execute("CREATE TABLE \""+table+"\" ("+String.join(",",columns.stream()
                    .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())
                    +") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
            var owner=new IndexMembershipJobService(jdbc,context.getBean(TusharePageService.class),path,table);
            var datasets=context.getBean(DatasetRegistry.class);
            var thsOwner=combined?new ThsIndexJobService(jdbc,context.getBean(TusharePageService.class),path,thsTable):null;
            var service=new StockBasicWriteGroupService(datasets,context.getBean(StockBasicJobService.class),
                    null,null,null,thsOwner,owner,jdbc,context.getBean(QuestDB.class),path.toString());
            var all=production.businessRows();String industry=all.getFirst().indexCode();
            var rows=all.stream().filter(row->row.indexCode().equals(industry)).limit(2).toList();
            assertEquals(2,rows.size());
            var mapper=new IndexMembershipMapper();Path input=folder.resolve("write-group.json");
            var members=new ArrayList<Map<String,Object>>();
            if(combined) {
                var thsMapper=new com.zoutrankil.questdbwithdata.mapper.ThsIndexMapper();
                members.add(Map.of("memberId","ths-member","datasetId","ths_index",
                        "definitionVersion",ThsIndexDataset.DEFINITION.schemaVersion(),"batchId","ths-rows",
                        "rows",thsProduction.businessRows().subList(0,2).stream().map(r->thsMapper.values(r).asMap()).toList()));
            }
            members.add(Map.of("memberId","membership-member",
                    "datasetId","index_member","definitionVersion",IndexMembershipDataset.DEFINITION.schemaVersion(),
                    "batchId","membership-rows","rows",rows.stream().map(r->mapper.values(r).asMap()).toList()));
            JobDefinitionJson.mapper().writeValue(input.toFile(),Map.of("batchId","membership-sample",
                    "logicalDate","2026-09-29","members",members));
            DatasetIntervalLock locks=null;DatasetIntervalLock.Lease held=null;
            if(conflict) {
                new SyncRunLedger(path).createRun(new SyncRunLedger.Run("lock-fixture",null,"fixture.lock",1,
                        "2026-09-29",owner.targetId(),"{}"));
                locks=new DatasetIntervalLock(path);held=locks.acquire("lock-fixture",DatasetIntervalLock.Scope.allDates("index_member"));
                assertNotNull(held);
            }
            var first=cancelBeforeChild?runCancellingAfterFirst(datasets,owner,thsOwner,path,folder,input):service.run(input,null);
            if(conflict || cancelBeforeChild) {
                assertEquals(cancelBeforeChild?SyncRunState.CANCELLED:SyncRunState.PARTIAL,first.state());
                assertEquals(SyncRunState.VERIFIED,first.members().getFirst().state());
                if(conflict) assertEquals(SyncRunState.FAILED,first.members().getLast().state());
                else {
                    assertEquals(1,first.members().size());
                    assertNull(SyncRunLedger.openReadOnly(path).groupMembers(first.runId()).getLast().childRunId());
                }
                assertTrue(new IndexMembershipStorage(jdbc,table).snapshot().rows().isEmpty());
                var alreadyWritten=new ThsIndexStorage(jdbc,thsTable).snapshot();
                assertEquals(2,alreadyWritten.rows().size());
                var failed=first;if(conflict) locks.releaseVerified(held);first=service.run(input,failed.runId());
                assertEquals(SyncRunState.VERIFIED,first.state());
                assertTrue(first.members().getFirst().reused());assertFalse(first.members().getLast().reused());
                assertEquals(failed.members().getFirst().childRunId(),first.members().getFirst().childRunId());
                assertEquals(alreadyWritten,new ThsIndexStorage(jdbc,thsTable).snapshot());
                JobDefinitionJson.mapper().writeValue(folder.resolve("partial-resume.json").toFile(),
                        Map.of("partial",failed,"continued",first,"firstDatasetUnchanged",alreadyWritten));
            }
            assertEquals(SyncRunState.VERIFIED,first.state());
            var actual=new IndexMembershipStorage(jdbc,table).snapshot();assertEquals(2,actual.rows().size());
            for(var row:rows) assertTrue(actual.businessRows().contains(row));
            var child=first.members().getLast().childRunId();var ledger=SyncRunLedger.openReadOnly(path);var previous=ledger.getRun(child);
            var targets=new LinkedHashMap<String,String>();targets.put("index_member",previous.targetId());
            var thsActual=combined?new ThsIndexStorage(jdbc,thsTable).snapshot():null;
            if(combined) {
                assertEquals(2,thsActual.rows().size());
                assertEquals(thsProduction.businessRows().subList(0,2),thsActual.businessRows());
                targets.put("ths_index",ledger.getRun(first.members().getFirst().childRunId()).targetId());
                assertTrue(java.time.Instant.parse(ledger.get(first.members().getFirst().childRunId()).updatedAt())
                        .isBefore(java.time.Instant.parse(ledger.events(child,-1,10).getFirst().updatedAt())));
            }
            var plan=WriteGroupPlan.prepare(new WriteGroupJson(datasets).read(input),datasets,
                    targets);
            var adapter=new IndexMembershipPreparedWriteAdapter(plan,"membership-member",owner,folder,true);
            owner.revalidatePreparedChild(child,previous.targetId(),adapter.request());
            var resumed=service.run(input,first.runId());assertEquals(SyncRunState.VERIFIED,resumed.state());
            assertEquals(first.members().stream().map(m->m.childRunId()).toList(),resumed.members().stream().map(m->m.childRunId()).toList());
            assertTrue(resumed.members().stream().allMatch(m->m.reused()));
            assertEquals(actual,new IndexMembershipStorage(jdbc,table).snapshot());
            if(!combined) {
                var rerun=service.run(input,null);assertEquals(SyncRunState.VERIFIED,rerun.state());
                assertEquals(actual,new IndexMembershipStorage(jdbc,table).snapshot());
                var noWrite=JobDefinitionJson.mapper().readTree(folder.resolve("sync-evidence")
                        .resolve(rerun.members().getFirst().childRunId()).resolve("completion.json").toFile());
                assertEquals(0,noWrite.path("submittedStageRows").asInt());
                JobDefinitionJson.mapper().writeValue(folder.resolve("idempotent-rerun.json").toFile(),
                        Map.of("rerun",rerun,"actual",actual,"submittedStageRows",0));
            }
            assertEquals(production,new IndexMembershipStorage(jdbc,"index_member").snapshot());
            if(combined) {
                assertEquals(thsActual,new ThsIndexStorage(jdbc,thsTable).snapshot());
                assertEquals(thsProduction,new ThsIndexStorage(jdbc,"ths_index").snapshot());
                JobDefinitionJson.mapper().writeValue(folder.resolve("ths-readback.json").toFile(),thsActual);
            }
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("write-readback.json").toFile(),
                    Map.of("first",first,"resumed",resumed,"actual",actual,"input",input.toString(),"formalUnchanged",true));
            String backup=new ReferencePublicationJournal(path,"index_member").forRun(child).intent().backup();
            jdbc.execute("DROP TABLE \""+table+"\"");jdbc.execute("DROP TABLE \""+backup+"\"");
            if(combined) {
                jdbc.execute("DROP TABLE "+thsTable);
                jdbc.execute("DROP TABLE "+new ReferencePublicationJournal(path,"ths_index").forRun(first.members().getFirst().childRunId()).intent().backup());
            }
        }
    }
    private SyncGroupRunner.Result runCancellingAfterFirst(DatasetRegistry datasets,IndexMembershipJobService owner,
            ThsIndexJobService ths,Path path,Path folder,Path input) throws Exception {
        var plan=WriteGroupPlan.prepare(new WriteGroupJson(datasets).read(input),datasets,
                Map.of("ths_index",ths.targetId(),"index_member",owner.targetId()));
        var delegate=new ThsIndexPreparedWriteAdapter(plan,"ths-member",ths,folder.resolve("cancel-evidence"),false);
        var cancelling=new WriteGroupMemberAdapter() {
            public WriteGroupPlan.Member member() { return delegate.member(); }
            public SyncJobDefinition.FrozenRequest request() { return delegate.request(); }
            public void preflight(SyncJobDefinition.FrozenRequest request) throws Exception { delegate.preflight(request); }
            public SyncJobRunner.Result execute(SyncRunLedger ledger,DatasetIntervalLock locks,String child,String parent,
                    String prior,String target,SyncJobDefinition.FrozenRequest request,java.util.function.BooleanSupplier cancelled) throws Exception {
                var result=delegate.execute(ledger,locks,child,parent,prior,target,request,cancelled);
                assertEquals(SyncRunState.VERIFIED,result.state());ledger.requestCancellation(parent);return result;
            }
            public String revalidate(SyncRunLedger ledger,String prior,String target,SyncJobDefinition.FrozenRequest request,
                    java.util.function.BooleanSupplier cancelled,Path evidence) throws Exception {
                return delegate.revalidate(ledger,prior,target,request,cancelled,evidence);
            }
        };
        return new PersistentWriteGroupRunner(path,folder.resolve("write-evidence"),datasets).run(
                "write-group-cancel-"+UUID.randomUUID(),plan,Map.of("ths-member",cancelling,
                        "membership-member",new IndexMembershipPreparedWriteAdapter(plan,"membership-member",owner,
                                folder.resolve("cancel-evidence"),false)),null);
    }
}
