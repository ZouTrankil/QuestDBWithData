package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE", matches="1")
class ThsMemberJobLiveTest {
    @Test void registeredBoardJobWritesRealSourceAndThenRerunsWithoutMutation() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            String id = UUID.randomUUID().toString().replace("-", "");
            String table = "java_d006_job_" + id;
            Path ledgerPath = Path.of("var", "D006-job-" + id + ".sqlite");
            var jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE \"" + table + "\" AS (SELECT * FROM ths_member WHERE ts_code IN "
                    + "('700001.TI','885800.TI')) TIMESTAMP(update_time) PARTITION BY MONTH WAL "
                    + "DEDUP UPSERT KEYS(ts_code,con_code,update_time)");
            awaitWal(jdbc, table);
            var service = new ThsMemberJobService(jdbc, context.getBean(TusharePageService.class), ledgerPath, table);
            var request = service.plan("885800.TI", LocalDate.of(2026, 9, 29));
            var registry = context.getBean(SyncJobRegistry.class);
            assertEquals("ths_member", registry.require("data.ths_member", 1).datasetId());
            var first = service.run(request);
            assertEquals(SyncRunState.VERIFIED, first.state(), first.toString());
            assertEquals(first.sourceRows(), first.verifiedBoardRows());
            assertTrue(first.sourceRows() > 0 && first.copiedOtherRows() == 5565);
            assertNotNull(first.publicationId());
            var firstSnapshot = new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI");
            var second = service.run(request);
            assertEquals(SyncRunState.VERIFIED, second.state(), second.toString());
            assertNull(second.publicationId());
            assertEquals(firstSnapshot.contentFingerprint(),
                    new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI").contentFingerprint());
            var ledger = SyncRunLedger.openReadOnly(ledgerPath);
            assertEquals(SyncRunState.VERIFIED, ledger.get(first.runId()).state());
            assertEquals(SyncRunState.VERIFIED, ledger.get(first.runId() + "-attempt").state());
            assertEquals(SyncRunState.VERIFIED, ledger.get(first.runId() + "-board").state());
            var backup = new ReferencePublicationJournal(ledgerPath, "ths_member")
                    .forRun(first.runId()).intent().backup();
            Path review = Path.of("artifacts/java-migration/D006/job-" + id + ".json");
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(review.toFile(),
                    Map.of("first", first, "second", second, "target", table,
                            "rowFingerprint", firstSnapshot.contentFingerprint(), "formalTableWrites", 0));
            jdbc.execute("DROP TABLE \"" + table + "\"");
            jdbc.execute("DROP TABLE \"" + backup + "\"");
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
