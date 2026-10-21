package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.derived.storage.EtfMarketOverviewDailyViewReadRepository;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.fund.EtfDailyDataset;
import com.zoutrankil.data.domain.fund.EtfOverviewScope;
import com.zoutrankil.data.domain.fund.EtfShareDataset;
import com.zoutrankil.data.maintenance.application.EtfOverviewMaintenance;
import com.zoutrankil.data.maintenance.storage.EtfOverviewRefreshJournal;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 固定私有实例，人工来源与独立手算值；失败保留原表、意图和证据。 */
@EnabledIfEnvironmentVariable(named = "ETFO_LAYOUT_LIVE_ACCEPTANCE", matches = "true")
class EtfOverviewLayoutLiveAcceptanceTest {
    @Test
    void nativeJoinRevisionUnknownResponseAndTypedPaginationUseFrozenLayouts() throws Exception {
        Path marker = Path.of(System.getenv("ETFO_LAYOUT_FIXTURE")).toRealPath();
        byte[] original = Files.readAllBytes(marker);
        assertEquals(
                System.getenv("ETFO_LAYOUT_FIXTURE_SHA256"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
        var fixture = JobDefinitionJson.mapper().readTree(original);
        var scope =
                new EtfOverviewScope(
                        fixture.path("share").asText(),
                        fixture.path("shareVersion").asInt(2),
                        fixture.path("daily").asText(),
                        fixture.path("dailyVersion").asInt(2),
                        fixture.path("output").asText());
        Path root = Path.of(fixture.path("root").asText()).toRealPath();
        Path evidence = marker.getParent();
        Path ledger = evidence.resolve("ledger.sqlite");
        assertFalse(Files.exists(evidence.resolve("report.json")), "不能覆盖原失败验收");
        var attestor =
                new PrivateQuestDbInstanceAttestor(
                        new PrivateQuestDbInstanceAttestor.Spec(
                                "ETF_OVERVIEW_LAYOUT2",
                                Path.of(fixture.path("repository").asText()).resolve("var"),
                                root,
                                18862,
                                19050),
                        checked -> {
                            if (!checked.equals(root)
                                    || !Arrays.equals(original, Files.readAllBytes(marker)))
                                throw new IOException("私有 fixture 或根目录变化");
                        });
        var report = new LinkedHashMap<String, Object>();
        report.put("state", "PREPARED");
        report.put("scope", scope);
        report.put("sourceCoverageVerified", false);
        report.put("physicalMigrationExecuted", false);
        save(evidence, report);
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:18862/qdb", "admin", "quest");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(20);
        try {
            attestor.attest();
            assertEquals(0L, jdbc.queryForObject("SELECT count() FROM tables()", Long.class));
            jdbc.execute(
                    sourceDdl(
                            EtfShareDataset.definition(
                                    scope.shareTable(),
                                    CnTradeDateLayout.fromVersion(scope.shareVersion()))));
            jdbc.execute(
                    sourceDdl(
                            EtfDailyDataset.definition(
                                    scope.dailyTable(),
                                    CnTradeDateLayout.fromVersion(scope.dailyVersion()))));
            jdbc.execute(
                    "INSERT INTO "
                            + scope.shareTable()
                            + " (ts_code,"
                            + scope.shareDate()
                            + ",fd_share,update_time) VALUES ('510300.SH','2026-10-12',50.0,'2026-10-20'),('510500.SH','2026-10-12',50.0,'2026-10-20')");
            jdbc.execute(
                    "INSERT INTO "
                            + scope.dailyTable()
                            + " (ts_code,"
                            + scope.dailyDate()
                            + ",close) VALUES ('510300.SH','2026-10-12',10.0),('510500.SH','2026-10-12',10.0)");
            awaitSources(jdbc, scope);
            var maintenance =
                    new EtfOverviewMaintenance(jdbc, ledger, Duration.ofMinutes(2), scope);
            var first = maintenance.refresh();
            report.put("first", first);
            assertEquals(1L, first.get("verifiedRows"));
            assertRows(jdbc, scope, 0.1, false);
            report.put("replay", maintenance.refresh());
            jdbc.execute(
                    "INSERT INTO "
                            + scope.shareTable()
                            + " (ts_code,"
                            + scope.shareDate()
                            + ",fd_share,update_time) VALUES ('510300.SH','2026-10-13',20.0,'2026-10-20')");
            awaitSources(jdbc, scope);
            report.put("leftAppend", maintenance.refresh());
            assertRows(jdbc, scope, 0.1, false);
            jdbc.execute(
                    "INSERT INTO "
                            + scope.dailyTable()
                            + " (ts_code,"
                            + scope.dailyDate()
                            + ",close) VALUES ('510300.SH','2026-10-13',12.0),('510300.SH','2026-10-12',11.0),('510500.SH','2026-10-12',11.0)");
            awaitSources(jdbc, scope);
            report.put("rightRevision", maintenance.refresh());
            assertRows(jdbc, scope, 0.11, true);
            jdbc.execute(
                    "INSERT INTO "
                            + scope.dailyTable()
                            + " (ts_code,"
                            + scope.dailyDate()
                            + ",close) VALUES ('510300.SH','2026-10-12',12.0),('510500.SH','2026-10-12',12.0)");
            awaitSources(jdbc, scope);
            var uncertain =
                    new JdbcTemplate(dataSource) {
                        @Override
                        public void execute(String sql) {
                            super.execute(sql);
                            if (sql.startsWith("REFRESH MATERIALIZED VIEW"))
                                throw new IllegalStateException("实际提交后注入确认丢失");
                        }
                    };
            var interrupted =
                    new EtfOverviewMaintenance(uncertain, ledger, Duration.ofMinutes(2), scope);
            assertThrows(IllegalStateException.class, interrupted::refresh);
            var journal = new EtfOverviewRefreshJournal(ledger);
            var op =
                    journal.pending(
                                    scope.outputTable()
                                            + "@"
                                            + jdbc.queryForObject(
                                                    "SELECT current_data_id()", String.class))
                            .orElseThrow();
            Files.writeString(
                    evidence.resolve("original-operation.json"),
                    JobDefinitionJson.mapper().writeValueAsString(op));
            assertThrows(IllegalStateException.class, maintenance::refresh);
            assertThrows(
                    IllegalArgumentException.class, () -> maintenance.reconcile(op.id(), false));
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            while (true) {
                try {
                    assertRows(jdbc, scope, 0.12, true);
                    break;
                } catch (AssertionError lag) {
                    if (System.nanoTime() > deadline) throw lag;
                    Thread.sleep(50);
                }
            }
            // 原同步 execute 已结束；新默认改为 v1，也只能核验原 v2 意图。
            var recovered =
                    new EtfOverviewMaintenance(
                                    jdbc, ledger, Duration.ofMinutes(2), EtfOverviewScope.LEGACY)
                            .reconcile(op.id(), true);
            report.put("reconcile", recovered);
            assertEquals(2L, recovered.get("verifiedRows"));
            assertEquals("VERIFIED", journal.get(op.id()).state());
            assertEquals(op.definitionJson(), journal.get(op.id()).definitionJson());
            assertEquals(op.definitionSql(), journal.get(op.id()).definitionSql());
            var reader =
                    new EtfMarketOverviewDailyViewReadRepository(
                            new QuestDbBoundedReader(jdbc, ledger.toString()), scope);
            var page =
                    reader.findRange(
                            LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14), 1, null);
            assertEquals(1, page.rows().size());
            assertNotNull(page.nextCursor());
            var tail =
                    reader.findRange(
                            LocalDate.of(2026, 10, 12),
                            LocalDate.of(2026, 10, 14),
                            1,
                            page.nextCursor());
            assertEquals(1, tail.rows().size());
            assertNull(tail.nextCursor());
            report.put("typed", List.of(page, tail));
            report.put(
                    "final",
                    jdbc.queryForList(
                            "SELECT * FROM " + scope.outputTable() + " ORDER BY trade_date"));
            report.put("state", "VERIFIED_BEFORE_CLEANUP");
            save(evidence, report);
            attestor.attest();
            jdbc.execute("DROP MATERIALIZED VIEW " + scope.outputTable());
            jdbc.execute("DROP TABLE " + scope.dailyTable());
            jdbc.execute("DROP TABLE " + scope.shareTable());
            assertEquals(0L, jdbc.queryForObject("SELECT count() FROM tables()", Long.class));
            report.put("state", "VERIFIED_AND_CLEANED");
            save(evidence, report);
        } catch (Throwable failure) {
            report.put("state", "INCOMPLETE_PRESERVED");
            report.put("failure", failure.getClass().getSimpleName() + ":" + failure.getMessage());
            save(evidence, report);
            throw failure;
        }
    }

    /** 仅在已核验的本机空库建立完整模型夹具；不复用正式部署入口。 */
    private static String sourceDdl(DatasetDefinition d) {
        return "CREATE TABLE "
                + d.objectName()
                + " ("
                + String.join(
                        ",",
                        d.columns().stream()
                                .map(c -> "\"" + c.storageName() + "\" " + c.storageType())
                                .toList())
                + ") TIMESTAMP("
                + d.designatedTimestamp()
                + ") PARTITION BY "
                + d.partition()
                + " WAL DEDUP UPSERT KEYS("
                + String.join(",", d.dedupKey())
                + ")";
    }

    private static void awaitSources(JdbcTemplate jdbc, EtfOverviewScope scope) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try {
                EtfMarketOverviewMaterializedViewReadGuard.snapshot(
                        jdbc, scope.shareTable(), scope);
                EtfMarketOverviewMaterializedViewReadGuard.snapshot(
                        jdbc, scope.dailyTable(), scope);
                return;
            } catch (IllegalStateException lag) {
                if (System.nanoTime() > deadline) throw lag;
                Thread.sleep(25);
            }
        }
    }

    private static void assertRows(
            JdbcTemplate jdbc, EtfOverviewScope scope, double first, boolean second) {
        var rows =
                jdbc.queryForList("SELECT * FROM " + scope.outputTable() + " ORDER BY trade_date");
        assertEquals(second ? 2 : 1, rows.size());
        assertEquals(2L, ((Number) rows.get(0).get("etf_count")).longValue());
        assertEquals(100.0, ((Number) rows.get(0).get("total_share")).doubleValue());
        assertEquals(first, ((Number) rows.get(0).get("total_size_yi")).doubleValue(), 1e-12);
        if (second) {
            assertEquals(1L, ((Number) rows.get(1).get("etf_count")).longValue());
            assertEquals(20.0, ((Number) rows.get(1).get("total_share")).doubleValue());
            assertEquals(0.024, ((Number) rows.get(1).get("total_size_yi")).doubleValue(), 1e-12);
        }
    }

    private static void save(Path root, Map<String, Object> value) throws Exception {
        Files.writeString(
                root.resolve("report.json"), JobDefinitionJson.mapper().writeValueAsString(value));
    }
}
