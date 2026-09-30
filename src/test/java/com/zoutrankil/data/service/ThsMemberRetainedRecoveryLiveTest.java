package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
class ThsMemberRetainedRecoveryLiveTest {
    @Test void finishTwoRetainedFailedTestRunsWithoutAnotherSourceRequest() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var receipts = new ArrayList<Object>();
            for (var caseId : List.of(
                    "8e2e254a45a34f24badd182a84d8e278|ths-member-d296ac0d-7b28-4d33-931a-355ab071f7c7",
                    "458f7163afc24793baa076286859de52|ths-member-bf6f99d8-6fc4-4ee6-b120-0c3f5dbc5417")) {
                var parts = caseId.split("\\|");
                Path folder = Path.of("artifacts/java-migration/D006", "run-recovery-" + parts[0]);
                Path ledgerPath = folder.resolve("ledger.sqlite");
                String run = parts[1];
                var frozen = JobDefinitionJson.mapper().readTree(folder.resolve("sync-evidence")
                        .resolve(run).resolve("prepared.json").toFile());
                String table = frozen.path("prepared").path("target").asText();
                assertTrue(table.startsWith("java_d006_recovery_"));
                var service = new ThsMemberJobService(jdbc, context.getBean(TusharePageService.class),
                        ledgerPath, table);
                var result = service.finishInterrupted(run, true);
                assertEquals(SyncRunState.VERIFIED, result.state());
                assertEquals(result.sourceRows(), result.verifiedBoardRows());
                var intent = new ReferencePublicationJournal(ledgerPath, "ths_member").forRun(run).intent();
                receipts.add(Map.of("run", run, "result", result,
                        "fingerprint", new ThsMemberBoardStorage(jdbc, table)
                                .snapshot("885800.TI").contentFingerprint()));
                jdbc.execute("DROP TABLE \"" + table + "\"");
                jdbc.execute("DROP TABLE \"" + intent.backup() + "\"");
            }
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                    Path.of("artifacts/java-migration/D006/retained-recovery-review.json").toFile(),
                    Map.of("recoveredRuns", receipts, "sourceRequests", 0, "formalTableWrites", 0));
        }
    }
}
