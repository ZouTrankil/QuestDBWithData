package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
class ThsMemberRetainedEarlyRecoveryLiveTest {
    @Test void finishRetainedPrejournalFailureWithoutSourceRequest() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String nonce = "7cbb1c7c93a14535aa3c992ce27a2b9e";
            String run = "ths-member-early-" + nonce;
            String table = "java_d006_early_" + nonce;
            Path folder = Path.of("artifacts/java-migration/D006", "early-recovery-" + nonce);
            Path ledgerPath = folder.resolve("ledger.sqlite");
            Path evidence = ledgerPath.toAbsolutePath().getParent().resolve("sync-evidence").resolve(run);
            String orphan;
            try (var paths = Files.list(evidence)) {
                Path intent = paths.filter(path -> path.getFileName().toString().matches(
                        "java_ths_member_stage_[0-9a-f]{32}-intent\\.json")).findFirst().orElseThrow();
                orphan = JobDefinitionJson.mapper().readTree(intent.toFile()).path("stage").asText();
            }
            var owner = new ThsMemberJobService(jdbc, context.getBean(TusharePageService.class), ledgerPath, table);
            var recovered = owner.finishInterrupted(run, true);
            assertEquals(SyncRunState.VERIFIED, recovered.state());
            var intent = new ReferencePublicationJournal(ledgerPath, "ths_member").forRun(run).intent();
            assertNotEquals(orphan, intent.stage());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("retained-recovery-review.json").toFile(),
                    Map.of("recovered", recovered, "retainedStage", orphan,
                            "sourceRequestsDuringRecovery", 0, "formalTableWrites", 0));
            jdbc.execute("DROP TABLE \"" + orphan + "\"");
            jdbc.execute("DROP TABLE \"" + table + "\"");
            jdbc.execute("DROP TABLE \"" + intent.backup() + "\"");
        }
    }
}
