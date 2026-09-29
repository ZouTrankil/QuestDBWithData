package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.IndexMembershipMapper;
import com.zoutrankil.questdbwithdata.repository.*;
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
class IndexMembershipPreparedRecoveryLiveTest {
    @Test void stoppedWriterRecoversVerifiedStageWithoutReinterpretingPreparedRowsAsTushare() throws Exception {
        verify(false);
    }
    @Test void stoppedWriterRecoversAlreadyPublishedRowsWithoutSecondPublication() throws Exception {
        verify(true);
    }
    private void verify(boolean failAfterPublication) throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            String table="java_d005_prepared_recovery_"+nonce;
            Path folder=Path.of("artifacts/java-migration/D005","prepared-recovery-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");
            var formal=new IndexMembershipStorage(jdbc,"index_member").snapshot();
            var columns=IndexMembershipDataset.DEFINITION.columns();
            jdbc.execute("CREATE TABLE \""+table+"\" ("+String.join(",",columns.stream()
                    .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())
                    +") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
            var rows=List.of(formal.businessRows().getFirst());var mapper=new IndexMembershipMapper();
            var datasets=context.getBean(DatasetRegistry.class);
            var groupRequest=new WriteGroupRequest("membership-recovery",LocalDate.of(2026,9,29),
                    List.of(new WriteGroupRequest.Member("member","index_member",1,"prepared-row",
                            rows.stream().map(mapper::values).toList())));
            String target=new IndexMembershipSliceJob(jdbc,context.getBean(TusharePageService.class),path,table).targetId();
            var plan=WriteGroupPlan.prepare(groupRequest,datasets,Map.of("index_member",target));
            var owner=new IndexMembershipJobService(jdbc,context.getBean(TusharePageService.class),path,table);
            var adapter=new IndexMembershipPreparedWriteAdapter(plan,"member",owner,folder,false);
            Path receipt=folder.resolve("prepared-input.json");
            Files.writeString(receipt,JobDefinitionJson.mapper().writeValueAsString(Map.of(
                    "sourceKind","prepared-write-request","targetId",target,
                    "fingerprint",plan.members().getFirst().batch().fingerprint(),
                    "rows",plan.members().getFirst().batch().rows())));
            String run="membership-prepared-"+nonce;
            IndexMembershipPreparedJob.Hook hook=failAfterPublication?new IndexMembershipPreparedJob.Hook() {
                public void afterStage(String id) {}
                public void afterPublication(String id) throws Exception { throw new java.io.IOException("STOPPED_AFTER_PUBLICATION"); }
            }:id->{throw new java.io.IOException("STOPPED_AFTER_STAGE");};
            var stopped=new IndexMembershipPreparedJob(jdbc,path,table,hook)
                    .execute(run,null,adapter.request(),rows,receipt);
            assertEquals(SyncRunState.IN_DOUBT,stopped.state());
            var beforeRecovery=new IndexMembershipStorage(jdbc,table).snapshot();
            assertEquals(failAfterPublication?1:0,beforeRecovery.rows().size());
            assertThrows(IllegalStateException.class,()->owner.finishPreparedChild(run,false));
            var recovered=owner.finishPreparedChild(run,true);assertEquals(SyncRunState.VERIFIED,recovered.state());
            assertEquals(1,recovered.verified());
            var actual=new IndexMembershipStorage(jdbc,table).snapshot();assertEquals(1,actual.rows().size());
            assertEquals(rows.getFirst(),actual.businessRows().getFirst());
            owner.revalidatePreparedChild(run,target,adapter.request());
            assertEquals(formal,new IndexMembershipStorage(jdbc,"index_member").snapshot());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("recovery-readback.json").toFile(),
                    Map.of("stopped",stopped,"recovered",recovered,"actual",actual,
                            "failAfterPublication",failAfterPublication,"formalUnchanged",true));
            String backup=new ReferencePublicationJournal(path,"index_member").forRun(run).intent().backup();
            jdbc.execute("DROP TABLE \""+table+"\"");jdbc.execute("DROP TABLE \""+backup+"\"");
        }
    }
}
