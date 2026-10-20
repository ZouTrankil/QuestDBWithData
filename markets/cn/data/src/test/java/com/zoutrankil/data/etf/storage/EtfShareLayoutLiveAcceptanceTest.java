package com.zoutrankil.data.etf.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.WriteGroupRequest;
import com.zoutrankil.data.domain.fund.EtfShare;
import com.zoutrankil.data.domain.fund.EtfShareDataset;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendarDataset;
import com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp;
import com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit;
import com.zoutrankil.data.etf.mapper.EtfShareMapper;
import com.zoutrankil.data.etf.port.EtfShareWriteSession;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.WriteGroupJson;
import com.zoutrankil.data.service.WriteGroupPlan;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;
import com.zoutrankil.runtime.ProjectConfiguration;

import io.questdb.client.QuestDB;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只写任务专属 v2 表；未决或失败时保留表与原件，不重发、不自动清理。 */
@EnabledIfEnvironmentVariable(named = "D016_LAYOUT_LIVE_ACCEPTANCE", matches = "true")
class EtfShareLayoutLiveAcceptanceTest {
    @Test
    void explicitV2WriterTypedReadsAndRepeatedVerifiedUpsertPreserveTheFrozenTwoRowSample()
            throws Exception {
        Path evidenceRoot =
                Path.of(
                                System.getenv()
                                        .getOrDefault(
                                                "D016_LAYOUT_EVIDENCE_ROOT",
                                                "var/etf-share-layout-acceptance"))
                        .toAbsolutePath();
        Files.createDirectories(evidenceRoot);
        requireNoUnfinishedRuns(evidenceRoot);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Path evidence = Files.createDirectory(evidenceRoot.resolve(suffix));
        String table = "java_d016_etf_share_layout2_" + suffix;
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1);
        report.put("observedAt", Instant.now().toString());
        report.put("table", table);
        report.put("sourceCoverageVerified", false);
        report.put("modelAcceptanceVerified", false);
        report.put("requestIsProductionApproved", false);
        report.put("physicalMigrationExecuted", false);
        report.put("checkpointAdvanced", false);
        report.put("state", "PREPARED");
        save(evidence, report);
        boolean ddlAttempted = false;
        try {
            var env = ProjectConfiguration.load("data");
            var db = Binder.get(env).bind("app.questdb", QuestDbProperties.class).get();
            var source =
                    new DriverManagerDataSource(
                            env.getRequiredProperty("spring.datasource.url"),
                            db.getUsername(),
                            db.getPassword());
            var jdbc = new JdbcTemplate(source);
            jdbc.setQueryTimeout(20);
            Path unusedLedger = evidence.resolve("unused-ledger.sqlite3");
            var reader = new QuestDbBoundedReader(jdbc, unusedLedger.toString());
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            report.put("state", "SOURCE_IDENTITY_READ");
            save(evidence, report);
            var formalBefore = state(jdbc, "etf_share");
            // 最大日期只用于选两行样本，不证明来源或历史完整；再按单日读取全部字段。
            Long latest =
                    jdbc.queryForObject(
                            "SELECT cast(max(timestamp) AS long) FROM etf_share WHERE timestamp >= cast(? AS TIMESTAMP) AND timestamp < cast(? AS TIMESTAMP)",
                            Long.class,
                            new CalendarTimestamp(today.minusDays(60))
                                    .storageEpoch(EpochUnit.MICROS),
                            new CalendarTimestamp(today.plusDays(1))
                                    .storageEpoch(EpochUnit.MICROS));
            assertTrue(latest != null);
            LocalDate sampleDate =
                    CalendarTimestamp.fromStorageEpoch(latest, EpochUnit.MICROS).date();
            report.put("state", "SOURCE_DATE_SELECTED");
            report.put("sampleDate", sampleDate);
            save(evidence, report);
            // 正式表只读；不放宽生产仓库和 writer 原有的隔离目标要求。
            var legacyDefinition = EtfShareDataset.definition("etf_share");
            var expected =
                    reader.read(
                                    legacyDefinition,
                                    new DatasetReadQuery(
                                            legacyDefinition.columns().stream()
                                                    .map(DatasetDefinition.Column::logicalName)
                                                    .toList(),
                                            Map.of(),
                                            "trade_date",
                                            sampleDate,
                                            sampleDate.plusDays(1),
                                            2,
                                            null),
                                    null,
                                    new EtfShareMapper()::fromValues)
                            .rows();
            assertEquals(2, expected.size());
            assertEquals(2, expected.stream().map(EtfShare::key).distinct().count());
            assertEquals(formalBefore, state(jdbc, "etf_share"));
            // 保存原始期望后才建表和发送；进程中断也能定位原表、原键和值。
            FileEvidenceStore.writeNewDurable(
                    evidence.resolve("expected.json"),
                    JobDefinitionJson.mapper().writeValueAsBytes(expected));
            report.put("state", "DDL_INTENT_SAVED");
            save(evidence, report);
            ddlAttempted = true;
            jdbc.execute(
                    EtfShareDataset.createIsolatedTableSql(table, CnTradeDateLayout.TRADE_DATE_V2));
            report.put("state", "TABLE_CREATED");
            report.put("sourceState", formalBefore);
            save(evidence, report);
            String identity;
            QuestDbEtfShareTarget target;
            try (var qdb = QuestDB.connect(db.qwpConfig())) {
                target =
                        new QuestDbEtfShareTarget(
                                table, jdbc, qdb, CnTradeDateLayout.TRADE_DATE_V2);
                identity = target.targetId();
                var dates = expected.stream().map(EtfShare::tradeDate).distinct().sorted().toList();
                // 迁移样本固定每行原 update_time，不冒充按 observedAt 生成的新来源作业。
                var request =
                        new WriteGroupRequest(
                                "layout-sample-" + suffix,
                                today,
                                List.of(
                                        new WriteGroupRequest.Member(
                                                "share",
                                                "etf_share",
                                                2,
                                                "rows-" + suffix,
                                                expected.stream()
                                                        .map(new EtfShareMapper()::values)
                                                        .toList())));
                var datasets =
                        new DatasetRegistry(
                                List.<DatasetImplementation>of(
                                        () -> ExchangeCalendarDataset.DEFINITION,
                                        new EtfShareReadRepository(
                                                reader, table, CnTradeDateLayout.TRADE_DATE_V2)));
                var prepared =
                        WriteGroupPlan.prepare(request, datasets, Map.of("etf_share", identity));
                report.put("requestKind", "prepared-write-layout-sample");
                report.put("requestFingerprint", prepared.fingerprint());
                report.put("sourceSyncJobExecuted", false);
                FileEvidenceStore.writeNewDurable(
                        evidence.resolve("request.json"),
                        JobDefinitionJson.mapper().writeValueAsBytes(request));
                report.put("targetId", identity);
                report.put("state", "TARGET_FROZEN");
                save(evidence, report);
                settled(jdbc, table);
                assertThrows(
                        IllegalStateException.class,
                        () -> target.newWriter(identity, 1).preflight());
                var typed =
                        new EtfShareReadRepository(reader, table, CnTradeDateLayout.TRADE_DATE_V2);
                for (int pass = 1; pass <= 2; pass++) {
                    var restored =
                            new WriteGroupJson(datasets).read(evidence.resolve("request.json"));
                    assertEquals(
                            prepared.fingerprint(),
                            WriteGroupPlan.prepare(
                                            restored, datasets, Map.of("etf_share", identity))
                                    .fingerprint());
                    var writer = target.newWriter(identity, 2);
                    writer.send(expected);
                    // 仅在本次发送已完整核验后才做第二次幂等写入；不重发未决批次。
                    visible(writer, expected);
                    settled(jdbc, table);
                    assertEquals(
                            sorted(expected),
                            sorted(writer.readback(expected.stream().map(EtfShare::key).toList())));
                    var before = state(jdbc, table);
                    var page =
                            typed.findRange(dates.getFirst(), dates.getLast().plusDays(1), 3, null);
                    assertEquals(sorted(expected), sorted(page.rows()));
                    assertFalse(page.hasMore());
                    assertEquals(
                            2L, jdbc.queryForObject("SELECT count() FROM " + table, Long.class));
                    assertEquals(before, state(jdbc, table));
                    assertEquals(identity, target.targetId());
                    report.put("verifiedPasses", pass);
                    report.put("rows", page.rows());
                    report.put("state", "PASS_VERIFIED");
                    save(evidence, report);
                }
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new EtfShareReadRepository(reader, table)
                                        .findRange(
                                                dates.getFirst(),
                                                dates.getLast().plusDays(1),
                                                2,
                                                null));
                assertEquals(formalBefore, state(jdbc, "etf_share"));
                assertFalse(Files.exists(unusedLedger));
            }
            // 客户端已停，完整键值及 WAL 已核验；只删除本测试创建且身份未变的表。
            assertEquals(identity, target.targetId());
            settled(jdbc, table);
            jdbc.execute("DROP TABLE " + table);
            assertTrue(
                    jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table)
                            .isEmpty());
            report.put("state", "VERIFIED_AND_CLEANED");
            report.put("javaTypedReadExercised", true);
            report.put("legacyLayoutRejected", true);
            save(evidence, report);
            System.out.println("D016 v2 隔离两行验收通过；原件：" + evidence);
        } catch (Exception | AssertionError failure) {
            report.put("failedAt", report.get("state"));
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause())
                if (cause instanceof SQLException sql) {
                    report.put("sqlState", String.valueOf(sql.getSQLState()));
                    report.put("sqlVendorCode", sql.getErrorCode());
                    // 此阶段只有公开行情 SELECT；仅保留服务端错误，不记录连接配置。
                    if ("SOURCE_DATE_SELECTED".equals(report.get("failedAt")))
                        report.put("sourceQueryError", sql.getMessage());
                }
            report.put("state", ddlAttempted ? "INCOMPLETE_PRESERVED" : "FAILED_BEFORE_DDL");
            report.put("failureType", failure.getClass().getName());
            save(evidence, report);
            // 不把客户端异常文本或配置写入报告，原表和发送原件留给人工核对。
            fail("D016 v2 验收未完成；保留原件：" + evidence + "；类型：" + failure.getClass().getSimpleName());
        }
    }

    private static List<EtfShare> sorted(List<EtfShare> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(EtfShare::tsCode).thenComparing(EtfShare::tradeDate))
                .toList();
    }

    private static List<Map<String, Object>> state(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList(
                "SELECT id,directoryName,table_txn FROM tables() WHERE table_name=?", table);
    }

    private static void settled(JdbcTemplate jdbc, String table) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("隔离表 WAL 未在期限内就绪");
            Thread.sleep(200);
        }
    }

    private static void visible(EtfShareWriteSession writer, List<EtfShare> expected)
            throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        var keys = expected.stream().map(EtfShare::key).toList();
        while (!writer.walSettled() || !sorted(expected).equals(sorted(writer.readback(keys)))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("隔离表完整键值未在期限内可见");
            Thread.sleep(200);
        }
    }

    private static void requireNoUnfinishedRuns(Path root) throws Exception {
        try (var paths = Files.list(root)) {
            var runs = paths.filter(Files::isDirectory).limit(101).toList();
            if (runs.size() > 100) throw new IllegalStateException("验收历史超过有界核对上限");
            for (var run : runs) {
                Path report = run.resolve("report.json");
                byte[] bytes =
                        FileEvidenceStore.readBounded(
                                report,
                                1024 * 1024,
                                () -> new IllegalStateException("原验收报告过大或无法核对"));
                String state = JobDefinitionJson.mapper().readTree(bytes).path("state").asText();
                if (!List.of("VERIFIED_AND_CLEANED", "FAILED_BEFORE_DDL").contains(state))
                    throw new IllegalStateException("存在未完成验收，先核对原表和原件：" + run);
            }
        }
    }

    private static void save(Path evidence, Map<String, Object> report) throws Exception {
        FileEvidenceStore.replaceDurable(
                evidence.resolve("report.json"),
                JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(report));
    }
}
