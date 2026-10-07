package com.zoutrankil.data.service;
import com.zoutrankil.data.stock.storage.QuestDbStockDetailTarget;

import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.stock.storage.StockDetailInfoStorage;
import com.zoutrankil.data.stock.storage.StockDetailPublicationJournal;

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
class StockDetailSliceRecoveryLiveTest {
    @Test void rejectedCompletionKeepsThreeLevelsUncertainUntilActualReadbackRecovery() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");String table="java_d002_slice_recovery_"+id;
            var path=Path.of("var","D002-slice-recovery-"+id+".sqlite");
            var evidence=Path.of("artifacts/java-migration/D002","slice-recovery-"+id);
            var ledger=new SyncRunLedger(path);var jdbc=ctx.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",StockDetailInfoDataset.DEFINITION.columns()
                    .stream().map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
            String backup=null;boolean verified=false;
            try {
                try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement()) {
                    s.execute("CREATE TRIGGER reject_test_completion BEFORE UPDATE ON sync_entries "
                            +"WHEN OLD.kind='SLICE' AND NEW.state='VERIFIED' "
                            +"BEGIN SELECT RAISE(ABORT,'injected completion failure'); END");
                }
                var owner=new StockDetailInfoJobService(ctx.getBean(TusharePageService.class),new QuestDbStockDetailTarget(jdbc,table),path);
                var failed=owner.run(owner.plan(List.of("000001.SZ"),false,LocalDate.of(2026,9,29)));
                assertEquals(SyncRunState.IN_DOUBT,failed.state());assertNotNull(failed.publicationId());
                for(String entry:List.of(failed.runId(),failed.runId()+"-attempt",failed.runId()+"-snapshot"))
                    assertEquals(SyncRunState.IN_DOUBT,ledger.get(entry).state());
                var beforeRecovery=new StockDetailInfoStorage(jdbc,table).snapshot();assertEquals(1,beforeRecovery.rows().size());
                backup=new StockDetailPublicationJournal(path).get(failed.publicationId()).intent().backup();
                var locks=new DatasetIntervalLock(path);var scope=DatasetIntervalLock.Scope.allDates("stock_detail_info");
                assertTrue(locks.findOwned(failed.runId(),scope).inDoubt());
                try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement()) {
                    s.execute("DROP TRIGGER reject_test_completion");
                }
                assertThrows(IllegalStateException.class,()->owner.reconcilePublished(failed.runId(),false));
                var completion=path.toAbsolutePath().getParent().resolve("sync-evidence").resolve(failed.runId()).resolve("completion.json");
                // Preserve the original receipt while simulating its absence after publication.
                Files.move(completion,completion.resolveSibling("completion-original-test.json"));
                assertFalse(Files.exists(completion));
                // The synchronous owner has returned: no producer/writer remains active in this test.
                var recovered=owner.reconcilePublished(failed.runId(),true);
                assertTrue(JobDefinitionJson.mapper().readTree(completion.toFile()).has("reconstructedFrom"));
                assertEquals(SyncRunState.VERIFIED,recovered.state());
                for(String entry:List.of(failed.runId(),failed.runId()+"-attempt",failed.runId()+"-snapshot"))
                    assertEquals(SyncRunState.VERIFIED,ledger.get(entry).state());
                assertNull(locks.findOwned(failed.runId(),scope));
                var afterRecovery=new StockDetailInfoStorage(jdbc,table).snapshot();
                assertEquals(beforeRecovery.identity(),afterRecovery.identity());
                assertEquals(beforeRecovery.rows(),afterRecovery.rows());
                Files.createDirectories(evidence);
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                        evidence.resolve("slice-recovery-readback.json").toFile(),Map.of("failed",failed,
                                "recovered",recovered,"beforeRecovery",beforeRecovery,"afterRecovery",afterRecovery,
                                "ledger",path.toString(),"recoveryDataWrites",0,
                                "writerStoppedProof","Synchronous owner returned before recovery invocation"));
                verified=true;
            } finally {
                if(verified) { jdbc.execute("DROP TABLE "+table);if(backup!=null) jdbc.execute("DROP TABLE "+backup); }
            }
        }
    }
}
