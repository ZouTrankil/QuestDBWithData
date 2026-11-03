package com.zoutrankil.market.cn.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.config.DatasetConfiguration;
import com.zoutrankil.data.domain.stock.reference.StockShockDatasets;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.application.ContractTableSyncOwner;
import com.zoutrankil.market.cn.observation.cli.ContractTableCommands;
import com.zoutrankil.market.cn.observation.config.ContractTableConfiguration;
import com.zoutrankil.market.cn.observation.mapper.ContractTableDefinition;

import io.questdb.client.QuestDB;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

class ContractTableConfigurationTest {
    @Test
    void eachOwnerHasOneDefinitionReadBindingAndCliWithoutStartingAnyWriter() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var q = mock(QuestDB.class);
            var pages = mock(TusharePageService.class);
            context.registerBean(
                    JdbcTemplate.class, () -> new JdbcTemplate(mock(DataSource.class)));
            context.registerBean(QuestDB.class, () -> q);
            context.registerBean(TusharePageService.class, () -> pages);
            context.register(DatasetConfiguration.class, ContractTableConfiguration.class);
            context.refresh();
            var registry = context.getBean(SyncJobRegistry.class);
            for (String dataset : ContractTableDefinition.DATASETS) {
                var job = registry.require("data." + dataset, 1);
                assertEquals("contract_table_owner", job.owner());
                assertFalse(job.dailyEligible());
            }
            assertEquals(
                    ContractTableDefinition.DATASETS.size(),
                    context.getBeansOfType(ContractTableSyncOwner.class).size());
            var datasets = context.getBean(DatasetRegistry.class);
            var bindings =
                    context.getBeansOfType(ContractTableSyncOwner.class).values().stream()
                            .map(
                                    o ->
                                            ReadBindingCatalog.generic(
                                                    o.datasetId(), o.definition().schemaVersion()))
                            .toList();
            assertEquals(
                    ContractTableDefinition.DATASETS.size(),
                    new ReadBindingCatalog(bindings).bind(datasets).size());
            for (String id : Set.of("stk_shock", "stk_high_shock")) {
                var current = datasets.require(id).definition();
                var original =
                        id.equals("stk_shock")
                                ? StockShockDatasets.SHOCK
                                : StockShockDatasets.HIGH_SHOCK;
                assertEquals(2, current.schemaVersion());
                assertEquals(original.columns(), current.columns());
                assertEquals(original.businessKey(), current.businessKey());
                assertEquals(1, datasets.requireDefinition(id, 1).schemaVersion());
            }
            try (var checks = Mockito.mockStatic(QuestDbWriteChecks.class)) {
                for (String id : Set.of("broker_recommend", "stk_alert", "sge_daily")) {
                    var jdbc = context.getBean(JdbcTemplate.class);
                    var physical = ContractTableDefinition.from(id);
                    checks.when(() -> QuestDbWriteChecks.preflight(jdbc, id, physical))
                            .thenThrow(new IllegalStateException("旧键不匹配"));
                    var registered =
                            context
                                    .getBeansOfType(ReadBindingCatalog.Registration.class)
                                    .values()
                                    .stream()
                                    .filter(r -> r.datasetId().equals(id))
                                    .findFirst()
                                    .orElseThrow();
                    assertThrows(
                            IllegalStateException.class, () -> registered.sourceVersion().get());
                }
            }
            var cli = context.getBean(ContractTableCommands.class);
            assertEquals(4, cli.commands().size());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            cli.execute(
                                    "resume-contract-table",
                                    Map.of(
                                            "--dataset",
                                            "fut_daily",
                                            "--run",
                                            "id",
                                            "--code",
                                            "IF2612.CFX")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cli.execute("run-contract-table", Map.of("--dataset", "missing")));
            verifyNoInteractions(q, pages);
        }
    }
}
