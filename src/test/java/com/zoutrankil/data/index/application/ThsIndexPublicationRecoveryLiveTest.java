package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbThsIndexTables;

import com.zoutrankil.data.index.domain.ThsIndexState;

import com.zoutrankil.data.service.StaticTargetIdentity;

import com.zoutrankil.data.index.storage.ThsIndexStaging;
import com.zoutrankil.data.index.storage.ThsIndexStorage;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.client.dto.TushareThsIndexDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.mapper.ThsIndexMapper;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class ThsIndexPublicationRecoveryLiveTest {
    static class AbruptStop extends Error {}
    @Test void normalPublicationAndEveryDurableRenamePhaseRecoverWithExactReadback() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-","");Path folder=Path.of("artifacts/java-migration/D004","publication-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");var ledger=new SyncRunLedger(path);var locks=new DatasetIntervalLock(path);
            var raw=json.readTree(Path.of("artifacts/java-migration/D004/source-preflight.json").toFile()).path("rows").get(0);
            var source=new ThsIndexMapper().fromSource(new TushareThsIndexDto(raw.path("ts_code").asText(),raw.path("name").asText(),
                    raw.path("count").intValue(),raw.path("exchange").asText(),raw.path("list_date").asText(),raw.path("type").asText()),
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            var cases=new ArrayList<Object>();
            for(String phase:List.of("NORMAL","PREPARED","OLD_MOVED","PUBLISHED")) {
                String table="java_d004_publish_"+phase.toLowerCase(Locale.ROOT)+"_"+nonce;String run="ths-publish-"+phase+"-"+nonce;
                jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",ThsIndexDataset.DEFINITION.columns().stream()
                        .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())
                        +") TIMESTAMP(update_time) PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
                var before=new ThsIndexStorage(jdbc,table).snapshot();
                String target=StaticTargetIdentity.identify(jdbc,table,before.identity().id(),before.identity().directory());
                ledger.createRun(new SyncRunLedger.Run(run,null,"test.ths_publication",1,"2026-09-29",target,"{}"));
                var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("ths_index"));assertNotNull(lease);
                var prepared=ThsIndexStaging.prepare(before,List.of(source),new ThsIndexState.Scope(source.tsCode(),null,null));
                var stage=new ThsIndexStaging(jdbc).write(prepared,folder.resolve(phase),()->false);
                var publisher=new ThsIndexPublication(new QuestDbThsIndexTables(jdbc),path,state->{if(state.name().equals(phase)) throw new AbruptStop();});
                ThsIndexPublication.Result result;
                if(phase.equals("NORMAL")) result=publisher.publish(lease,table,prepared,stage,()->false);
                else {
                    assertThrows(AbruptStop.class,()->publisher.publish(lease,table,prepared,stage,()->false));
                    var recovery=new ThsIndexPublication(new QuestDbThsIndexTables(jdbc),path);
                    var expected=phase.equals("PREPARED")?ThsIndexPublication.Layout.ORIGINAL:ThsIndexPublication.Layout.valueOf(phase);
                    assertEquals(expected,recovery.inspect(run));
                    assertThrows(IllegalStateException.class,()->recovery.finish(lease,false));
                    // Synchronous publisher returned abruptly; the test owns the sole writer for this target/ledger.
                    result=recovery.finish(lease,true);
                }
                assertEquals(State.VERIFIED,result.publication().state());assertEquals(prepared.rows(),result.actual().rows());
                assertEquals(before,new ThsIndexStorage(jdbc,result.publication().intent().backup()).snapshot());
                assertTrue(QuestDbWriteChecks.walSettled(jdbc,table));
                cases.add(Map.of("phase",phase,"result",result,"backupVerified",true,"sourceReceipt","source-preflight.json"));
                var current=locks.findOwned(run,lease.scope());
                if(current.inDoubt()) locks.releaseAfterReconciliation(current,true,true);else locks.releaseVerified(current);
                jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+result.publication().intent().backup());
            }
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("publication-readback.json").toFile(),
                    Map.of("cases",cases,"injection","Error at durable publication phases; no OS process kill", "formalOwnerRunVerified",false));
        }
    }
}
