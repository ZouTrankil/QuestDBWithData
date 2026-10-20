package com.zoutrankil.data.index.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.WriteGroupRequest;
import com.zoutrankil.data.domain.index.IndexCatalogDataset;
import com.zoutrankil.data.index.mapper.IndexCatalogMapper;
import com.zoutrankil.data.index.storage.IndexCatalogStorage;
import com.zoutrankil.data.index.storage.QuestDbIndexCatalogTarget;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.WriteGroupPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "QUESTDB_WRITE_LIVE", matches = "1")
class IndexCatalogPreparedRecoveryLiveTest {
    @Test
    void completedPublicationWithRejectedLedgerFinishRecoversPreparedInput() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(
                c ->
                        c.addBeanFactoryPostProcessor(
                                f ->
                                        ((BeanDefinitionRegistry) f)
                                                .removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String table = "java_d003_prepared_recovery_" + nonce;
            Path folder = Path.of("artifacts/java-migration/D003", "prepared-recovery-" + nonce);
            Files.createDirectories(folder);
            Path path = folder.resolve("ledger.sqlite");
            jdbc.execute(
                    "CREATE TABLE "
                            + table
                            + " ("
                            + String.join(
                                    ",",
                                    IndexCatalogDataset.DEFINITION.columns().stream()
                                            .map(
                                                    c ->
                                                            c.storageName()
                                                                    + " "
                                                                    + c.storageType().name())
                                            .toList())
                            + ") TIMESTAMP(import_time) PARTITION BY MONTH WAL");
            var owner =
                    new IndexCatalogJobService(new QuestDbIndexCatalogTarget(jdbc, table), path);
            var mapper = new IndexCatalogMapper();
            var rows =
                    new IndexCatalogFileSource()
                            .read(
                                    Path.of("artifacts/java-migration/D003/source-catalog.csv"),
                                    Instant.now().truncatedTo(ChronoUnit.MICROS),
                                    () -> false)
                            .rows()
                            .subList(0, 2);
            var values = rows.stream().map(mapper::values).toList();
            var request =
                    new WriteGroupRequest(
                            "catalog-recovery",
                            LocalDate.of(2026, 9, 29),
                            List.of(
                                    new WriteGroupRequest.Member(
                                            "index-member",
                                            "index",
                                            IndexCatalogDataset.DEFINITION.schemaVersion(),
                                            "catalog-recovery-rows",
                                            values)));
            var plan =
                    WriteGroupPlan.prepare(
                            request,
                            context.getBean(DatasetRegistry.class),
                            Map.of("index", owner.targetId()));
            var adapter =
                    new IndexCatalogPreparedWriteAdapter(
                            plan, "index-member", owner, folder, false);
            Path receipt = folder.resolve("prepared-input.json");
            Files.writeString(
                    receipt,
                    JobDefinitionJson.mapper()
                            .writeValueAsString(
                                    Map.of(
                                            "sourceKind",
                                            "prepared-write-request",
                                            "memberId",
                                            "index-member",
                                            "batchId",
                                            adapter.member().batchId(),
                                            "targetId",
                                            adapter.member().targetId(),
                                            "fingerprint",
                                            adapter.member().batch().fingerprint(),
                                            "rows",
                                            adapter.member().batch().rows())),
                    StandardOpenOption.CREATE_NEW);
            new SyncRunLedger(path);
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path);
                    var s = db.createStatement()) {
                s.execute(
                        "CREATE TRIGGER reject_prepared_completion BEFORE UPDATE ON sync_entries "
                                + "WHEN OLD.kind='SLICE' AND NEW.state='VERIFIED' BEGIN SELECT RAISE(ABORT,'injected completion failure'); END");
            }
            String run = "prepared-recovery-" + nonce;
            var failed =
                    owner.executePrepared(run, null, adapter.request(), rows, receipt.toString());
            assertEquals(SyncRunState.IN_DOUBT, failed.state());
            assertNotNull(failed.publicationId());
            var before = new IndexCatalogStorage(jdbc, table).snapshot();
            assertEquals(2, before.rows().size());
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path);
                    var s = db.createStatement()) {
                s.execute("DROP TRIGGER reject_prepared_completion");
            }
            Path completion =
                    path.toAbsolutePath()
                            .getParent()
                            .resolve("sync-evidence")
                            .resolve(run)
                            .resolve("completion.json");
            Files.move(completion, completion.resolveSibling("completion-original-test.json"));
            var recovered = owner.finishInterrupted(run, true);
            assertEquals(SyncRunState.VERIFIED, recovered.state());
            assertEquals(before, new IndexCatalogStorage(jdbc, table).snapshot());
            assertNull(
                    new DatasetIntervalLock(path)
                            .findOwned(run, DatasetIntervalLock.Scope.allDates("index")));
            JobDefinitionJson.mapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            folder.resolve("recovery-readback.json").toFile(),
                            Map.of(
                                    "failed",
                                    failed,
                                    "recovered",
                                    recovered,
                                    "actual",
                                    before,
                                    "sourceRequestsDuringRecovery",
                                    0,
                                    "rowInsertsDuringRecovery",
                                    0));
            String backup =
                    new ReferencePublicationJournal(path, "index").forRun(run).intent().backup();
            jdbc.execute("DROP TABLE " + table);
            jdbc.execute("DROP TABLE " + backup);
        }
    }
}
