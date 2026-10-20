package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.basic.StockDetailInfoDataset;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.stock.storage.QuestDbStockDetailTarget;
import com.zoutrankil.data.stock.storage.StockDetailInfoStorage;
import com.zoutrankil.data.stock.storage.StockDetailPublicationJournal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "QUESTDB_WRITE_LIVE", matches = "1")
class StockDetailOwnerResumeLiveTest {
    @Test
    void sourceFailureBeforeSubmissionCanReplayExactScopeAndPublishOnce() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(
                c ->
                        c.addBeanFactoryPostProcessor(
                                f ->
                                        ((BeanDefinitionRegistry) f)
                                                .removeBeanDefinition("commandLineRunner")));
        try (var ctx = app.run()) {
            String id = UUID.randomUUID().toString().replace("-", "");
            String target = "java_d002_resume_" + id;
            Path ledgerPath = Path.of("var", "D002-resume-" + id + ".sqlite");
            Path evidence = Path.of("artifacts/java-migration/D002", "resume-" + id);
            var jdbc = ctx.getBean(JdbcTemplate.class);
            var columns = StockDetailInfoDataset.DEFINITION.columns();
            jdbc.execute(
                    "CREATE TABLE "
                            + target
                            + " ("
                            + String.join(
                                    ",",
                                    columns.stream()
                                            .map(
                                                    c ->
                                                            c.storageName()
                                                                    + " "
                                                                    + c.storageType().name())
                                            .toList())
                            + ")");
            String backup = null;
            boolean verified = false;
            try {
                var failedPages = mock(TusharePageService.class);
                doThrow(new IOException("injected source failure"))
                        .when(failedPages)
                        .execute(any(), anyMap(), any(), any(), any());
                var failing =
                        new StockDetailInfoJobService(
                                failedPages,
                                new QuestDbStockDetailTarget(jdbc, target),
                                ledgerPath);
                var plan = failing.plan(List.of("000001.SZ"), false, LocalDate.of(2026, 9, 29));
                var first = failing.run(plan);
                assertEquals(SyncRunState.FAILED, first.state());
                assertEquals(0, new StockDetailInfoStorage(jdbc, target).snapshot().rows().size());
                assertNull(
                        new DatasetIntervalLock(ledgerPath)
                                .findOwned(
                                        first.runId(),
                                        DatasetIntervalLock.Scope.allDates("stock_detail_info")));
                var owner =
                        new StockDetailInfoJobService(
                                ctx.getBean(TusharePageService.class),
                                new QuestDbStockDetailTarget(jdbc, target),
                                ledgerPath);
                var replay = owner.resume(plan, first.runId());
                assertEquals(SyncRunState.VERIFIED, replay.state(), replay.errorCode());
                assertEquals(1, replay.insertedRows());
                assertEquals(1, new StockDetailInfoStorage(jdbc, target).snapshot().rows().size());
                assertThrows(IllegalStateException.class, () -> owner.resume(plan, replay.runId()));
                var receipt =
                        JobDefinitionJson.mapper().readTree(Path.of(replay.evidence()).toFile());
                backup =
                        new StockDetailPublicationJournal(ledgerPath)
                                .get(receipt.path("publicationId").asText())
                                .intent()
                                .backup();
                Files.createDirectories(evidence);
                JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValue(
                                evidence.resolve("resume-readback.json").toFile(),
                                Map.of(
                                        "target",
                                        target,
                                        "ledger",
                                        ledgerPath.toString(),
                                        "failed",
                                        first,
                                        "replay",
                                        replay,
                                        "resumeIntent",
                                        ledgerPath
                                                .getParent()
                                                .resolve("sync-evidence")
                                                .resolve(replay.runId())
                                                .resolve("resume-intent.json")
                                                .toString()));
                verified = true;
            } finally {
                if (verified) {
                    jdbc.execute("DROP TABLE " + target);
                    if (backup != null) jdbc.execute("DROP TABLE " + backup);
                }
            }
        }
    }
}
