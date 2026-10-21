package com.zoutrankil.market.cn.futures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.config.DatasetConfiguration;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.futures.application.FuturesDaySyncOwner;
import com.zoutrankil.market.cn.futures.cli.FuturesDayCommands;
import com.zoutrankil.market.cn.futures.config.FuturesDayConfiguration;
import com.zoutrankil.market.cn.futures.mapper.FuturesDayDefinition;

import io.questdb.client.QuestDB;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import javax.sql.DataSource;

class FuturesDayConfigurationTest {
    @Test
    void eachOwnerHasOneDefinitionReadBindingAndCliWithoutStartingAnyWriter() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var q = mock(QuestDB.class);
            var pages = mock(TusharePageService.class);
            context.registerBean(
                    JdbcTemplate.class, () -> new JdbcTemplate(mock(DataSource.class)));
            context.registerBean(QuestDB.class, () -> q);
            context.registerBean(TusharePageService.class, () -> pages);
            context.register(DatasetConfiguration.class, FuturesDayConfiguration.class);
            context.refresh();
            var registry = context.getBean(SyncJobRegistry.class);
            for (String dataset : FuturesDayDefinition.DATASETS) {
                var job = registry.require("data." + dataset, 1);
                assertEquals("futures_day_owner", job.owner());
                assertFalse(job.dailyEligible());
            }
            assertEquals(
                    FuturesDayDefinition.DATASETS.size(),
                    context.getBeansOfType(FuturesDaySyncOwner.class).size());
            var cli = context.getBean(FuturesDayCommands.class);
            assertEquals(4, cli.commands().size());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            cli.execute(
                                    "resume-futures-day",
                                    Map.of(
                                            "--dataset",
                                            "fut_daily",
                                            "--run",
                                            "id",
                                            "--code",
                                            "IF2612.CFX")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cli.execute("run-futures-day", Map.of("--dataset", "missing")));
            verifyNoInteractions(q, pages);
        }
    }
}
