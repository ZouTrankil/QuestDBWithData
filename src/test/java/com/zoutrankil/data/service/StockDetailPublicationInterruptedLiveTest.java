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
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.repository.StockDetailPublicationJournal.State;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class StockDetailPublicationInterruptedLiveTest {
    static final class AbruptStop extends Error {}
    @Test void escapedFailureLeavesDurablePhaseAndRecoveryUsesActualLayout() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");var folder=Path.of("artifacts/java-migration/D002","interrupted-"+id);
            var path=Path.of("var","D002-interrupted-"+id+".sqlite");var ledger=new SyncRunLedger(path);var locks=new DatasetIntervalLock(path);
            var jdbc=ctx.getBean(JdbcTemplate.class);var columns=StockDetailInfoDataset.DEFINITION.columns();
            var source=new StockDetailInfoSource(ctx.getBean(TusharePageService.class),folder);
            var page=source.fetch("000001.SZ",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
            assertEquals(1,page.rows().size());var results=new ArrayList<Object>();
            for(var phase:List.of(State.OLD_RENAMED,State.NEW_RENAMED)) {
                String run="interrupted-"+phase.name();String target="java_d002_interrupt_"+phase.name().toLowerCase()+"_"+id;
                jdbc.execute("CREATE TABLE "+target+" ("+String.join(",",columns.stream().map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
                ledger.createRun(new SyncRunLedger.Run(run,null,"test.static_recovery",1,"2026-09-29","isolated","{}"));
                var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("stock_detail_info"));assertNotNull(lease);
                var before=new StockDetailInfoStorage(jdbc,target).snapshot();var prepared=StockDetailInfoStaging.prepare(before,page.rows());
                var stage=new StockDetailInfoStaging(jdbc).write(prepared,folder);
                var publisher=new StockDetailInfoPublication(jdbc,path,current->{if(current==phase) throw new AbruptStop();});
                assertThrows(AbruptStop.class,()->publisher.publish(lease,target,prepared,stage,()->false));
                // Error escapes Exception handling: find the persisted phase through a fresh SQLite connection.
                String publicationId;
                try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
                    var statement=db.prepareStatement("SELECT id FROM stock_detail_publications WHERE run_id=?")) {
                    statement.setString(1,run);try(var rows=statement.executeQuery()) { assertTrue(rows.next());publicationId=rows.getString(1);assertFalse(rows.next()); }
                }
                var journal=new StockDetailPublicationJournal(path);assertEquals(phase,journal.get(publicationId).state());
                assertFalse(journal.requireLease(lease,true));
                var recovery=new StockDetailInfoPublication(jdbc,path);
                assertThrows(IllegalStateException.class,()->recovery.acceptPublished(lease,publicationId,false));
                var inspected=recovery.inspect(publicationId);
                // The synchronous rename returned before the injected Error; this test's writer has stopped.
                var result=phase==State.OLD_RENAMED?recovery.restoreOriginal(lease,publicationId,true)
                        :recovery.acceptPublished(lease,publicationId,true);
                assertEquals(phase==State.OLD_RENAMED?State.ROLLED_BACK:State.VERIFIED,result.entry().state());
                var actual=new StockDetailInfoStorage(jdbc,target).snapshot();
                assertEquals(phase==State.OLD_RENAMED?before.rows():stage.snapshot().rows(),actual.rows());
                results.add(Map.of("interruptedPhase",phase,"observed",inspected,"recovered",result,"actual",actual));
                locks.releaseAfterReconciliation(lease,true,true);
                jdbc.execute("DROP TABLE "+target);
                jdbc.execute("DROP TABLE "+(phase==State.OLD_RENAMED?stage.table():result.entry().intent().backup()));
            }
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("recovery-readback.json").toFile(),
                    Map.of("source",page,"cases",results,"injection","Error after synchronous DDL; no OS process was killed"));
        }
    }
}
