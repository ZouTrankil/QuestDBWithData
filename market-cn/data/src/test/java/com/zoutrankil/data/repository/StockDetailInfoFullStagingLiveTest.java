package com.zoutrankil.data.repository;

import com.zoutrankil.data.stock.storage.StockDetailInfoStaging;
import com.zoutrankil.data.stock.storage.StockDetailInfoStorage;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.stock.application.StockDetailInfoDiscovery;
import com.zoutrankil.data.service.TusharePageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE", matches="1")
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
class StockDetailInfoFullStagingLiveTest {
    @Test void fullBoundedDiscoveryMergesWithPhysicalSnapshotAndStagesEveryField() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var ctx = app.run()) {
            var folder = Path.of("artifacts/java-migration/D002", "full-stage-" + UUID.randomUUID());
            var jdbc = ctx.getBean(JdbcTemplate.class);
            var production = new StockDetailInfoStorage(jdbc,"stock_detail_info");
            var before = production.snapshot();
            var source = new StockDetailInfoDiscovery(ctx.getBean(TusharePageService.class), folder);
            var fetched = source.fetch(Instant.now().truncatedTo(ChronoUnit.MICROS), () -> false);
            var prepared = StockDetailInfoStaging.prepare(before, fetched.rows());
            assertEquals(fetched.rows().size(), prepared.merge().sourceRows());
            assertEquals(before.rows().size() + prepared.merge().insertedRows(), prepared.rows().size());
            assertEquals(prepared.rows().size(),prepared.rows().stream().map(r -> r.tsCode()).distinct().count());
            assertTrue(prepared.merge().requiresPublication());
            var staged = new StockDetailInfoStaging(jdbc).write(prepared,folder);
            boolean verified = false;
            try {
                assertEquals(prepared.rows(),staged.snapshot().rows());
                assertEquals(before.identity(),production.preflight());
                assertEquals(before.rows(),production.snapshot().rows());
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                        folder.resolve("full-stage-readback.json").toFile(),Map.ofEntries(
                                Map.entry("sourceRows",fetched.rows().size()),
                                Map.entry("sourceSlices",fetched.sliceCounts()),
                                Map.entry("targetBeforeRows",before.rows().size()),
                                Map.entry("targetBeforeIdentity",before.identity()),
                                Map.entry("targetBeforeFingerprint",before.fingerprint()),
                                Map.entry("stageTable",staged.table()),
                                Map.entry("stageIdentity",staged.snapshot().identity()),
                                Map.entry("stageFingerprint",staged.snapshot().fingerprint()),
                                Map.entry("stageRows",staged.snapshot().rows().size()),
                                Map.entry("inserted",prepared.merge().insertedRows()),
                                Map.entry("updated",prepared.merge().updatedRows()),
                                Map.entry("unchanged",prepared.merge().unchangedRows()),
                                Map.entry("productionUnchanged",true)));
                verified = true;
            } finally {
                if (verified) jdbc.execute("DROP TABLE " + staged.table());
            }
        }
    }
}
