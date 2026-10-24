package com.zoutrankil.market.cn.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.config.DatasetConfiguration;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.application.SnapshotEventSyncOwner;
import com.zoutrankil.market.cn.observation.cli.SnapshotEventCommands;
import com.zoutrankil.market.cn.observation.config.SnapshotEventConfiguration;

import io.questdb.client.QuestDB;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import javax.sql.DataSource;

class SnapshotEventConfigurationTest {
    @Test
    void twoOwnersExposeExplicitV2JobsAndStableReadsWithoutStartingAnyWriter() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var db = mock(DataSource.class);
            var questdb = mock(QuestDB.class);
            var pages = mock(TusharePageService.class);
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(db));
            context.registerBean(QuestDB.class, () -> questdb);
            context.registerBean(TusharePageService.class, () -> pages);
            context.register(DatasetConfiguration.class, SnapshotEventConfiguration.class);
            context.refresh();
            var jobs = context.getBean(SyncJobRegistry.class);
            var datasets = context.getBean(DatasetRegistry.class);
            assertEquals(2, context.getBeansOfType(SnapshotEventSyncOwner.class).size());
            for (String dataset : Set.of("repurchase", "block_trade")) {
                var job = jobs.require("data." + dataset, 2);
                assertEquals(2, job.datasetVersion());
                assertFalse(job.dailyEligible());
                assertEquals(2, datasets.require(dataset).definition().schemaVersion());
                var binding =
                        context
                                .getBeansOfType(ReadBindingCatalog.Registration.class)
                                .values()
                                .stream()
                                .filter(r -> r.datasetId().equals(dataset))
                                .findFirst()
                                .orElseThrow();
                assertTrue(binding.verifyAfterRead());
            }
            assertEquals(5, context.getBean(SnapshotEventCommands.class).commands().size());
            verifyNoInteractions(db, questdb, pages);
        }
    }
}
