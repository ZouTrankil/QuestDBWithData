package com.zoutrankil.data.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.index.application.ThsIndexJobService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

class ThsIndexPlanningStartupTest {
    @TempDir Path temp;

    @Test
    void completeDirectoryPlanIsFrozenWithoutSourceRequestLedgerOrWrite() throws Exception {
        Path ledger = temp.resolve("not-created.sqlite");
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(
                c ->
                        c.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new MapPropertySource(
                                                "ths-plan-test",
                                                Map.of(
                                                        "app.sync.ledger-path",
                                                        ledger.toString()))));
        try (var context = application.run("plan-ths-index-job", "--logical-date", "2026-09-29")) {
            assertFalse(Files.exists(ledger));
            var owner = context.getBean(ThsIndexJobService.class);
            var plan = owner.plan(LocalDate.of(2026, 9, 29));
            assertEquals(SyncJobDefinition.Mode.INCREMENTAL, plan.mode());
            assertTrue(plan.parameters().isEmpty());
            var frozen =
                    JobDefinitionJson.mapper().readTree(SyncRequestIdentity.snapshotJson(plan));
            assertEquals("data.ths_index", frozen.path("definition").path("jobId").asText());
            assertEquals("INCREMENTAL", frozen.path("mode").asText());
            var cli = context.getBean(CommandLineRunner.class);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            cli.run(
                                    new DefaultApplicationArguments(
                                            "finish-ths-index-publication",
                                            "--run",
                                            "absent",
                                            "--writer-stopped",
                                            "false")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            cli.run(
                                    new DefaultApplicationArguments(
                                            "plan-ths-index-job",
                                            "--logical-date",
                                            "2026-09-29",
                                            "--resume-from",
                                            "old")));
            assertFalse(Files.exists(ledger));
        }
    }
}
