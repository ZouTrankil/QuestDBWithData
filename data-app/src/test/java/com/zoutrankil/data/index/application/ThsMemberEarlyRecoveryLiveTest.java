package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbThsMemberTarget;

import com.zoutrankil.data.index.storage.ThsMemberBoardStaging;
import com.zoutrankil.data.index.storage.ThsMemberBoardStorage;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE", matches="1")
class ThsMemberEarlyRecoveryLiveTest {
    @Test void stoppedWriterRebuildsVerifiedStageBeforeJournalCreation() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String table = "java_d006_early_" + nonce;
            String run = "ths-member-early-" + nonce;
            Path folder = Path.of("artifacts/java-migration/D006", "early-recovery-" + nonce);
            Files.createDirectories(folder);
            Path ledgerPath = folder.resolve("ledger.sqlite");
            jdbc.execute("CREATE TABLE \"" + table + "\" AS (SELECT * FROM ths_member WHERE ts_code IN "
                    + "('700001.TI','885800.TI')) TIMESTAMP(update_time) PARTITION BY MONTH WAL "
                    + "DEDUP UPSERT KEYS(ts_code,con_code,update_time)");
            awaitWal(jdbc, table);
            var owner = new ThsMemberJobService(new QuestDbThsMemberTarget(table,jdbc),context.getBean(TusharePageService.class),ledgerPath);
            var request = owner.plan("885800.TI", LocalDate.of(2026, 9, 29));
            var ledger = new SyncRunLedger(ledgerPath);
            var locks = new DatasetIntervalLock(ledgerPath);
            String targetId = owner.targetId();
            ledger.createRun(run, null, targetId, request);
            var lease = locks.acquire(run, DatasetIntervalLock.Scope.allDates("ths_member"));
            assertNotNull(lease);
            String attempt = run + "-attempt", slice = run + "-board";
            move(ledger, run, SyncRunState.RUNNING);
            ledger.createChild(attempt, SyncRunLedger.Kind.ATTEMPT, run, run);
            move(ledger, attempt, SyncRunState.RUNNING);
            ledger.createChild(slice, SyncRunLedger.Kind.SLICE, run, attempt);
            move(ledger, slice, SyncRunState.RUNNING);
            Path evidence = ledgerPath.toAbsolutePath().getParent().resolve("sync-evidence").resolve(run);
            Files.createDirectories(evidence);
            var source = new ThsMemberSource(context.getBean(TusharePageService.class), evidence)
                    .fetchBoard("885800.TI", Instant.now().truncatedTo(ChronoUnit.MICROS), () -> false);
            var staging = new ThsMemberBoardStaging(jdbc);
            var prepared = staging.prepare(table, "885800.TI", source.rows());
            Files.write(evidence.resolve("prepared.json"), JobDefinitionJson.mapper().writeValueAsBytes(Map.of(
                    "runId", run, "targetId", targetId, "request", SyncRequestIdentity.snapshotJson(request),
                    "source", source, "prepared", prepared)), StandardOpenOption.CREATE_NEW);
            var orphan = staging.write(prepared, evidence, () -> false);
            assertEquals(prepared.before(), new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI"));
            assertTrue(new ReferencePublicationJournal(ledgerPath, "ths_member").findForRun(run).isEmpty());
            for (var id : List.of(slice, attempt, run)) move(ledger, id, SyncRunState.IN_DOUBT);
            locks.retainInDoubt(lease);
            assertThrows(IllegalStateException.class, () -> owner.finishInterrupted(run, false));
            var recovered = owner.finishInterrupted(run, true);
            assertEquals(SyncRunState.VERIFIED, recovered.state());
            assertEquals(source.rows().size(), recovered.verifiedBoardRows());
            var intent = new ReferencePublicationJournal(ledgerPath, "ths_member").forRun(run).intent();
            assertNotEquals(orphan.stage(), intent.stage());
            assertEquals(orphan.snapshot().contentFingerprint(),
                    new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI").contentFingerprint());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("early-recovery-review.json").toFile(),
                    Map.of("run", run, "orphanStage", orphan.stage(), "recovered", recovered,
                            "sourceRequestsDuringRecovery", 0, "formalTableWrites", 0));
            jdbc.execute("DROP TABLE \"" + orphan.stage() + "\"");
            jdbc.execute("DROP TABLE \"" + table + "\"");
            jdbc.execute("DROP TABLE \"" + intent.backup() + "\"");
        }
    }

    private static void move(SyncRunLedger ledger, String id, SyncRunState state) throws Exception {
        ledger.transition(id, ledger.get(id).revision(), state, "{}");
    }
    private static void awaitWal(JdbcTemplate jdbc, String table) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Test WAL unresolved; retain isolated target");
            Thread.sleep(50);
        }
    }
}
