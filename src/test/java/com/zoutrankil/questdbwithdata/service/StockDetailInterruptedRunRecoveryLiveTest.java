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

/** Simulate an escaped Error after the first rename and recover the entire owner ledger. */
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class StockDetailInterruptedRunRecoveryLiveTest {
    static final class AbruptStop extends Error {}

    @Test void oldMovedCanFinishFromPreparedReceiptAndVerifyRunWithoutRefetch() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f)
                .removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");String table="java_d002_hardstop_"+id;
            String runId="hardstop-"+UUID.randomUUID();
            Path ledgerPath=Path.of("var","D002-hardstop-"+id+".sqlite");
            Path evidence=ledgerPath.toAbsolutePath().getParent().resolve("sync-evidence").resolve(runId);
            var jdbc=ctx.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",StockDetailInfoDataset.DEFINITION.columns()
                    .stream().map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
            boolean verified=false;String backup=null;
            try {
                var owner=new StockDetailInfoJobService(ctx.getBean(TusharePageService.class),jdbc,ledgerPath,table);
                var request=owner.plan(List.of("000001.SZ"),false,LocalDate.of(2026,9,29));
                var ledger=new SyncRunLedger(ledgerPath);
                ledger.createRun(runId,null,owner.targetId(),request);
                ledger.transition(runId,ledger.get(runId).revision(),SyncRunState.RUNNING,"{}");
                String attempt=runId+"-attempt",slice=runId+"-snapshot";
                ledger.createChild(attempt,SyncRunLedger.Kind.ATTEMPT,runId,runId);
                ledger.transition(attempt,ledger.get(attempt).revision(),SyncRunState.RUNNING,"{}");
                ledger.createChild(slice,SyncRunLedger.Kind.SLICE,runId,attempt);
                ledger.transition(slice,ledger.get(slice).revision(),SyncRunState.RUNNING,"{}");
                var locks=new DatasetIntervalLock(ledgerPath);
                var lease=locks.acquire(runId,DatasetIntervalLock.Scope.allDates("stock_detail_info"));assertNotNull(lease);
                var before=new StockDetailInfoStorage(jdbc,table).snapshot();
                var observed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                var source=new StockDetailInfoSource(ctx.getBean(TusharePageService.class),evidence)
                        .fetch("000001.SZ",observed,()->false);
                assertEquals(1,source.rows().size());
                var prepared=StockDetailInfoStaging.prepare(before,source.rows());
                StockDetailRecoveryEvidence.prepare(evidence,runId,owner.targetId(),request,observed,
                        source.rows(),List.of(source.responseEvidence()),prepared);
                var stage=new StockDetailInfoStaging(jdbc).write(prepared,evidence);
                var publisher=new StockDetailInfoPublication(jdbc,ledgerPath,
                        state->{if(state==StockDetailPublicationJournal.State.OLD_RENAMED) throw new AbruptStop();});
                assertThrows(AbruptStop.class,()->publisher.publish(lease,table,prepared,stage,()->false));
                var journal=new StockDetailPublicationJournal(ledgerPath);
                var intent=journal.requireSingleForRun(runId).intent();backup=intent.backup();
                assertEquals(StockDetailInfoPublication.Layout.OLD_MOVED,
                        new StockDetailInfoPublication(jdbc,ledgerPath).inspect(intent.id()).layout());
                assertEquals(SyncRunState.RUNNING,ledger.get(runId).state());
                assertThrows(IllegalStateException.class,()->owner.finishInterrupted(runId,false));
                // The synchronous injected writer has returned; source evidence and stage are retained.
                var result=owner.finishInterrupted(runId,true);
                assertEquals(SyncRunState.VERIFIED,result.state());
                assertEquals(1,result.verifiedRows());
                for(var entry:List.of(runId,attempt,slice)) assertEquals(SyncRunState.VERIFIED,ledger.get(entry).state());
                assertNull(locks.findOwned(runId,DatasetIntervalLock.Scope.allDates("stock_detail_info")));
                var after=new StockDetailInfoStorage(jdbc,table).snapshot();
                assertEquals(prepared.rows(),after.rows());
                assertEquals(before.rows(),new StockDetailInfoStorage(jdbc,backup).snapshot().rows());
                var proof=JobDefinitionJson.mapper().readTree(evidence.resolve("completion.json").toFile());
                assertTrue(proof.has("reconstructedFrom"));
                Path report=Path.of("artifacts/java-migration/D002","hardstop-"+id+".json");
                Files.createDirectories(report.getParent());
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(),Map.of(
                        "run",result,"target",table,"before",before,"after",after,
                        "sourceReceipt",source.responseEvidence(),"publicationId",intent.id(),
                        "sourceRefetchesAfterStop",0,"writerStoppedProof","Injected synchronous Error returned"));
                verified=true;
            } finally {
                if(verified) { jdbc.execute("DROP TABLE "+table);if(backup!=null) jdbc.execute("DROP TABLE "+backup); }
            }
        }
    }
}
