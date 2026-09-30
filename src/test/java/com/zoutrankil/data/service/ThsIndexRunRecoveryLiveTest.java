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
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class ThsIndexRunRecoveryLiveTest {
    @Test void ledgerCompletionFailureRecoversVerifiedPhysicalPublicationWithoutSourceCall() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            String table="java_d004_recovery_"+nonce;
            Path folder=Path.of("artifacts/java-migration/D004","run-recovery-"+nonce);Files.createDirectories(folder);
            Path path=folder.resolve("ledger.sqlite");var ledger=new SyncRunLedger(path);
            jdbc.execute("CREATE TABLE \""+table+"\" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                    +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                    +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement()) {
                s.execute("CREATE TRIGGER reject_ths_completion BEFORE UPDATE ON sync_entries "
                        +"WHEN OLD.kind='SLICE' AND NEW.state='VERIFIED' BEGIN SELECT RAISE(ABORT,'injected completion failure'); END");
            }
            var owner=new ThsIndexJobService(jdbc,context.getBean(TusharePageService.class),path,table);
            var request=owner.plan(LocalDate.of(2026,9,29));var failed=owner.run(request);
            assertEquals(SyncRunState.IN_DOUBT,failed.state());assertNotNull(failed.publicationId());
            var before=new ThsIndexStorage(jdbc,table).snapshot();assertEquals(failed.sourceRows(),before.rows().size());
            for(String id:List.of(failed.runId()+"-snapshot",failed.runId()+"-attempt",failed.runId()))
                assertEquals(SyncRunState.IN_DOUBT,ledger.get(id).state());
            assertThrows(IllegalStateException.class,()->owner.finishInterrupted(failed.runId(),false));
            assertThrows(IllegalStateException.class,()->owner.resume(request,failed.runId()));
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement()) {
                s.execute("DROP TRIGGER reject_ths_completion");
            }
            var completion=path.toAbsolutePath().getParent().resolve("sync-evidence").resolve(failed.runId()).resolve("completion.json");
            Files.move(completion,completion.resolveSibling("completion-original-test.json"));
            var recovered=owner.finishInterrupted(failed.runId(),true);
            assertEquals(SyncRunState.VERIFIED,recovered.state());assertEquals(failed.sourceRows(),recovered.verifiedRows());
            for(String id:List.of(failed.runId()+"-snapshot",failed.runId()+"-attempt",failed.runId()))
                assertEquals(SyncRunState.VERIFIED,ledger.get(id).state());
            var after=new ThsIndexStorage(jdbc,table).snapshot();assertEquals(before,after);
            assertNull(new DatasetIntervalLock(path).findOwned(failed.runId(),DatasetIntervalLock.Scope.allDates("ths_index")));
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("recovery-readback.json").toFile(),
                    Map.of("failed",failed,"recovered",recovered,"before",before,"after",after,
                            "sourceRequestsDuringRecovery",0,"rowInsertsDuringRecovery",0,"writerStoppedProof","Synchronous owner returned"));
            String backup=new ReferencePublicationJournal(path,"ths_index").forRun(failed.runId()).intent().backup();
            jdbc.execute("DROP TABLE \""+table+"\"");jdbc.execute("DROP TABLE \""+backup+"\"");
        }
    }
}
