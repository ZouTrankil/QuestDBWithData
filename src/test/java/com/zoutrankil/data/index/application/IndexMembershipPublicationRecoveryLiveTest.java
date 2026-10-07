package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbIndexMembershipTarget;

import com.zoutrankil.data.service.StaticTargetIdentity;

import com.zoutrankil.data.index.storage.IndexMembershipStaging;
import com.zoutrankil.data.index.storage.IndexMembershipStorage;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexMembershipPublicationRecoveryLiveTest {
    static class AbruptStop extends Error {}
    @Test void normalPublicationAndEveryDurableRenamePhaseRecoverWithExactReadback() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-","");Path folder=Path.of("artifacts/java-migration/D005","publication-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");var ledger=new SyncRunLedger(path);var locks=new DatasetIntervalLock(path);
            Path sourceReceipt=Path.of("artifacts/java-migration/D005/source-adapter-7ec60463-be52-45b5-bcf2-bebdee34fea3/source-review.json");
            List<IndexMembership> source=json.convertValue(json.readTree(sourceReceipt.toFile()).path("result").path("rows"),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            assertEquals(7,source.size());
            var cases=new ArrayList<Object>();
            for(String phase:List.of("NORMAL","PREPARED","OLD_MOVED","PUBLISHED")) {
                String emptyTable="java_d005_publish_"+phase.toLowerCase(Locale.ROOT)+"_"+nonce;String run="membership-publish-"+phase+"-"+nonce;
                jdbc.execute("CREATE TABLE "+emptyTable+" ("+String.join(",",IndexMembershipDataset.DEFINITION.columns().stream()
                        .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())
                        +") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
                long createdBy=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
                while(!QuestDbWriteChecks.walSettled(jdbc,emptyTable)) {
                    if(System.nanoTime()>createdBy) throw new IllegalStateException("New isolated membership target WAL did not settle");
                    Thread.sleep(50);
                }
                var empty=new IndexMembershipStorage(jdbc,emptyTable).snapshot();
                var seed=IndexMembershipStaging.prepare(empty,source.stream().filter(r->r.latestFlag().equals("Y")).toList(),"801011.SI");
                var seeded=new IndexMembershipStaging(jdbc).write(seed,folder.resolve(phase).resolve("seed"),()->false);
                String table=seeded.table();
                jdbc.execute("DROP TABLE "+emptyTable);
                var before=new IndexMembershipStorage(jdbc,table).snapshot();
                assertEquals(4,before.rows().size());
                String target=StaticTargetIdentity.identify(jdbc,table,before.identity().id(),before.identity().directory());
                ledger.createRun(new SyncRunLedger.Run(run,null,"test.membership_publication",1,"2026-09-29",target,"{}"));
                var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("index_member"));assertNotNull(lease);
                var prepared=IndexMembershipStaging.prepare(before,source,"801011.SI");
                assertEquals(3,prepared.merge().inserted());assertEquals(4,prepared.merge().unchanged());
                var stage=new IndexMembershipStaging(jdbc).write(prepared,folder.resolve(phase),()->false);
                var publisher=new IndexMembershipPublication(new QuestDbIndexMembershipTarget(jdbc,"index_member"),path,state->{if(state.name().equals(phase)) throw new AbruptStop();});
                IndexMembershipPublication.Result result;
                assertThrows(java.util.concurrent.CancellationException.class,()->publisher.publish(lease,table,prepared,stage,()->true));
                assertEquals(before,new IndexMembershipStorage(jdbc,table).snapshot());
                assertTrue(new ReferencePublicationJournal(path,"index_member").findForRun(run).isEmpty());
                if(phase.equals("NORMAL")) result=publisher.publish(lease,table,prepared,stage,()->false);
                else {
                    assertThrows(AbruptStop.class,()->publisher.publish(lease,table,prepared,stage,()->false));
                    var recovery=new IndexMembershipPublication(new QuestDbIndexMembershipTarget(jdbc,"index_member"),path);
                    var expected=phase.equals("PREPARED")?IndexMembershipPublication.Layout.ORIGINAL:IndexMembershipPublication.Layout.valueOf(phase);
                    assertEquals(expected,recovery.inspect(run));
                    assertThrows(IllegalStateException.class,()->recovery.finish(lease,false));
                    // Synchronous publisher returned abruptly; the test owns the sole writer for this target/ledger.
                    result=recovery.finish(lease,true);
                }
                assertEquals(State.VERIFIED,result.publication().state());assertEquals(prepared.rows(),result.actual().rows());
                assertEquals(7,result.actual().rows().size());assertTrue(result.actual().rows().containsAll(before.rows()));
                assertEquals(before,new IndexMembershipStorage(jdbc,result.publication().intent().backup()).snapshot());
                assertTrue(QuestDbWriteChecks.walSettled(jdbc,table));
                assertEquals(result,new IndexMembershipPublication(new QuestDbIndexMembershipTarget(jdbc,"index_member"),path).finish(lease,true));
                cases.add(Map.of("phase",phase,"result",result,"backupVerified",true,"sourceReceipt",sourceReceipt.toString()));
                var current=locks.findOwned(run,lease.scope());
                if(current.inDoubt()) locks.releaseAfterReconciliation(current,true,true);else locks.releaseVerified(current);
                jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+result.publication().intent().backup());
            }
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("publication-readback.json").toFile(),
                    Map.of("cases",cases,"injection","Error at durable publication phases; no OS process kill", "formalOwnerRunVerified",false));
        }
    }
}
