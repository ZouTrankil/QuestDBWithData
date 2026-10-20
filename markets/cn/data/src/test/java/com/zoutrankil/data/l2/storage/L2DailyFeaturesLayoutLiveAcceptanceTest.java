package com.zoutrankil.data.l2.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只写任务专属 v2 表；未决或失败时保留表与原件，不重发、不自动清理。 */
class L2DailyFeaturesLayoutLiveAcceptanceTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "D086_LAYOUT_LIVE_ACCEPTANCE", matches = "true")
    void explicitV2WriterTypedReadsAndRepeatedVerifiedUpsertPreserveTheFrozenTwoRowSample()
            throws Exception {
        Path evidenceRoot =
                Path.of(
                                System.getenv()
                                        .getOrDefault(
                                                "D086_LAYOUT_EVIDENCE_ROOT",
                                                "var/l2-daily-features-layout-acceptance"))
                        .toAbsolutePath();
        Files.createDirectories(evidenceRoot);
        requireNoUnfinishedRuns(evidenceRoot);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Path evidence = Files.createDirectory(evidenceRoot.resolve(suffix));
        String table = "java_d086_l2_daily_features_layout2_" + suffix;
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1);
        report.put("observedAt", Instant.now().toString());
        report.put("table", table);
        report.put("sourceCoverageVerified", false);
        report.put("modelAcceptanceVerified", false);
        report.put("requestIsProductionApproved", false);
        report.put("physicalMigrationExecuted", false);
        report.put("checkpointAdvanced", false);
        report.put("requestKind", "l2-layout-storage-sample");
        report.put("sourceSyncJobExecuted", false);
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
            var formalBefore = state(jdbc, "l2_daily_features");
            var expected =
                    new L2DailyFeaturesReadRepository(reader, "l2_daily_features")
                            .findRange(today.minusDays(60), today.plusDays(1), 2, null)
                            .rows();
            assertEquals(2, expected.size());
            assertEquals(2, expected.stream().map(L2DailyFeatures::key).distinct().count());
            assertEquals(formalBefore, state(jdbc, "l2_daily_features"));
            // 保存原始期望后才建表和发送；进程中断也能定位原表、原键和值。
            FileEvidenceStore.writeNewDurable(
                    evidence.resolve("expected.json"),
                    JobDefinitionJson.mapper().writeValueAsBytes(expected));
            report.put(
                    "expectedSha256",
                    FileEvidenceStore.sha256(
                            Files.readAllBytes(evidence.resolve("expected.json"))));
            report.put("state", "DDL_INTENT_SAVED");
            save(evidence, report);
            ddlAttempted = true;

            String identity;
            QuestDbL2DailyFeaturesTarget target;
            try (var qdb = QuestDB.connect(db.qwpConfig())) {
                target =
                        new QuestDbL2DailyFeaturesTarget(
                                table, jdbc, qdb, db, CnTradeDateLayout.TRADE_DATE_V2);
                target.createIsolatedTarget(table);
                report.put("state", "TABLE_CREATED");
                report.put("sourceState", formalBefore);
                save(evidence, report);
                identity = target.targetId();
                var dates =
                        expected.stream()
                                .map(L2DailyFeatures::tradeDate)
                                .distinct()
                                .sorted()
                                .toList();
                FileEvidenceStore.writeNewDurable(
                        evidence.resolve("request.json"),
                        JobDefinitionJson.mapper()
                                .writeValueAsBytes(
                                        Map.of(
                                                "requestKind",
                                                "l2-layout-storage-sample",
                                                "targetId",
                                                identity,
                                                "datasetSchemaVersion",
                                                2,
                                                "table",
                                                table,
                                                "from",
                                                dates.getFirst(),
                                                "to",
                                                dates.getLast(),
                                                "sourceCoverageVerified",
                                                false)));
                report.put(
                        "requestSha256",
                        FileEvidenceStore.sha256(
                                Files.readAllBytes(evidence.resolve("request.json"))));
                report.put("targetId", identity);
                report.put("state", "TARGET_FROZEN");
                save(evidence, report);
                settled(jdbc, table);
                assertThrows(
                        IllegalStateException.class,
                        () -> target.forVersion(1).newWriter().preflight());
                var typed =
                        new L2DailyFeaturesReadRepository(
                                reader, table, CnTradeDateLayout.TRADE_DATE_V2);
                for (int pass = 1; pass <= 2; pass++) {
                    var writer = target.newWriter();
                    report.put("sendPass", pass);
                    report.put("state", "SEND_INTENT_SAVED");
                    save(evidence, report);
                    writer.send(expected);
                    // 仅在本次发送已完整核验后才做第二次幂等写入；不重发未决批次。
                    visible(writer, expected);
                    settled(jdbc, table);
                    assertEquals(
                            sorted(expected),
                            sorted(
                                    writer.readback(
                                            expected.stream().map(L2DailyFeatures::key).toList())));
                    assertEquals(dates, writer.readExistingDates());
                    for (var day : dates) {
                        var selected =
                                expected.stream()
                                        .filter(row -> row.tradeDate().equals(day))
                                        .toList();
                        assertEquals(selected.size(), writer.countRows(day));
                        assertEquals(
                                selected.size(),
                                writer.countRows(
                                        day,
                                        selected.stream()
                                                .map(L2DailyFeatures::symbol)
                                                .distinct()
                                                .toList()));
                    }
                    String versionBefore = writer.stableVersion();
                    var before = state(jdbc, table);
                    var page =
                            typed.findRange(dates.getFirst(), dates.getLast().plusDays(1), 3, null);
                    assertEquals(sorted(expected), sorted(page.rows()));
                    assertFalse(page.hasMore());
                    assertEquals(110, typed.definition().columns().size());
                    assertEquals(108, page.rows().getFirst().features().size());
                    assertEquals(
                            108,
                            Arrays.stream(L2DailyFeatureField.values())
                                    .filter(field -> !field.identity())
                                    .count());
                    assertEquals(versionBefore, writer.stableVersion());
                    var first =
                            typed.findRange(dates.getFirst(), dates.getLast().plusDays(1), 1, null);
                    assertTrue(first.hasMore());
                    var second =
                            typed.findRange(
                                    dates.getFirst(),
                                    dates.getLast().plusDays(1),
                                    1,
                                    first.nextCursor());
                    var combined = new ArrayList<>(first.rows());
                    combined.addAll(second.rows());
                    assertEquals(sorted(expected), sorted(combined));
                    assertFalse(second.hasMore());
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
                                new L2DailyFeaturesReadRepository(reader, table)
                                        .findRange(
                                                dates.getFirst(),
                                                dates.getLast().plusDays(1),
                                                2,
                                                null));
                assertEquals(formalBefore, state(jdbc, "l2_daily_features"));
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
            System.out.println("D086 v2 隔离两行验收通过；原件：" + evidence);
        } catch (Exception | AssertionError failure) {
            report.put("state", ddlAttempted ? "INCOMPLETE_PRESERVED" : "FAILED_BEFORE_DDL");
            report.put("failureType", failure.getClass().getName());
            save(evidence, report);
            // 不把客户端异常文本或配置写入报告，原表和发送原件留给人工核对。
            fail("D086 v2 验收未完成；保留原件：" + evidence + "；类型：" + failure.getClass().getSimpleName());
        }
    }

    /** 仅显式核对原超时发送；不调用 send，不用新 UUID 绕过原未决表。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "D086_LAYOUT_RECONCILE", matches = "true")
    void originalTimedOutSendCanOnlyBeReadBackAndCleanedUnderPinnedEvidence() throws Exception {
        Path reportPath = Path.of(required("D086_LAYOUT_RECONCILE_REPORT")).toRealPath();
        byte[] original = pinned(reportPath, required("D086_LAYOUT_RECONCILE_REPORT_SHA"));
        var report = JobDefinitionJson.mapper().readTree(original);
        assertEquals("INCOMPLETE_PRESERVED", report.path("state").asText());
        assertEquals("l2-layout-storage-sample", report.path("requestKind").asText());
        assertEquals(1, report.path("sendPass").asInt());
        Path evidence = reportPath.getParent();
        Path receipt = evidence.resolve("reconciliation.json");
        assertFalse(Files.exists(receipt));
        byte[] expectedBytes =
                pinned(
                        evidence.resolve("expected.json"),
                        required("D086_LAYOUT_RECONCILE_EXPECTED_SHA"));
        byte[] requestBytes =
                pinned(
                        evidence.resolve("request.json"),
                        required("D086_LAYOUT_RECONCILE_REQUEST_SHA"));
        var expected =
                JobDefinitionJson.mapper()
                        .readValue(expectedBytes, new TypeReference<List<L2DailyFeatures>>() {});
        var request = JobDefinitionJson.mapper().readTree(requestBytes);
        assertEquals(2, expected.size());
        assertEquals(2, request.path("datasetSchemaVersion").asInt());
        String table = report.path("table").asText();
        assertEquals(table, request.path("table").asText());
        String identity = report.path("targetId").asText();
        assertEquals(identity, request.path("targetId").asText());
        var env = ProjectConfiguration.load("data");
        var db = Binder.get(env).bind("app.questdb", QuestDbProperties.class).get();
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                env.getRequiredProperty("spring.datasource.url"),
                                db.getUsername(),
                                db.getPassword()));
        jdbc.setQueryTimeout(20);
        QuestDbL2DailyFeaturesTarget target;
        List<L2DailyFeatures> actual;
        try (var qdb = QuestDB.connect(db.qwpConfig())) {
            target =
                    new QuestDbL2DailyFeaturesTarget(
                            table, jdbc, qdb, db, CnTradeDateLayout.TRADE_DATE_V2);
            assertEquals(identity, target.targetId());
            var writer = target.newWriter();
            writer.preflight();
            assertTrue(writer.walSettled());
            String version = writer.stableVersion();
            var before = state(jdbc, table);
            actual = writer.readback(expected.stream().map(L2DailyFeatures::key).toList());
            assertEquals(sorted(expected), sorted(actual));
            var dates =
                    expected.stream().map(L2DailyFeatures::tradeDate).distinct().sorted().toList();
            assertEquals(dates, writer.readExistingDates());
            var reader =
                    new QuestDbBoundedReader(
                            jdbc, evidence.resolve("unused-recovery-ledger.sqlite3").toString());
            assertEquals(
                    sorted(expected),
                    sorted(
                            new L2DailyFeaturesReadRepository(
                                            reader, table, CnTradeDateLayout.TRADE_DATE_V2)
                                    .findRange(
                                            dates.getFirst(), dates.getLast().plusDays(1), 3, null)
                                    .rows()));
            assertEquals(2L, jdbc.queryForObject("SELECT count() FROM " + table, Long.class));
            assertEquals(version, writer.stableVersion());
            assertEquals(before, state(jdbc, table));
            assertEquals(identity, target.targetId());
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("state", "RECONCILED_AND_CLEANED");
        result.put("verifiedAt", Instant.now());
        result.put("originalReportSha256", FileEvidenceStore.sha256(original));
        result.put("expectedSha256", FileEvidenceStore.sha256(expectedBytes));
        result.put("requestSha256", FileEvidenceStore.sha256(requestBytes));
        result.put("targetId", identity);
        result.put("table", table);
        result.put("rows", actual);
        result.put("resent", false);
        result.put("sourceCoverageVerified", false);
        result.put("state", "VERIFIED_CLEANUP_INTENT");
        FileEvidenceStore.writeNewDurable(
                evidence.resolve("reconciliation-predelete.json"),
                JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(result));
        // 已确认原测试进程终止；这里没有发送，只清理完整回读且身份未变的专属表。
        assertEquals(identity, target.targetId());
        settled(jdbc, table);
        jdbc.execute("DROP TABLE " + table);
        assertTrue(state(jdbc, table).isEmpty());
        result.put("state", "RECONCILED_AND_CLEANED");
        FileEvidenceStore.writeNewDurable(
                receipt,
                JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(result));
        assertEquals(
                FileEvidenceStore.sha256(original),
                FileEvidenceStore.sha256(Files.readAllBytes(reportPath)));
        System.out.println("D086 原发送只读核对并清理完成；原失败报告未改：" + evidence);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少明确原件参数：" + key);
        return value;
    }

    private static byte[] pinned(Path path, String sha) throws Exception {
        assertTrue(sha.matches("[0-9a-f]{64}"));
        byte[] bytes =
                FileEvidenceStore.readBounded(
                        path, 1024 * 1024, () -> new IllegalStateException("原件过大或缺失"));
        assertEquals(sha, FileEvidenceStore.sha256(bytes));
        return bytes;
    }

    private static List<L2DailyFeatures> sorted(List<L2DailyFeatures> rows) {
        return rows.stream()
                .sorted(
                        Comparator.comparing(L2DailyFeatures::symbol)
                                .thenComparing(L2DailyFeatures::tradeDate))
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

    private static void visible(L2DailyFeaturesWriteSession writer, List<L2DailyFeatures> expected)
            throws Exception {
        long deadline = System.nanoTime() + 120_000_000_000L;
        var keys = expected.stream().map(L2DailyFeatures::key).toList();
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
                if (!List.of("VERIFIED_AND_CLEANED", "FAILED_BEFORE_DDL").contains(state)) {
                    Path receipt = run.resolve("reconciliation.json");
                    if (!Files.isRegularFile(receipt))
                        throw new IllegalStateException("存在未完成验收，先核对原表和原件：" + run);
                    var recovery =
                            JobDefinitionJson.mapper()
                                    .readTree(
                                            FileEvidenceStore.readBounded(
                                                    receipt,
                                                    1024 * 1024,
                                                    () -> new IllegalStateException("原核对报告过大")));
                    if (!"RECONCILED_AND_CLEANED".equals(recovery.path("state").asText())
                            || !FileEvidenceStore.sha256(bytes)
                                    .equals(recovery.path("originalReportSha256").asText()))
                        throw new IllegalStateException("原核对报告未绑定失败原件：" + run);
                }
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
