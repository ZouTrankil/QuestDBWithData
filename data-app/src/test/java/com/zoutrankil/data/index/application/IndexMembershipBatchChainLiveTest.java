package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.domain.IndexMembershipState;
import com.zoutrankil.data.index.storage.QuestDbIndexMembershipTarget;

import com.zoutrankil.data.index.storage.IndexMembershipStorage;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Saved real source rows exercise two publications and a changed full target against live QuestDB. */
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexMembershipBatchChainLiveTest {
    @Test void twoSavedRealNonemptyIndustriesPublishSeriallyAndResumeRejectsOutsideDrift() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-","");
            Path folder=Path.of("artifacts/java-migration/D005","batch-chain-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");String table="java_d005_chain_"+nonce;
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",IndexMembershipDataset.DEFINITION.columns().stream()
                    .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())+") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,table)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Chain fixture WAL did not settle");Thread.sleep(50);
            }
            var catalog=json.treeToValue(json.readTree(Path.of("artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/discovery-readback.json").toFile())
                    .path("catalog"),IndexMembershipClassificationSource.Catalog.class);
            var request=IndexMembershipJobPlan.freeze(catalog,List.of("801011.SI","801207.SI"),
                    IndexMembershipSource.Selection.CURRENT,LocalDate.of(2026,9,29));
            var sample=json.readTree(Path.of("artifacts/java-migration/D005/two-industry-source-sample.json").toFile());
            var raw=new HashMap<String,List<Map<String,JsonNode>>>();
            for(var response:sample.path("requests")) raw.put(response.path("l2Code").asText(),
                    json.convertValue(response.path("rows"),new com.fasterxml.jackson.core.type.TypeReference<>() {}));
            assertEquals(Set.of("801011.SI","801207.SI"),raw.keySet());
            var calls=new AtomicInteger();
            var pages=new TusharePageService(null) {
                @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                        PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                    calls.incrementAndGet();
                    if(!"Y".equals(params.get("is_new"))) throw new IllegalArgumentException("Saved current-only response required");
                    return new PageExecutor().execute(contract,params,p->new PageExecutor.Page(
                            raw.get((String)p.get("l2_code")),null,false,null),consumer,validator,cancelled);
                }
            };
            var batch=new IndexMembershipBatchJob(new QuestDbIndexMembershipTarget(jdbc,table),pages,path,table);
            var result=batch.run(request);
            assertEquals(SyncRunState.VERIFIED,result.state(),result.toString());
            assertEquals(2,result.members().size());
            var first=json.readTree(folder.resolve("sync-evidence").resolve(result.members().get(0).childRunId())
                    .resolve("completion.json").toFile());
            var second=json.readTree(folder.resolve("sync-evidence").resolve(result.members().get(1).childRunId())
                    .resolve("completion.json").toFile());
            assertTrue(first.path("source").path("rows").size()>0);
            assertTrue(second.path("source").path("rows").size()>0);
            var firstBefore=json.treeToValue(first.path("before"),IndexMembershipState.Snapshot.class);
            var firstAfter=json.treeToValue(first.path("actual"),IndexMembershipState.Snapshot.class);
            var secondBefore=json.treeToValue(second.path("before"),IndexMembershipState.Snapshot.class);
            var secondAfter=json.treeToValue(second.path("actual"),IndexMembershipState.Snapshot.class);
            assertEquals(firstAfter,secondBefore);
            assertNotEquals(firstBefore.identity(),firstAfter.identity());
            assertNotEquals(secondBefore.identity(),secondAfter.identity());
            assertEquals(secondAfter,new IndexMembershipStorage(jdbc,table).snapshot());
            var reused=batch.resume(request,result.runId());assertEquals(SyncRunState.VERIFIED,reused.state(),reused.toString());
            assertTrue(reused.members().stream().allMatch(IndexMembershipBatchJob.Member::reused));
            assertEquals(2,calls.get());assertEquals(secondAfter,new IndexMembershipStorage(jdbc,table).snapshot());
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("chain-readback.json").toFile(),Map.of(
                    "batch",result,"firstBefore",firstBefore,"firstAfter",firstAfter,
                    "secondBefore",secondBefore,"secondAfter",secondAfter,"reused",reused,"resumeSourceRequests",0));
            jdbc.execute("INSERT INTO "+table+" (index_code,ts_code,update_time,index_name,con_name,in_date,is_new,level,l1_name,l2_name,l3_name) "
                    +"VALUES ('801216.SI','999999.SZ',cast(1790680000000000 AS TIMESTAMP),'Injected drift','Injected drift','20260929','Y','L2','Injected','Injected','Injected')");
            long settle=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,table)) {
                if(System.nanoTime()>settle) throw new IllegalStateException("Drift injection WAL did not settle");Thread.sleep(50);
            }
            assertEquals(secondAfter.rows().size()+1,new IndexMembershipStorage(jdbc,table).snapshot().rows().size());
            boolean rejected=false;
            try { rejected=batch.resume(request,result.runId()).state()!=SyncRunState.VERIFIED; }
            catch(IllegalStateException expected) { rejected=true; }
            assertTrue(rejected,"Resume accepted a change outside both frozen industry scopes");
            assertEquals(2,calls.get(),"Drift rejection must precede another source request");
            var journal=new ReferencePublicationJournal(path,"index_member");
            var firstPublication=journal.forRun(result.members().get(0).childRunId());
            var secondPublication=journal.forRun(result.members().get(1).childRunId());
            jdbc.execute("DROP TABLE "+table);
            jdbc.execute("DROP TABLE "+firstPublication.intent().backup());
            jdbc.execute("DROP TABLE "+secondPublication.intent().backup());
        }
    }
}
