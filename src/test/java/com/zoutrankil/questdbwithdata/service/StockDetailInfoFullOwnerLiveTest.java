package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.StockDetailInfoDataset;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.StockDetailInfoStaging;
import com.zoutrankil.questdbwithdata.repository.StockDetailInfoStorage;
import com.zoutrankil.questdbwithdata.repository.StockDetailPublicationJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE", matches="1")
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
class StockDetailInfoFullOwnerLiveTest {
    @Test void discoverAllStatusesAndPublishRealNewAndRevisedRowsToIsolatedClone() throws Exception {
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var ctx = app.run()) {
            var jdbc = ctx.getBean(JdbcTemplate.class);
            var pages = ctx.getBean(TusharePageService.class);
            var suffix = UUID.randomUUID().toString().replace("-","");
            var seed = "java_d002_seed_" + suffix;
            var target = "java_d002_full_" + suffix;
            var folder = Path.of("artifacts/java-migration/D002", "full-owner-" + suffix);
            Files.createDirectories(folder);
            var ledger = folder.resolve("ledger.sqlite");
            var columns = StockDetailInfoDataset.DEFINITION.columns();
            jdbc.execute("CREATE TABLE " + seed + " (" + String.join(",", columns.stream()
                    .map(c -> c.storageName() + " " + c.storageType().name()).toList()) + ")");
            var cleanup = new ArrayList<String>();
            cleanup.add(seed);
            boolean verified = false;
            try {
                var production = new StockDetailInfoStorage(jdbc,"stock_detail_info");
                var productionBefore = production.snapshot();
                assertTrue(productionBefore.rows().size() > 0);
                var empty = new StockDetailInfoStorage(jdbc,seed).snapshot();
                var cloned = new StockDetailInfoStaging(jdbc).write(
                        StockDetailInfoStaging.prepare(empty,productionBefore.businessRows()),
                        folder.resolve("clone"));
                jdbc.execute("RENAME TABLE " + cloned.table() + " TO " + target);
                cleanup.add(target);
                var seeded = new StockDetailInfoStorage(jdbc,target).snapshot();
                assertEquals(productionBefore.rows().size(),seeded.rows().size());

                var owner = new StockDetailInfoJobService(pages,jdbc,ledger,target);
                var result = owner.run(owner.plan(List.of(),true,LocalDate.of(2026,9,29)));
                assertEquals(SyncRunState.VERIFIED,result.state(),result.errorCode());
                assertEquals(result.sourceRows(),result.verifiedRows());
                assertTrue(result.insertedRows() > 0,"Observed source must include a genuinely new code");
                assertTrue(result.updatedRows() > 0,"Observed source must include a real business revision");
                assertEquals(result.sourceRows(),result.insertedRows()+result.updatedRows()+result.unchangedRows());
                var after = new StockDetailInfoStorage(jdbc,target).snapshot();
                assertEquals(seeded.rows().size()+result.insertedRows(),after.rows().size());
                assertEquals(productionBefore.identity(),production.preflight());
                assertEquals(productionBefore.fingerprint(),production.snapshot().fingerprint());
                var backup = new StockDetailPublicationJournal(ledger).get(result.publicationId()).intent().backup();
                cleanup.add(backup);
                assertEquals(seeded.rows(),new StockDetailInfoStorage(jdbc,backup).snapshot().rows());
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                        folder.resolve("full-owner-readback.json").toFile(),Map.of(
                                "run",result,"sourceRows",result.sourceRows(),"beforeRows",seeded.rows().size(),
                                "afterRows",after.rows().size(),"afterFingerprint",after.fingerprint(),
                                "productionBeforeFingerprint",productionBefore.fingerprint(),
                                "productionUnchanged",true,"backupVerified",true));
                verified = true;
            } finally {
                if (verified) for (var table : cleanup) jdbc.execute("DROP TABLE " + table);
            }
        }
    }
}
