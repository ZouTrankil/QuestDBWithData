package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.client.dto.TushareThsIndexDto;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.ThsIndexMapper;
import com.zoutrankil.questdbwithdata.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class ThsIndexStageRecoveryLiveTest {
    @Test void incompleteOwnedStageIsRetainedAndFreshFullStageIsVerifiedBeforePublication() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-",""),table="java_d004_stage_recovery_"+nonce;
            Path folder=Path.of("artifacts/java-migration/D004","stage-recovery-"+nonce);Files.createDirectories(folder);
            Path path=folder.resolve("ledger.sqlite");var ledger=new SyncRunLedger(path);
            jdbc.execute("CREATE TABLE \""+table+"\" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                    +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                    +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            var owner=new ThsIndexJobService(jdbc,context.getBean(TusharePageService.class),path,table);
            var before=new ThsIndexStorage(jdbc,table).snapshot();var request=owner.plan(LocalDate.of(2026,9,29));
            String run="ths-stage-recovery-"+nonce,target=owner.targetId();
            ledger.createRun(run,null,target,request);ledger.transition(run,0,SyncRunState.RUNNING,"{}");
            ledger.createChild(run+"-attempt",SyncRunLedger.Kind.ATTEMPT,run,run);
            ledger.transition(run+"-attempt",0,SyncRunState.RUNNING,"{}");
            ledger.createChild(run+"-snapshot",SyncRunLedger.Kind.SLICE,run,run+"-attempt");
            ledger.transition(run+"-snapshot",0,SyncRunState.RUNNING,"{}");
            var locks=new DatasetIntervalLock(path);var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("ths_index"));
            assertNotNull(lease);locks.retainInDoubt(lease);
            Path evidence=path.toAbsolutePath().getParent().resolve("sync-evidence").resolve(run);Files.createDirectories(evidence);
            var observed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var rows=json.readTree(Path.of("artifacts/java-migration/D004/source-preflight.json").toFile()).path("rows");
            var raw=new ArrayList<JsonNode>();var typed=new ArrayList<ThsIndex>();var mapper=new ThsIndexMapper();
            for(var row:rows) {
                raw.add(row);typed.add(mapper.fromSource(new TushareThsIndexDto(text(row,"ts_code"),text(row,"name"),
                        row.path("count").isNull()?null:row.path("count").intValue(),text(row,"exchange"),
                        text(row,"list_date"),text(row,"type")),observed));
            }
            assertEquals(2517,typed.size());
            var receiptBody=new LinkedHashMap<String,Object>();receiptBody.put("endpoint","ths_index");
            receiptBody.put("parameters",Map.of());receiptBody.put("observedAt",observed);
            receiptBody.put("rows",raw);receiptBody.put("completion",Map.of("pages",1,"rows",raw.size()));
            byte[] bytes=json.writeValueAsBytes(receiptBody);Path sourceReceipt=evidence.resolve("source-replayed.json");
            Files.write(sourceReceipt,bytes,StandardOpenOption.CREATE_NEW);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var source=new SyncJobRunner.Page<>(typed,hash,sourceReceipt.toString(),null);
            var prepared=ThsIndexStaging.prepare(before,source.rows(),ThsIndexSource.Scope.all());
            Files.writeString(evidence.resolve("prepared.json"),json.writeValueAsString(Map.of("runId",run,"targetId",target,
                    "request",SyncRequestIdentity.snapshotJson(request),"source",source,"prepared",prepared)),
                    StandardOpenOption.CREATE_NEW);
            String incomplete="java_ths_index_stage_"+UUID.randomUUID().toString().replace("-","");
            Files.writeString(evidence.resolve(incomplete+"-intent.json"),json.writeValueAsString(Map.of(
                    "stage",incomplete,"prepared",prepared,"batches",11,"maxBatchRows",250,"estimatedBatchBytes",256*1024)),
                    StandardOpenOption.CREATE_NEW);
            jdbc.execute("CREATE TABLE \""+incomplete+"\" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                    +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                    +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            assertEquals(0,new ThsIndexStorage(jdbc,incomplete).snapshot().rows().size());
            assertThrows(IllegalStateException.class,()->owner.finishInterrupted(run,false));
            var recovered=owner.finishInterrupted(run,true);assertEquals(SyncRunState.VERIFIED,recovered.state());
            var actual=new ThsIndexStorage(jdbc,table).snapshot();assertEquals(prepared.rows(),actual.rows());
            assertEquals(0,new ThsIndexStorage(jdbc,incomplete).snapshot().rows().size());
            var publication=new ReferencePublicationJournal(path,"ths_index").forRun(run);
            assertNotEquals(incomplete,publication.intent().stage());
            assertNull(locks.findOwned(run,lease.scope()));
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("stage-recovery-readback.json").toFile(),
                    Map.of("recovered",recovered,"originalIncompleteStage",incomplete,"retainedRows",0,
                            "replacementStage",publication.intent().stage(),"actual",actual,
                            "sourceRequestsDuringRecovery",0,"originalTargetRows",before.rows().size()));
            jdbc.execute("DROP TABLE \""+incomplete+"\"");
            jdbc.execute("DROP TABLE \""+table+"\"");
            jdbc.execute("DROP TABLE \""+publication.intent().backup()+"\"");
        }
    }
    private static String text(JsonNode row,String field) { return row.path(field).isNull()?null:row.path(field).asText(); }
}
