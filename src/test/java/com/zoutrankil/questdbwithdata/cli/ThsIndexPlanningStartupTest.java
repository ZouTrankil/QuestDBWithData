package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ThsIndexPlanningStartupTest {
    @TempDir Path temp;
    @Test void completeDirectoryPlanIsFrozenWithoutSourceRequestLedgerOrWrite() throws Exception {
        Path ledger=temp.resolve("not-created.sqlite");
        var application=new SpringApplication(QuestDbWithDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("ths-plan-test",Map.of("app.sync.ledger-path",ledger.toString()))));
        try(var context=application.run("plan-ths-index-job","--logical-date","2026-09-29")) {
            assertFalse(Files.exists(ledger));
            var owner=context.getBean(ThsIndexJobService.class);
            var plan=owner.plan(LocalDate.of(2026,9,29));
            assertEquals(SyncJobDefinition.Mode.INCREMENTAL,plan.mode());assertTrue(plan.parameters().isEmpty());
            var frozen=JobDefinitionJson.mapper().readTree(SyncRequestIdentity.snapshotJson(plan));
            assertEquals("data.ths_index",frozen.path("definition").path("jobId").asText());
            assertEquals("INCREMENTAL",frozen.path("mode").asText());
            var cli=context.getBean(CommandLineRunner.class);
            assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments(
                    "finish-ths-index-publication","--run","absent","--writer-stopped","false")));
            assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments(
                    "plan-ths-index-job","--logical-date","2026-09-29","--resume-from","old")));
            assertFalse(Files.exists(ledger));
        }
    }
}
