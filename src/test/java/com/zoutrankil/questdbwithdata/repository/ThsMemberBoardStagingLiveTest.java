package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.service.ThsMemberSource;
import com.zoutrankil.questdbwithdata.service.TusharePageService;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
class ThsMemberBoardStagingLiveTest {
    @Test void realBoardReplacementPreservesEveryOtherIsolatedRow() throws Exception {
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String target = "java_ths_member_test_" + UUID.randomUUID().toString().replace("-", "");
            String stage = null;
            Path folder = Path.of("artifacts/java-migration/D006", "stage-" + UUID.randomUUID());
            boolean verified = false;
            try {
                jdbc.execute("CREATE TABLE " + target + " AS (SELECT * FROM ths_member WHERE ts_code IN "
                        + "('700001.TI','885800.TI')) TIMESTAMP(update_time) PARTITION BY MONTH WAL "
                        + "DEDUP UPSERT KEYS(ts_code,con_code,update_time)");
                awaitWal(jdbc, target);
                var source = new ThsMemberSource(context.getBean(TusharePageService.class), folder)
                        .fetchBoard("885800.TI", Instant.now().truncatedTo(ChronoUnit.MICROS), () -> false);
                var staging = new ThsMemberBoardStaging(jdbc);
                var prepared = staging.prepare(target, "885800.TI", source.rows());
                assertTrue(staging.requiresWrite(prepared));
                var result = staging.write(prepared, folder, () -> false);
                stage = result.stage();
                assertEquals(prepared.before().otherRows(), result.snapshot().otherRows());
                assertEquals(source.rows().size(), result.snapshot().boardRows().size());
                assertNotEquals(prepared.before().contentFingerprint(), result.snapshot().contentFingerprint());
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("stage-review.json").toFile(),
                        Map.of("target", target, "stage", stage, "sourceRows", source.rows().size(),
                                "copiedOtherRows", result.snapshot().otherRows(),
                                "beforeFingerprint", prepared.before().contentFingerprint(),
                                "afterFingerprint", result.snapshot().contentFingerprint(),
                                "stageReceipt", result.receipt(), "questdbFormalWrites", 0));
                verified = true;
            } finally {
                if (verified) {
                    if (stage != null) jdbc.execute("DROP TABLE \"" + stage + "\"");
                    jdbc.execute("DROP TABLE \"" + target + "\"");
                }
            }
        }
    }

    private static void awaitWal(JdbcTemplate jdbc, String table) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Test WAL unresolved; retain isolated target");
            Thread.sleep(50);
        }
    }
}
