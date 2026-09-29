package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexCatalogOwnerBoundaryLiveTest {
    @Test void emptyChangedFileCancellationAndBusyLeaseCannotModifyExistingCatalog() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            String table="java_d003_boundary_"+nonce;Path folder=Path.of("artifacts/java-migration/D003","boundary-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");
            jdbc.execute("CREATE TABLE "+table+" AS (SELECT * FROM \"index\") TIMESTAMP(import_time) PARTITION BY MONTH WAL");
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,table)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Clone WAL did not settle");Thread.sleep(50);
            }
            var original=new IndexCatalogStorage(jdbc,table).snapshot();assertFalse(original.rows().isEmpty());
            var owner=new IndexCatalogJobService(jdbc,path,table);var day=LocalDate.of(2026,9,29);
            Path file=folder.resolve("candidate.csv");String header=String.join(",",IndexCatalogFileSource.HEADERS)+"\n";
            Files.writeString(file,header);var empty=owner.run(owner.plan(file,day));
            assertEquals(SyncRunState.VERIFIED_EMPTY,empty.state());assertNull(empty.publicationId());assertEquals(0,empty.sourceRows());
            assertEquals(original,new IndexCatalogStorage(jdbc,table).snapshot());
            byte[] real=Files.readAllBytes(Path.of("artifacts/java-migration/D003/source-catalog.csv"));
            Files.write(file,real);var frozen=owner.plan(file,day);Files.writeString(file,header);
            var changed=owner.run(frozen);assertEquals(SyncRunState.FAILED,changed.state());assertNull(changed.publicationId());
            assertEquals(original,new IndexCatalogStorage(jdbc,table).snapshot());
            Files.write(file,real);
            var ledger=new SyncRunLedger(path);var locks=new DatasetIntervalLock(path);
            String parent="catalog-cancel-parent-"+nonce;ledger.createRun(parent,null,owner.targetId(),frozen);
            ledger.requestCancellation(parent);
            var cancelled=owner.execute("catalog-cancel-child-"+nonce,parent,frozen);
            assertEquals(SyncRunState.CANCELLED,cancelled.state());assertNull(cancelled.publicationId());
            assertEquals(original,new IndexCatalogStorage(jdbc,table).snapshot());
            String holder="catalog-lock-holder-"+nonce;ledger.createRun(holder,null,owner.targetId(),frozen);
            var scope=DatasetIntervalLock.Scope.allDates("index");var lease=locks.acquire(holder,scope);assertNotNull(lease);
            var busy=owner.run(frozen);assertEquals(SyncRunState.FAILED,busy.state());assertEquals("DATASET_INTERVAL_BUSY",busy.errorCode());
            assertEquals(lease.id(),locks.findOwned(holder,scope).id());assertEquals(original,new IndexCatalogStorage(jdbc,table).snapshot());
            locks.releaseVerified(lease);
            // Restore the original frozen file, then replay only the failed pre-write observation.
            var resumed=owner.resume(frozen,changed.runId());assertEquals(SyncRunState.VERIFIED,resumed.state());
            var after=new IndexCatalogStorage(jdbc,table).snapshot();assertEquals(2803,after.rows().size());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("boundary-readback.json").toFile(),
                    Map.of("empty",empty,"fileChanged",changed,"cancelled",cancelled,"busy",busy,"resumed",resumed,
                            "before",original,"after",after,"boundaryTargetUnchanged",true));
            String backup=new ReferencePublicationJournal(path,"index").forRun(resumed.runId()).intent().backup();
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+backup);
        }
    }
}
