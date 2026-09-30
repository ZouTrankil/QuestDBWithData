package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.ThsIndexMapper;
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
class ThsIndexPreparedRecoveryLiveTest {
    @Test void preparedPublicationWithRejectedLedgerFinishRecoversWithoutSourceRequest() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            String table="java_d004_prepared_recovery_"+nonce;
            Path folder=Path.of("artifacts/java-migration/D004","prepared-recovery-"+nonce);Files.createDirectories(folder);
            Path path=folder.resolve("ledger.sqlite");
            jdbc.execute("CREATE TABLE \""+table+"\" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                    +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                    +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            var owner=new ThsIndexJobService(jdbc,context.getBean(TusharePageService.class),path,table);
            var mapper=new ThsIndexMapper();var rows=new ThsIndexStorage(jdbc,"ths_index").snapshot().businessRows().subList(0,2);
            var values=rows.stream().map(mapper::values).toList();
            var request=new WriteGroupRequest("ths-recovery",LocalDate.of(2026,9,29),
                    List.of(new WriteGroupRequest.Member("ths-member","ths_index",ThsIndexDataset.DEFINITION.schemaVersion(),
                            "ths-recovery-rows",values)));
            var plan=WriteGroupPlan.prepare(request,context.getBean(DatasetRegistry.class),Map.of("ths_index",owner.targetId()));
            var adapter=new ThsIndexPreparedWriteAdapter(plan,"ths-member",owner,folder,false);
            Path receipt=folder.resolve("write-evidence").resolve("prepared-input.json");Files.createDirectories(receipt.getParent());
            Files.writeString(receipt,JobDefinitionJson.mapper().writeValueAsString(Map.of(
                    "sourceKind","prepared-write-request","memberId","ths-member",
                    "batchId",adapter.member().batchId(),"targetId",adapter.member().targetId(),
                    "fingerprint",adapter.member().batch().fingerprint(),"rows",adapter.member().batch().rows())),
                    StandardOpenOption.CREATE_NEW);
            new SyncRunLedger(path);
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement()) {
                s.execute("CREATE TRIGGER reject_prepared_completion BEFORE UPDATE ON sync_entries "
                        +"WHEN OLD.kind='SLICE' AND NEW.state='VERIFIED' BEGIN SELECT RAISE(ABORT,'injected completion failure'); END");
            }
            String run="prepared-recovery-"+nonce;
            var failed=owner.executePrepared(run,null,adapter.request(),rows,receipt.toString());
            assertEquals(SyncRunState.IN_DOUBT,failed.state());assertNotNull(failed.publicationId());
            var before=new ThsIndexStorage(jdbc,table).snapshot();assertEquals(2,before.rows().size());
            var ledger=SyncRunLedger.openReadOnly(path);
            assertThrows(IllegalStateException.class,()->owner.finishInterrupted(run,false));
            byte[] originalReceipt=Files.readAllBytes(receipt);
            Files.writeString(receipt,"{}",StandardOpenOption.TRUNCATE_EXISTING);
            try {
                assertThrows(IllegalStateException.class,()->owner.finishInterrupted(run,true));
                assertEquals(before,new ThsIndexStorage(jdbc,table).snapshot());
                assertEquals(SyncRunState.IN_DOUBT,ledger.get(run).state());
                assertNotNull(new DatasetIntervalLock(path).findOwned(run,DatasetIntervalLock.Scope.allDates("ths_index")));
            } finally { Files.write(receipt,originalReceipt,StandardOpenOption.TRUNCATE_EXISTING); }
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement()) {
                s.execute("DROP TRIGGER reject_prepared_completion");
            }
            Path completion=path.toAbsolutePath().getParent().resolve("sync-evidence").resolve(run).resolve("completion.json");
            Files.move(completion,completion.resolveSibling("completion-original-test.json"));
            var recovered=owner.finishInterrupted(run,true);assertEquals(SyncRunState.VERIFIED,recovered.state());
            assertEquals(before,new ThsIndexStorage(jdbc,table).snapshot());
            for(String id:List.of(run,run+"-attempt",run+"-snapshot"))
                assertEquals(SyncRunState.VERIFIED,ledger.get(id).state());
            assertNull(new DatasetIntervalLock(path).findOwned(run,DatasetIntervalLock.Scope.allDates("ths_index")));
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("recovery-readback.json").toFile(),
                    Map.of("failed",failed,"recovered",recovered,"actual",before,"sourceRequestsDuringRecovery",0,
                            "rowInsertsDuringRecovery",0,"changedReceiptRejected",true,"missingWriterProofRejected",true));
            String backup=new ReferencePublicationJournal(path,"ths_index").forRun(run).intent().backup();
            jdbc.execute("DROP TABLE \""+table+"\"");jdbc.execute("DROP TABLE \""+backup+"\"");
        }
    }
}
