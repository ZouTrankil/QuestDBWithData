package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.derived.storage.BacktestDailyViewReadRepository;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.data.domain.derived.BacktestDailyViewValue;
import com.zoutrankil.data.maintenance.application.BacktestMvMaintenance;
import com.zoutrankil.data.maintenance.storage.BacktestRefreshJournal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实际 SQLite 意图、真实 FULL 与丢确认恢复；仅已核验的私有空库。 */
@EnabledIfEnvironmentVariable(named = "BACKTEST_MAINTENANCE_LIVE_ACCEPTANCE", matches = "true")
class BacktestMvMaintenanceLiveAcceptanceTest {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 14),
            TO = LocalDate.of(2026, 10, 16);
    private static final String A = "000001.SZ", B = "000002.SZ";

    @Test
    void fullIntentLostAcknowledgementFrozenRecoveryAndGenerationChecksUseActualQuestDb()
            throws Exception {
        Path marker = Path.of(System.getenv("BACKTEST_MAINTENANCE_FIXTURE")).toRealPath();
        byte[] original = Files.readAllBytes(marker);
        assertEquals(System.getenv("BACKTEST_MAINTENANCE_FIXTURE_SHA256"), sha(original));
        var fixture = JobDefinitionJson.mapper().readTree(original);
        Path repository = Path.of("").toRealPath(), folder = marker.getParent();
        assertEquals(repository, Path.of(fixture.path("repository").asText()).toRealPath());
        assertEquals(
                repository.resolve("var/cn-backtest-maintenance-live").toRealPath(),
                folder.getParent());
        Path root = Path.of(fixture.path("root").asText()).toRealPath();
        assertEquals(folder.resolve("database").toRealPath(), root);
        assertFalse(Files.exists(folder.resolve("report.json")));
        var scope =
                new BacktestDailyScope(
                        fixture.path("suspend").asText(),
                        fixture.path("suspendVersion").asInt(),
                        fixture.path("st").asText(),
                        fixture.path("stVersion").asInt(),
                        fixture.path("output").asText());
        boolean preserve = fixture.path("preserveSourceChange").asBoolean(false);
        var attestor =
                new PrivateQuestDbInstanceAttestor(
                        new PrivateQuestDbInstanceAttestor.Spec(
                                "BACKTEST_FULL_RECOVERY",
                                repository.resolve("var"),
                                root,
                                18862,
                                19050),
                        checked -> {
                            if (!checked.equals(root)
                                    || !Arrays.equals(original, Files.readAllBytes(marker)))
                                throw new IOException("原 fixture 或根目录变化");
                        });
        var source =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:18862/qdb", "admin", "quest");
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(20);
        var journalFile = folder.resolve("maintenance.sqlite");
        var journal = new BacktestRefreshJournal(journalFile);
        var submitted = new ArrayList<String>();
        var writer =
                new JdbcTemplate(source) {
                    @Override
                    public void execute(String sql) {
                        attestor.attest();
                        var pending = journal.pending(scope.outputTable()).orElseThrow();
                        assertEquals(sql, pending.request().refreshSql());
                        submitted.add(sql);
                        super.execute(sql);
                    }
                };
        var lossy =
                new JdbcTemplate(source) {
                    @Override
                    public void execute(String sql) {
                        writer.execute(sql);
                        throw new IllegalStateException("真实 FULL 提交后注入确认丢失");
                    }
                };
        var readOnly =
                new JdbcTemplate(source) {
                    @Override
                    public void execute(String sql) {
                        throw new AssertionError("只读恢复不能发送 SQL 变更：" + sql);
                    }
                };
        var report = new LinkedHashMap<String, Object>();
        report.put("state", "PREPARED");
        report.put("scope", scope);
        report.put("sourceCoverageVerified", false);
        report.put("formalMigrationExecuted", false);
        report.put("submitted", submitted);
        save(folder, report);
        try {
            attestor.attest();
            assertEquals(0L, jdbc.queryForObject("SELECT count() FROM tables()", Long.class));
            var inputs = new BacktestDailyLayoutLiveAcceptanceTest.Sources(jdbc, scope, attestor);
            for (var d : inputs.definitions)
                jdbc.execute(BacktestDailyLayoutLiveAcceptanceTest.sourceDdl(d));
            inputs.price("2026-10-14", A, 10.0, 100.0, 100.0, 1.0);
            inputs.price("2026-10-14", B, 20.0, 200.0, 400.0, 2.0);
            inputs.price("2026-10-15", A, 11.0, 100.0, 110.0, 1.0);
            inputs.flag(scope.suspendTable(), scope.suspendDate(), "is_suspended", "2026-10-15", B);
            inputs.flag(scope.stTable(), scope.stDate(), "is_st", "2026-10-15", B);
            inputs.settled();
            inputs.limit("2026-10-14", A, 11.0, 9.0);
            inputs.limit("2026-10-14", B, 22.0, 18.0);
            inputs.limit("2026-10-15", A, 12.1, 9.9);
            inputs.limit("2026-10-15", B, 22.0, 18.0);
            inputs.settled();
            attestor.attest();
            jdbc.execute(BacktestDailyMaterializedViewPort.createSql(scope));
            var port = new BacktestDailyMaterializedViewPort(jdbc, scope);
            port.awaitCurrent(Duration.ofSeconds(30));
            var normal =
                    new BacktestMvMaintenance(writer, journalFile, Duration.ofSeconds(30), scope);
            assertEquals("CURRENT", normal.plan().get("status"));
            assertFalse(Files.exists(journalFile));
            assertThrows(IllegalArgumentException.class, () -> normal.refresh(false));
            assertEquals(0, submitted.size());

            // 仅关联价格源更新，未重写基表；维护计划必须能接收这个原本被读保护拒绝的状态。
            inputs.price("2026-10-14", B, 21.0, 200.0, 420.0, 2.0);
            inputs.settled();
            assertThrows(
                    BacktestDailyMaterializationReadGuard.NotReadyException.class,
                    port::sourceState);
            var plan = normal.plan();
            assertEquals("FULL_REQUIRED", plan.get("status"));
            assertFalse(Files.exists(journalFile));
            report.put("plan", plan);
            var first = normal.refresh(true);
            assertEquals("VERIFIED", first.get("status"));
            assertEquals(1, submitted.size());
            report.put("first", first);
            var reader =
                    new BacktestDailyViewReadRepository(
                            new QuestDbBoundedReader(
                                    jdbc, folder.resolve("never-reader.sqlite").toString()),
                            scope);
            assertRows(reader, 21.0, 420.0);
            inputs.verify();
            save(folder, report);

            inputs.price("2026-10-14", B, 21.5, 200.0, 430.0, 2.0);
            inputs.settled();
            var uncertain =
                    new BacktestMvMaintenance(lossy, journalFile, Duration.ofSeconds(30), scope);
            assertThrows(IllegalStateException.class, () -> uncertain.refresh(true));
            var originalOperation = journal.pending(scope.outputTable()).orElseThrow();
            Files.writeString(
                    folder.resolve("original-operation.json"),
                    BacktestRefreshJournal.json(originalOperation));
            assertThrows(IllegalStateException.class, () -> normal.refresh(true));
            assertEquals(2, submitted.size());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> normal.reconcile(originalOperation.id(), false));
            // 当前配置故意不同，恢复仍只读原意图，并保持原 JSON 与 checksum。
            var recovery =
                    new BacktestMvMaintenance(
                            readOnly,
                            journalFile,
                            Duration.ofSeconds(30),
                            BacktestDailyScope.LEGACY);
            var recovered = recovery.reconcile(originalOperation.id(), true);
            assertEquals("VERIFIED", recovered.get("status"));
            assertEquals(2, submitted.size());
            assertEquals(
                    originalOperation.requestJson(),
                    journal.get(originalOperation.id()).requestJson());
            assertEquals(
                    originalOperation.requestSha256(),
                    journal.get(originalOperation.id()).requestSha256());
            assertEquals("VERIFIED", journal.get(originalOperation.id()).state());
            assertRows(reader, 21.5, 430.0);
            inputs.verify();
            assertEquals(recovered, recovery.reconcile(originalOperation.id(), true));
            report.put("recovered", recovered);
            report.put("originalUnchanged", true);
            save(folder, report);

            if (preserve) {
                inputs.price("2026-10-14", B, 21.75, 200.0, 435.0, 2.0);
                inputs.settled();
                assertThrows(IllegalStateException.class, () -> uncertain.refresh(true));
                var pending = journal.pending(scope.outputTable()).orElseThrow();
                awaitRows(reader, 21.75, 435.0); // 只观察这一次真实 FULL，不补发。
                report.put("knownFullBeforeSourceChange", port.sourceState());
                inputs.price("2026-10-14", B, 21.9, 200.0, 438.0, 2.0);
                inputs.settled();
                assertThrows(
                        IllegalStateException.class, () -> recovery.reconcile(pending.id(), true));
                assertThrows(IllegalStateException.class, () -> normal.refresh(true));
                assertEquals("IN_DOUBT", journal.get(pending.id()).state());
                assertEquals(pending.requestJson(), journal.get(pending.id()).requestJson());
                assertEquals(3, submitted.size());
                inputs.verify();
                report.put("pendingPreserved", journal.get(pending.id()));
                report.put("observedAfterSourceChange", port.refreshAdmissionState());
                report.put("state", "VERIFIED_REJECTION_PRESERVED");
                report.put("objectsRemoved", false);
                save(folder, report);
                return; // 未决对象与账本原件一起保留；不得为了通过测试删除它们。
            }

            // 只在两个 FULL 都已完整验证后换代输出；旧完成记录不能套到同名新 MV。
            var oldResult = journal.get(originalOperation.id()).resultJson();
            attestor.attest();
            jdbc.execute("DROP MATERIALIZED VIEW " + scope.outputTable());
            jdbc.execute(BacktestDailyMaterializedViewPort.createSql(scope));
            port.awaitCurrent(Duration.ofSeconds(30));
            assertThrows(
                    IllegalStateException.class,
                    () -> recovery.reconcile(originalOperation.id(), true));
            assertEquals(oldResult, journal.get(originalOperation.id()).resultJson());
            assertEquals(0, journal.pendingOperations().size());
            assertRows(reader, 21.5, 430.0);
            inputs.verify();
            report.put("generationReplacementRefused", true);
            report.put("final", reader.findRange(FROM, TO, 10, null));
            assertFalse(Files.exists(folder.resolve("never-reader.sqlite")));
            report.put("state", "VERIFIED_BEFORE_CLEANUP");
            save(folder, report);
            attestor.attest();
            jdbc.execute("DROP MATERIALIZED VIEW " + scope.outputTable());
            for (var d : inputs.definitions) jdbc.execute("DROP TABLE " + d.objectName());
            assertEquals(0L, jdbc.queryForObject("SELECT count() FROM tables()", Long.class));
            report.put("objectsRemoved", true);
            report.put("state", "VERIFIED_AND_CLEANED");
            save(folder, report);
        } catch (Throwable failure) {
            report.put("state", "INCOMPLETE_PRESERVED");
            report.put("failure", failure.getClass().getSimpleName() + ":" + failure.getMessage());
            save(folder, report);
            throw failure;
        }
    }

    private static void awaitRows(
            BacktestDailyViewReadRepository reader, double price, double amount) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try {
                assertRows(reader, price, amount);
                return;
            } catch (BacktestDailyMaterializationReadGuard.NotReadyException waiting) {
                if (System.nanoTime() >= deadline) throw waiting;
                Thread.sleep(25);
            }
        }
    }

    private static void assertRows(
            BacktestDailyViewReadRepository reader, double price, double amount) {
        var first = reader.findRange(FROM, TO, 2, null);
        assertNotNull(first.nextCursor());
        var tail = reader.findRange(FROM, TO, 2, first.nextCursor());
        assertFalse(tail.hasMore());
        var rows = new ArrayList<>(first.rows());
        rows.addAll(tail.rows());
        assertEquals(
                List.of(
                        row("2026-10-14", A, 10, 100, 100, 1, 11, 9, 0, 0),
                        row("2026-10-14", B, price, 200, amount, 2, 22, 18, 0, 0),
                        row("2026-10-15", A, 11, 100, 110, 1, 12.1, 9.9, 0, 0),
                        row("2026-10-15", B, price, 0, 0, 2, 22, 18, 1, 1)),
                rows);
    }

    private static BacktestDailyViewValue row(
            String date,
            String code,
            double price,
            double volume,
            double amount,
            double factor,
            double upper,
            double lower,
            long suspended,
            int st) {
        return new BacktestDailyViewValue(
                LocalDate.parse(date),
                code,
                price,
                price,
                price,
                price,
                volume,
                amount,
                factor,
                upper,
                lower,
                suspended,
                st);
    }

    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static void save(Path folder, Map<String, Object> report) throws Exception {
        Files.writeString(
                folder.resolve("report.json"),
                JobDefinitionJson.mapper().writeValueAsString(report));
    }
}
