package com.zoutrankil.data.service;

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
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** A stopped coordinator before attempt creation has no child writes to replay. */
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexMembershipEarlyResumeLiveTest {
    @Test void stoppedBeforeAttemptUsesFrozenEmptyPrefixAndThenRunsFirstIndustry() throws Exception {
        verify(false);
    }
    @Test void stoppedAfterSlotsButBeforeFirstChildResumesWithoutInventingACompletedChild() throws Exception {
        verify(true);
    }
    private void verify(boolean afterSlots) throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-","");
            Path folder=Path.of("artifacts/java-migration/D005","early-resume-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");String table="java_d005_early_"+nonce;
            var formal=new IndexMembershipStorage(jdbc,"index_member").snapshot();
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",IndexMembershipDataset.DEFINITION.columns().stream()
                    .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())+") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
            var catalog=json.treeToValue(json.readTree(Path.of("artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/discovery-readback.json").toFile())
                    .path("catalog"),IndexMembershipClassificationSource.Catalog.class);
            var request=IndexMembershipJobPlan.freeze(catalog,List.of("801011.SI"),
                    IndexMembershipSource.Selection.CURRENT,LocalDate.of(2026,9,29));
            var source=json.readTree(Path.of("artifacts/java-migration/D005/two-industry-source-sample.json").toFile());
            List<Map<String,JsonNode>> raw=null;
            for(var response:source.path("requests")) if(response.path("l2Code").asText().equals("801011.SI"))
                raw=json.convertValue(response.path("rows"),new com.fasterxml.jackson.core.type.TypeReference<>() {});
            assertNotNull(raw);var saved=raw;var calls=new AtomicInteger();
            var pages=new TusharePageService(null) {
                @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                        PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                    calls.incrementAndGet();return new PageExecutor().execute(contract,params,
                            p->new PageExecutor.Page(saved,null,false,null),consumer,validator,cancelled);
                }
            };
            var stoppedId=new AtomicReference<String>();
            IndexMembershipBatchJob.Hook stop=new IndexMembershipBatchJob.Hook() {
                public void afterMember(String run,int ordinal) {}
                public void afterPlan(String run) {
                    if(!afterSlots) { stoppedId.set(run);throw new AssertionError("STOPPED_BEFORE_ATTEMPT"); }
                }
                public void afterSlots(String run) {
                    if(afterSlots) { stoppedId.set(run);throw new AssertionError("STOPPED_BEFORE_FIRST_CHILD"); }
                }
            };
            assertThrows(AssertionError.class,()->new IndexMembershipBatchJob(jdbc,pages,path,table,stop).run(request));
            String prior=stoppedId.get();assertNotNull(prior);
            assertEquals(SyncRunState.RUNNING,SyncRunLedger.openReadOnly(path).get(prior).state());
            assertEquals(afterSlots?3:1,SyncRunLedger.openReadOnly(path).entries(prior,null,40).size());
            assertEquals(0,calls.get());assertTrue(new IndexMembershipStorage(jdbc,table).snapshot().rows().isEmpty());
            var resumed=new IndexMembershipBatchJob(jdbc,pages,path,table).resumeStopped(request,prior,true);
            assertEquals(SyncRunState.VERIFIED,resumed.state(),resumed.toString());
            assertEquals(1,calls.get());assertEquals(4,new IndexMembershipStorage(jdbc,table).snapshot().rows().size());
            assertEquals(SyncRunState.PARTIAL,SyncRunLedger.openReadOnly(path).get(prior).state());
            assertEquals(formal,new IndexMembershipStorage(jdbc,"index_member").snapshot());
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("early-resume-readback.json").toFile(),
                    Map.of("priorRun",prior,"resumed",resumed,"sourceCallsAfterResume",calls.get(),
                            "stoppedAfterSlots",afterSlots,"formalUnchanged",true));
            String child=resumed.members().getFirst().childRunId();
            String backup=new ReferencePublicationJournal(path,"index_member").forRun(child).intent().backup();
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+backup);
        }
    }
}
