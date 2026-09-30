package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class ThsIndexOwnerAcceptanceLiveTest {
    @Test void fullActualSourcePublishesToEmptyIsolatedTargetAndRerunKeepsIdentity() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var pages=context.getBean(TusharePageService.class);
            String nonce=UUID.randomUUID().toString().replace("-",""),table="java_d004_owner_"+nonce;
            Path folder=Path.of("artifacts/java-migration/D004","owner-"+nonce),ledger=folder.resolve("ledger.sqlite");
            Files.createDirectories(folder);
            var production=new ThsIndexStorage(jdbc,"ths_index").snapshot();
            jdbc.execute("CREATE TABLE \""+table+"\" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                    +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                    +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            var before=new ThsIndexStorage(jdbc,table).snapshot();assertTrue(before.rows().isEmpty());
            var owner=new ThsIndexJobService(jdbc,pages,ledger,table);
            var request=owner.plan(LocalDate.of(2026,9,29));var first=owner.run(request);
            assertEquals(SyncRunState.VERIFIED,first.state(),first.errorCode());
            assertEquals(first.sourceRows(),first.inserted());assertEquals(first.sourceRows(),first.verifiedRows());
            assertTrue(first.sourceRows()>0 && first.sourceRows()<5000);
            var after=new ThsIndexStorage(jdbc,table).snapshot();
            assertEquals(first.sourceRows(),after.rows().size());
            var journal=new ReferencePublicationJournal(ledger,"ths_index");
            var backupName=journal.forRun(first.runId()).intent().backup();
            assertEquals(before.rows(),new ThsIndexStorage(jdbc,backupName).snapshot().rows());
            var second=owner.run(owner.plan(LocalDate.of(2026,9,29)));
            assertEquals(SyncRunState.VERIFIED,second.state(),second.errorCode());
            assertNull(second.publicationId());assertEquals(0,second.inserted());assertEquals(0,second.revised());
            assertEquals(first.sourceRows(),second.unchanged());
            assertEquals(after,new ThsIndexStorage(jdbc,table).snapshot());
            assertEquals(production,new ThsIndexStorage(jdbc,"ths_index").snapshot());
            var state=SyncRunLedger.openReadOnly(ledger);
            assertEquals(SyncRunState.VERIFIED,state.get(first.runId()).state());
            assertEquals(SyncRunState.VERIFIED,state.get(second.runId()).state());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("owner-readback.json").toFile(),
                    Map.of("first",first,"second",second,"before",before,"after",after,
                            "productionIdentity",production.identity(),"productionFingerprint",production.fingerprint(),
                            "backup",backupName,"productionUnchanged",true));
            jdbc.execute("DROP TABLE \""+backupName+"\"");jdbc.execute("DROP TABLE \""+table+"\"");
        }
    }
}
