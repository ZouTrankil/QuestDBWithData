package com.zoutrankil.data.stock.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.domain.stock.market.StockSuspendDataset;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.stock.mapper.StockSuspendMapper;
import com.zoutrankil.data.stock.port.StockSuspendWriteSession;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;
import com.zoutrankil.runtime.ProjectConfiguration;

import io.questdb.client.QuestDB;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只写任务专属 v2 表；未决或失败时保留表与原件，不重发、不自动清理。 */
@EnabledIfEnvironmentVariable(named = "D011_LAYOUT_LIVE_ACCEPTANCE", matches = "true")
class StockSuspendLayoutLiveAcceptanceTest {
    @Test
    void explicitV2WriterTypedReadsAndRepeatedVerifiedUpsertPreserveTheFrozenTwoRowSample()
            throws Exception {
        Path evidenceRoot =
                Path.of(
                                System.getenv()
                                        .getOrDefault(
                                                "D011_LAYOUT_EVIDENCE_ROOT",
                                                "var/stk-suspend-layout-acceptance"))
                        .toAbsolutePath();
        Files.createDirectories(evidenceRoot);
        requireNoUnfinishedRuns(evidenceRoot);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Path evidence = Files.createDirectory(evidenceRoot.resolve(suffix));
        String table = "java_d011_stk_suspend_layout2_" + suffix;
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
            report.put("state", "SOURCE_IDENTITY_READ");
            save(evidence, report);
            var formalBefore = state(jdbc, "stk_suspend");
            // 默认日期来自既有只读核查的真实样本；只选两行，不证明该日或全历史完整。
            LocalDate sampleDate =
                    LocalDate.parse(
                            System.getenv().getOrDefault("D011_LAYOUT_SAMPLE_DATE", "2010-01-29"));
            report.put("state", "SOURCE_DATE_SELECTED");
            report.put("sampleDate", sampleDate);
            save(evidence, report);
            var expected =
                    new StockSuspendReadRepository(reader)
                            .findRange(null, sampleDate, sampleDate.plusDays(1), 2, null)
                            .rows();
            assertEquals(2, expected.size());
            assertEquals(2, expected.stream().map(StockSuspend::key).distinct().count());
            assertEquals(formalBefore, state(jdbc, "stk_suspend"));
            // 保存原始期望后才建表和发送；进程中断也能定位原表、原键和值。
            FileEvidenceStore.writeNewDurable(
                    evidence.resolve("expected.json"),
                    JobDefinitionJson.mapper().writeValueAsBytes(expected));
            report.put("state", "DDL_INTENT_SAVED");
            save(evidence, report);
            String ddl =
                    "CREATE TABLE "
                            + table
                            + " (ts_code SYMBOL, is_suspended LONG, trade_date TIMESTAMP) "
                            + "TIMESTAMP(trade_date) PARTITION BY DAY WAL DEDUP UPSERT KEYS(ts_code,trade_date)";
            FileEvidenceStore.writeNewDurable(
                    evidence.resolve("ddl-intent.json"),
                    JobDefinitionJson.mapper()
                            .writeValueAsBytes(Map.of("table", table, "sql", ddl)));
            ddlAttempted = true;
            jdbc.execute(ddl);
            report.put("state", "TABLE_CREATED");
            report.put("sourceState", formalBefore);
            save(evidence, report);
            String identity;
            LiveTarget target;
            try (var qdb = QuestDB.connect(db.qwpConfig())) {
                target = new LiveTarget(table, jdbc, qdb, CnTradeDateLayout.TRADE_DATE_V2);
                identity = target.targetId();
                var dates =
                        expected.stream().map(StockSuspend::tradeDate).distinct().sorted().toList();
                // 仅验证固定行的存储布局，不运行或伪造停复牌来源作业。
                var request =
                        Map.of(
                                "kind",
                                "storage-layout-rehearsal",
                                "definition",
                                StockSuspendDataset.definition(
                                        table, CnTradeDateLayout.TRADE_DATE_V2),
                                "targetId",
                                identity,
                                "rows",
                                expected.stream()
                                        .map(value -> new StockSuspendMapper().values(value))
                                        .toList());
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
                        new StockSuspendReadRepository(
                                reader, table, CnTradeDateLayout.TRADE_DATE_V2);
                for (int pass = 1; pass <= 2; pass++) {
                    var writer = target.newWriter(identity, 2);
                    FileEvidenceStore.writeNewDurable(
                            evidence.resolve("pass-" + pass + "-send-intent.json"),
                            JobDefinitionJson.mapper()
                                    .writeValueAsBytes(
                                            Map.of(
                                                    "pass",
                                                    pass,
                                                    "targetId",
                                                    identity,
                                                    "rows",
                                                    expected)));
                    report.put("state", "PASS_" + pass + "_SEND_INTENT");
                    save(evidence, report);
                    writer.send(expected);
                    report.put("state", "PASS_" + pass + "_SEND_ACKNOWLEDGED");
                    save(evidence, report);
                    // 仅在本次发送已完整核验后才做第二次幂等写入；不重发未决批次。
                    visible(writer, expected);
                    report.put("state", "PASS_" + pass + "_FULL_KEYS_VERIFIED");
                    save(evidence, report);
                    settled(jdbc, table);
                    assertEquals(
                            sorted(expected),
                            sorted(
                                    writer.readback(
                                            expected.stream().map(StockSuspend::key).toList())));
                    var before = state(jdbc, table);
                    var page =
                            typed.findRange(
                                    null, dates.getFirst(), dates.getLast().plusDays(1), 3, null);
                    assertEquals(sorted(expected), sorted(page.rows()));
                    assertFalse(page.hasMore());
                    report.put("state", "PASS_" + pass + "_TYPED_READ_VERIFIED");
                    save(evidence, report);
                    assertEquals(
                            2L, jdbc.queryForObject("SELECT count() FROM " + table, Long.class));
                    assertEquals(before, state(jdbc, table));
                    assertEquals(identity, target.targetId());
                    var storage =
                            new StockSuspendStorage(jdbc, table, CnTradeDateLayout.TRADE_DATE_V2);
                    var snapshot = storage.snapshot();
                    assertEquals(sorted(expected), sorted(snapshot.rows()));
                    assertEquals(independentDigest(expected), snapshot.fingerprint());
                    assertEquals(
                            0,
                            StockSuspendStorage.outside(
                                            snapshot.rows(),
                                            dates.getFirst(),
                                            dates.getLast().plusDays(1))
                                    .size());
                    assertEquals(
                            snapshot.rows(),
                            StockSuspendStorage.outside(
                                    snapshot.rows(),
                                    dates.getLast().plusDays(1),
                                    dates.getLast().plusDays(2)));
                    report.put("snapshot", snapshot);
                    report.put("verifiedPasses", pass);
                    report.put("rows", page.rows());
                    report.put("state", "PASS_VERIFIED");
                    save(evidence, report);
                }
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new StockSuspendReadRepository(
                                                reader, table, CnTradeDateLayout.LEGACY_V1)
                                        .findRange(
                                                null,
                                                dates.getFirst(),
                                                dates.getLast().plusDays(1),
                                                2,
                                                null));
                assertEquals(formalBefore, state(jdbc, "stk_suspend"));
                assertFalse(Files.exists(unusedLedger));
            }
            // 客户端已停，完整键值及 WAL 已核验；只删除本测试创建且身份未变的表。
            assertEquals(identity, target.targetId());
            settled(jdbc, table);
            var cleanupSnapshot =
                    new StockSuspendStorage(jdbc, table, CnTradeDateLayout.TRADE_DATE_V2)
                            .snapshot();
            assertEquals(sorted(expected), sorted(cleanupSnapshot.rows()));
            assertEquals(independentDigest(expected), cleanupSnapshot.fingerprint());
            FileEvidenceStore.writeNewDurable(
                    evidence.resolve("cleanup-intent.json"),
                    JobDefinitionJson.mapper()
                            .writeValueAsBytes(
                                    Map.of(
                                            "targetId",
                                            identity,
                                            "snapshot",
                                            cleanupSnapshot,
                                            "writerStopped",
                                            true)));
            assertEquals(identity, target.targetId());
            jdbc.execute("DROP TABLE " + table);
            assertTrue(
                    jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table)
                            .isEmpty());
            report.put("state", "VERIFIED_AND_CLEANED");
            report.put("javaTypedReadExercised", true);
            report.put("legacyLayoutRejected", true);
            save(evidence, report);
            System.out.println("D011 v2 隔离两行验收通过；原件：" + evidence);
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
            report.put(
                    "failureFrames",
                    Arrays.stream(failure.getStackTrace())
                            .limit(16)
                            .map(StackTraceElement::toString)
                            .toList());
            save(evidence, report);
            // 不把客户端异常文本或配置写入报告，原表和发送原件留给人工核对。
            fail("D011 v2 验收未完成；保留原件：" + evidence + "；类型：" + failure.getClass().getSimpleName());
        }
    }

    private static String independentDigest(List<StockSuspend> rows) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes)) {
            for (var row :
                    rows.stream()
                            .sorted(
                                    Comparator.comparing(StockSuspend::tradeDate)
                                            .thenComparing(StockSuspend::tsCode))
                            .toList()) {
                byte[] value =
                        ("{\"ts_code\":\""
                                        + row.tsCode()
                                        + "\",\"is_suspended\":1,\"trade_date\":\""
                                        + row.tradeDate()
                                        + "\"}")
                                .getBytes(StandardCharsets.UTF_8);
                data.writeInt(value.length);
                data.write(value);
            }
        }
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    private static final class LiveTarget {
        private final String table;
        private final JdbcTemplate jdbc;
        private final QuestDB qdb;
        private final CnTradeDateLayout layout;

        LiveTarget(String table, JdbcTemplate jdbc, QuestDB qdb, CnTradeDateLayout layout) {
            this.table = table;
            this.jdbc = jdbc;
            this.qdb = qdb;
            this.layout = layout;
        }

        String targetId() {
            var state = new StockSuspendStorage(jdbc, table, layout).preflight();
            return StockSuspendStorage.physicalTargetId(jdbc, table, state);
        }

        StockSuspendWriteSession newWriter(String identity, int version) {
            return new StockSuspendWritePort(
                    table, jdbc, qdb, identity, CnTradeDateLayout.fromVersion(version));
        }
    }

    private static List<StockSuspend> sorted(List<StockSuspend> rows) {
        return rows.stream()
                .sorted(
                        Comparator.comparing(StockSuspend::tsCode)
                                .thenComparing(StockSuspend::tradeDate))
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

    private static void visible(StockSuspendWriteSession writer, List<StockSuspend> expected)
            throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        var keys = expected.stream().map(StockSuspend::key).toList();
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
