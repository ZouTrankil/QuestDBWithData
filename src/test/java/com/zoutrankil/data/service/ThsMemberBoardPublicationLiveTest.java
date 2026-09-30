package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE", matches="1")
class ThsMemberBoardPublicationLiveTest {
    static class AbruptStop extends Error {}

    @Test void isolatedBoardPublicationAndStoppedWriterRenameRecovery() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            String id = UUID.randomUUID().toString().replace("-", "");
            Path folder = Path.of("artifacts/java-migration/D006", "publication-" + id);
            Path ledgerPath = Path.of("var", "D006-publication-" + id + ".sqlite");
            var jdbc = context.getBean(JdbcTemplate.class);
            var ledger = new SyncRunLedger(ledgerPath);
            var locks = new DatasetIntervalLock(ledgerPath);
            var source = new ThsMemberSource(context.getBean(TusharePageService.class), folder)
                    .fetchBoard("885800.TI", Instant.now().truncatedTo(ChronoUnit.MICROS), () -> false);
            var cases = new ArrayList<Object>();
            for (boolean interrupt : List.of(false, true)) {
                String targetTable = "java_d006_publish_" + interrupt + "_" + id;
                String run = "ths-member-publish-" + interrupt + "-" + id;
                jdbc.execute("CREATE TABLE \"" + targetTable + "\" AS (SELECT * FROM ths_member WHERE ts_code IN "
                        + "('700001.TI','885800.TI')) TIMESTAMP(update_time) PARTITION BY MONTH WAL "
                        + "DEDUP UPSERT KEYS(ts_code,con_code,update_time)");
                awaitWal(jdbc, targetTable);
                var storage = new ThsMemberBoardStorage(jdbc, targetTable);
                var before = storage.snapshot("885800.TI");
                String targetId = StaticTargetIdentity.identify(jdbc, targetTable,
                        before.identity().id(), before.identity().directory());
                ledger.createRun(new SyncRunLedger.Run(run, null, "test.ths_member_publication", 1,
                        "2026-09-29", targetId, "{}"));
                var lease = locks.acquire(run, DatasetIntervalLock.Scope.allDates("ths_member"));
                assertNotNull(lease);
                var staging = new ThsMemberBoardStaging(jdbc);
                var prepared = staging.prepare(targetTable, "885800.TI", source.rows());
                var stage = staging.write(prepared, folder, () -> false);
                var publisher = new ThsMemberBoardPublication(jdbc, ledgerPath,
                        state -> { if (interrupt && state == State.OLD_MOVED) throw new AbruptStop(); });
                ThsMemberBoardPublication.Result result;
                if (interrupt) {
                    assertThrows(AbruptStop.class, () -> publisher.publish(lease, prepared, stage, () -> false));
                    var recovery = new ThsMemberBoardPublication(jdbc, ledgerPath);
                    assertEquals(ThsMemberBoardPublication.Layout.OLD_MOVED, recovery.inspect(run));
                    assertThrows(IllegalStateException.class, () -> recovery.finish(lease, false));
                    result = recovery.finish(lease, true);
                } else result = publisher.publish(lease, prepared, stage, () -> false);
                assertEquals(State.VERIFIED, result.publication().state());
                assertEquals(stage.snapshot().contentFingerprint(), result.actual().contentFingerprint());
                assertEquals(before.otherFingerprint(), result.actual().otherFingerprint());
                assertEquals(source.rows().size(), result.actual().boardRows().size());
                assertEquals(before.contentFingerprint(), new ThsMemberBoardStorage(jdbc,
                        result.publication().intent().backup()).snapshot("885800.TI").contentFingerprint());
                assertFalse(staging.requiresWrite(staging.prepare(targetTable, "885800.TI", source.rows())));
                cases.add(Map.of("interrupted", interrupt, "run", run, "sourceRows", source.rows().size(),
                        "copiedOtherRows", result.actual().otherRows(), "publishedFingerprint",
                        result.actual().contentFingerprint(), "sameSourceRerunRequiresWrite", false));
                var actualLease = locks.findOwned(run, lease.scope());
                if (actualLease.inDoubt()) locks.releaseAfterReconciliation(actualLease, true, true);
                else locks.releaseVerified(actualLease);
                jdbc.execute("DROP TABLE \"" + targetTable + "\"");
                jdbc.execute("DROP TABLE \"" + result.publication().intent().backup() + "\"");
            }
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("publication-review.json").toFile(),
                    Map.of("sourceReceipt", source.responseEvidence(), "sourceRows", source.rows().size(),
                            "cases", cases, "formalTableWrites", 0));
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
