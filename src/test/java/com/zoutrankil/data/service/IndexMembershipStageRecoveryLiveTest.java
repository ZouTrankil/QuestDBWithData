package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class IndexMembershipStageRecoveryLiveTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void completeStageIsReusedAndIncompleteStageIsRebuiltWithoutNewSource(boolean partial) throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-","");Path folder=Path.of("artifacts/java-migration/D005","stage-recovery-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");String table="java_d005_stage_recover_"+nonce;
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",IndexMembershipDataset.DEFINITION.columns().stream()
                    .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())+") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,table)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Fixture WAL did not settle");Thread.sleep(50);
            }
            var catalog=json.treeToValue(json.readTree(Path.of("artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/discovery-readback.json").toFile())
                    .path("catalog"),IndexMembershipClassificationSource.Catalog.class);
            var request=IndexMembershipJobPlan.freeze(catalog,List.of("801011.SI"),IndexMembershipSource.Selection.BOTH,LocalDate.of(2026,9,29));
            var calls=new AtomicInteger();var delegate=context.getBean(TusharePageService.class);
            var pages=new TusharePageService(null) {
                @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                        PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                    calls.incrementAndGet();return delegate.execute(contract,params,consumer,validator,cancelled);
                }
            };
            var owner=new IndexMembershipSliceJob(jdbc,pages,path,table,new IndexMembershipSliceJob.Hook() {
                public void afterPublication(String run) {}
                public void afterStage(String run) throws Exception { throw new java.io.IOException("Injected after complete stage before publication"); }
                public IndexMembershipStaging staging(JdbcTemplate targetJdbc) {
                    return partial?new IndexMembershipStaging(targetJdbc,stage->{throw new java.io.IOException("Injected after CREATE before first batch");})
                            :new IndexMembershipStaging(targetJdbc);
                }
            });
            var failed=owner.run(request);assertEquals(SyncRunState.IN_DOUBT,failed.state(),failed.toString());assertEquals(2,calls.get());
            var ledger=SyncRunLedger.openReadOnly(path);var locks=new DatasetIntervalLock(path);
            assertTrue(ledger.entries(failed.runId(),null,10).stream().allMatch(e->e.state()==SyncRunState.IN_DOUBT));
            assertTrue(locks.findOwned(failed.runId(),DatasetIntervalLock.Scope.allDates("index_member")).inDoubt());
            Path evidence=Path.of(failed.evidence());assertFalse(Files.exists(evidence.resolve("completion.json")));
            var actualBefore=new IndexMembershipStorage(jdbc,table).snapshot();assertEquals(0,actualBefore.rows().size());
            var walBefore=jdbc.queryForList("SELECT writerTxn,sequencerTxn FROM wal_tables() WHERE name=?",table);
            assertThrows(IllegalStateException.class,()->owner.finishInterrupted(failed.runId(),false));
            var prepared=json.readTree(evidence.resolve("prepared.json").toFile());
            Path source=Path.of(prepared.path("source").path("responseEvidence").asText());
            byte[] original=Files.readAllBytes(source);
            try {
                Files.writeString(source," ",StandardOpenOption.APPEND);
                assertThrows(IllegalStateException.class,()->owner.finishInterrupted(failed.runId(),true));
                assertEquals(SyncRunState.IN_DOUBT,ledger.get(failed.runId()).state());
                assertEquals(actualBefore,new IndexMembershipStorage(jdbc,table).snapshot());
            } finally { Files.write(source,original); }
            String retained=null;
            if(partial) {
                Path intent;
                try(var files=Files.list(evidence)) { intent=files.filter(p->p.getFileName().toString().endsWith("-intent.json")).findFirst().orElseThrow(); }
                retained=json.readTree(intent.toFile()).path("stage").asText();DatasetDefinition.identifier(retained);
                long settle=System.nanoTime()+Duration.ofSeconds(20).toNanos();
                while(!QuestDbWriteChecks.walSettled(jdbc,retained)) {
                    if(System.nanoTime()>settle) throw new IllegalStateException("Injected incomplete stage WAL did not settle");Thread.sleep(50);
                }
                assertEquals(0,new IndexMembershipStorage(jdbc,retained).snapshot().rows().size());
            }
            var recovered=owner.finishInterrupted(failed.runId(),true);
            assertEquals(SyncRunState.VERIFIED,recovered.state());assertEquals(2,calls.get());
            var after=new IndexMembershipStorage(jdbc,table).snapshot();assertEquals(7,after.rows().size());
            Path stageRecovery;
            try(var files=Files.list(evidence)) { stageRecovery=files.filter(p->p.getFileName().toString().startsWith("stage-recovery-")).findFirst().orElseThrow(); }
            var stageProof=json.readTree(stageRecovery.toFile());
            assertEquals(partial,stageProof.path("rebuiltFreshStage").asBoolean());assertEquals(partial?7:0,stageProof.path("stageRowsSubmitted").asInt());
            if(partial) {
                assertEquals(retained,stageProof.path("retainedStages").get(0).asText());
                assertEquals(0,new IndexMembershipStorage(jdbc,retained).snapshot().rows().size());
            }
            assertEquals(3,ledger.entries(failed.runId(),null,10).size());
            assertTrue(ledger.entries(failed.runId(),null,10).stream().allMatch(e->e.state()==SyncRunState.VERIFIED));
            assertNull(locks.findOwned(failed.runId(),DatasetIntervalLock.Scope.allDates("index_member")));
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("recovery-readback.json").toFile(),Map.of(
                    "failed",failed,"recovered",recovered,"actual",after,"recoverySourceRequests",0,
                    "walBefore",walBefore,"recoveryStageRowsSubmitted",partial?7:0,"tamperedReceiptRejected",true,
                    "injection",partial?"IOException after stage CREATE before first batch; no OS kill":"IOException after full stage; no OS kill"));
            var publication=new ReferencePublicationJournal(path,"index_member").forRun(failed.runId());
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+publication.intent().backup());
            if(retained!=null) jdbc.execute("DROP TABLE "+retained);
        }
    }
}
