package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbThsMemberTarget;

import com.zoutrankil.data.index.storage.ThsMemberBoardStorage;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.sql.DriverManager;
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
class ThsMemberRunRecoveryLiveTest {
    @Test void ledgerFailureAfterPhysicalPublicationRecoversFromFrozenReceipt() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String table = "java_d006_recovery_" + nonce;
            Path folder = Path.of("artifacts/java-migration/D006", "run-recovery-" + nonce);
            Files.createDirectories(folder);
            Path path = folder.resolve("ledger.sqlite");
            var ledger = new SyncRunLedger(path);
            jdbc.execute("CREATE TABLE \"" + table + "\" AS (SELECT * FROM ths_member WHERE ts_code IN "
                    + "('700001.TI','885800.TI')) TIMESTAMP(update_time) PARTITION BY MONTH WAL "
                    + "DEDUP UPSERT KEYS(ts_code,con_code,update_time)");
            awaitWal(jdbc, table);
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path);
                 var statement = db.createStatement()) {
                statement.execute("CREATE TRIGGER reject_ths_member_completion BEFORE UPDATE ON sync_entries "
                        + "WHEN OLD.kind='SLICE' AND NEW.state='VERIFIED' "
                        + "BEGIN SELECT RAISE(ABORT,'injected completion failure'); END");
            }
            var owner = new ThsMemberJobService(new QuestDbThsMemberTarget(table,jdbc),context.getBean(TusharePageService.class),path);
            var request = owner.plan("885800.TI", LocalDate.of(2026, 9, 29));
            var failed = owner.run(request);
            assertEquals(SyncRunState.IN_DOUBT, failed.state(), failed.toString());
            assertNotNull(failed.publicationId());
            var before = new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI");
            assertEquals(failed.sourceRows(), before.boardRows().size());
            for (String id : List.of(failed.runId() + "-board", failed.runId() + "-attempt", failed.runId()))
                assertEquals(SyncRunState.IN_DOUBT, ledger.get(id).state());
            assertThrows(IllegalStateException.class, () -> owner.finishInterrupted(failed.runId(), false));
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path);
                 var statement = db.createStatement()) {
                statement.execute("DROP TRIGGER reject_ths_member_completion");
            }
            var recovered = owner.finishInterrupted(failed.runId(), true);
            assertEquals(SyncRunState.VERIFIED, recovered.state());
            assertEquals(failed.sourceRows(), recovered.verifiedBoardRows());
            assertEquals(before.contentFingerprint(),
                    new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI").contentFingerprint());
            assertNull(new DatasetIntervalLock(path).findOwned(failed.runId(),
                    DatasetIntervalLock.Scope.allDates("ths_member")));
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("recovery-review.json").toFile(),
                    Map.of("failed", failed, "recovered", recovered, "sourceRequestsDuringRecovery", 0,
                            "rowInsertsDuringRecovery", 0, "formalTableWrites", 0));
            String backup = new ReferencePublicationJournal(path, "ths_member")
                    .forRun(failed.runId()).intent().backup();
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
