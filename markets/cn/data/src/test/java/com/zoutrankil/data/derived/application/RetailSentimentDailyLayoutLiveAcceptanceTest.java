package com.zoutrankil.data.derived.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyLayout;
import com.zoutrankil.data.derived.storage.QuestDbRetailSentimentDailyTarget;
import com.zoutrankil.data.derived.storage.RetailSentimentDailyMaterializationPort;
import com.zoutrankil.data.derived.storage.RetailSentimentDailyReadRepository;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.derived.RetailSentimentDaily;
import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.l2.storage.QuestDbL2DailyFeaturesTarget;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

/** 只操作真实归属已核验的私有实例；夹具不是正式来源，失败时原表和账本全部保留。 */
@EnabledIfEnvironmentVariable(named = "D098_LAYOUT_LIVE_ACCEPTANCE", matches = "true")
class RetailSentimentDailyLayoutLiveAcceptanceTest {
    static final LocalDate FIRST = LocalDate.of(2026, 9, 17);
    static final LocalDate SECOND = FIRST.plusDays(1);
    static final LocalDate LAST = FIRST.plusDays(4);
    static final LocalDate LOGICAL = LocalDate.of(2026, 10, 21);

    @Test
    void realNativeV2RefreshRevisionPrefixOriginalVersionResumeAndLostAckReconciliation()
            throws Exception {
        Path root = Path.of(required("D098_LAYOUT_EVIDENCE_ROOT")).toAbsolutePath();
        Files.createDirectories(root);
        requireNoUnfinished(root);
        Path evidence = Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "java.retail_sentiment_layout_live.v2");
        report.put("observedAt", Instant.now());
        report.put("fixtureOnly", true);
        report.put("sourceCoverageVerified", false);
        report.put("calendarOrigin", "明确固定的有限夹具；不是正式SSE来源验收");
        report.put("productionCheckpointAdvanced", false);
        report.put("physicalMigrationExecuted", false);
        report.put("phase", "PREPARED");
        save(evidence, report);
        boolean ddlIntent = false;
        try {
            Path serverRecord = Path.of(required("D098_LAYOUT_SERVER_RECORD"));
            byte[] record =
                    FileEvidenceStore.readBounded(
                            serverRecord, 65536, () -> new IllegalStateException("服务器原件缺失或过大"));
            assertEquals(
                    required("D098_LAYOUT_SERVER_RECORD_SHA"), FileEvidenceStore.sha256(record));
            var server = JobDefinitionJson.mapper().readTree(record);
            assertEquals(
                    Path.of("").toRealPath(),
                    Path.of(server.path("worktree").asText()).toRealPath());
            assertEquals(
                    Path.of("var/d098-isolated-questdb").toRealPath(),
                    Path.of(server.path("root").asText()).toRealPath());
            FileEvidenceStore.writeNewDurable(evidence.resolve("server.json"), record);
            var layout =
                    new RetailSentimentDailyLayout(
                            CnTradeDateLayout.TRADE_DATE_V2,
                            server.path("source").asText(),
                            server.path("output").asText());
            var db = new QuestDbProperties();
            db.setHost("127.0.0.1");
            db.setPgPort(18822);
            db.setQwpPort(19010);
            db.setUsername("admin");
            db.setPassword("quest");
            db.setDatabase("qdb");
            var source =
                    new DriverManagerDataSource(
                            "jdbc:postgresql://127.0.0.1:18822/qdb",
                            db.getUsername(),
                            db.getPassword());
            var jdbc = new RefreshJdbc(source, false);
            jdbc.setQueryTimeout(20);
            var guard = new RetailSentimentDailyMaterializationPort(jdbc, db, true, null, layout);
            guard.verifyPrivateInstance();
            for (String table :
                    List.of(layout.sourceTable(), layout.outputTable(), "exchange_calendar"))
                assertTrue(identity(jdbc, table).isEmpty(), "原实例已有业务对象，不能重新初始化");
            var initial =
                    List.of(
                            row(FIRST, "000001.SZ", 100000000., false),
                            row(FIRST, "000002.SZ", 300000000., true),
                            empty(SECOND, "000001.SZ"));
            var changes =
                    List.of(
                            row(FIRST, "000001.SZ", 200000000., false),
                            row(LAST, "000001.SZ", 100000000., false));
            var beforeRows = List.of(aggregate(FIRST, 4.), nullAggregate(SECOND));
            var finalRows = List.of(aggregate(FIRST, 5.), nullAggregate(SECOND), single(LAST));
            FileEvidenceStore.writeNewDurable(
                    evidence.resolve("fixture.json"),
                    JobDefinitionJson.mapper()
                            .writeValueAsBytes(
                                    Map.of(
                                            "initial",
                                            initial,
                                            "revisionAndAppend",
                                            changes,
                                            "expectedBefore",
                                            beforeRows,
                                            "expectedAfter",
                                            finalRows,
                                            "openDates",
                                            List.of(FIRST, SECOND, LAST),
                                            "closedDates",
                                            List.of(FIRST.plusDays(2), FIRST.plusDays(3)))));
            report.put("sourceTable", layout.sourceTable());
            report.put("mvTable", layout.outputTable());
            phase(evidence, report, "CALENDAR_DDL_INTENT");
            ddlIntent = true;
            jdbc.execute(
                    "CREATE TABLE exchange_calendar(exchange SYMBOL,cal_date TIMESTAMP,is_open INT,pretrade_date STRING) TIMESTAMP(cal_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(exchange,cal_date)");
            for (int offset = 0; offset <= 4; offset++) {
                String date = FIRST.plusDays(offset).toString() + "T00:00:00.000000Z";
                jdbc.execute(
                        "INSERT INTO exchange_calendar VALUES ('SSE','"
                                + date
                                + "',"
                                + (offset == 0 || offset == 1 || offset == 4 ? 1 : 0)
                                + ",null)");
            }
            settled(jdbc, "exchange_calendar");
            report.put("calendarIdentity", identity(jdbc, "exchange_calendar"));
            try (var qdb = QuestDB.connect(db.qwpConfig())) {
                var target =
                        new QuestDbL2DailyFeaturesTarget(
                                layout.sourceTable(),
                                jdbc,
                                qdb,
                                db,
                                CnTradeDateLayout.TRADE_DATE_V2);
                phase(evidence, report, "SOURCE_DDL_INTENT");
                target.createIsolatedTarget(layout.sourceTable());
                report.put("sourceIdentity", identity(jdbc, layout.sourceTable()));
                report.put("sourceTargetId", target.targetId());
                phase(evidence, report, "SOURCE_FIRST_SEND_INTENT");
                var writer = target.newWriter();
                writer.send(initial);
                visible(writer, initial);
                var sourceInitialVersion = writer.stableVersion();
                phase(evidence, report, "MV_CREATE_INTENT");
                guard.createIsolatedTarget();
                ready(guard);
                report.put("mvIdentity", identity(jdbc, layout.outputTable()));
                report.put("mvTargetId", guard.targetId());
                Path ledger = evidence.resolve("ledger.sqlite3");
                var owner = owner(jdbc, db, layout, true, ledger);
                var read =
                        new RetailSentimentDailyReadRepository(
                                new QuestDbBoundedReader(jdbc), layout);
                var firstPlan = owner.plan(FIRST, SECOND, LOGICAL, Mode.INCREMENTAL);
                FileEvidenceStore.writeNewDurable(
                        evidence.resolve("first-plan.json"),
                        JobDefinitionJson.mapper().writeValueAsBytes(firstPlan));
                phase(evidence, report, "FIRST_REFRESH_INTENT");
                var first = owner.run(firstPlan);
                assertEquals(SyncRunState.VERIFIED, first.result().state());
                report.put("first", first);
                save(evidence, report);
                assertEquals(beforeRows, read.findRange(FIRST, SECOND.plusDays(1), 4, null).rows());
                var stored = SyncRunLedger.openReadOnly(ledger);
                String original = stored.getRun(first.result().runId()).frozenJson();
                String originalProof = stored.get(first.result().runId()).payloadJson();
                phase(evidence, report, "REPLAY_REFRESH_INTENT");
                var replay = owner.run(owner.plan(FIRST, SECOND, LOGICAL, Mode.INCREMENTAL));
                assertEquals(SyncRunState.VERIFIED, replay.result().state());
                assertEquals(beforeRows, read.findRange(FIRST, SECOND.plusDays(1), 4, null).rows());
                report.put("replay", replay);
                save(evidence, report);
                var disabled = owner(jdbc, db, layout, false, ledger);
                var noWritePlan = disabled.plan(FIRST, SECOND, LOGICAL, Mode.MATERIALIZE);
                phase(evidence, report, "EXPECTED_DISABLED_PREFLIGHT");
                var failed = disabled.run(noWritePlan);
                assertEquals(SyncRunState.FAILED, failed.result().state());
                String failedJson = stored.getRun(failed.result().runId()).frozenJson();
                var flipped = owner(jdbc, db, RetailSentimentDailyLayout.LEGACY, true, ledger);
                phase(evidence, report, "ORIGINAL_VERSION_RESUME_INTENT");
                var resumed = flipped.resume(failed.result().runId());
                assertEquals(SyncRunState.VERIFIED, resumed.result().state());
                assertEquals(failedJson, stored.getRun(resumed.result().runId()).frozenJson());
                assertEquals(2, stored.getRun(resumed.result().runId()).jobVersion());
                assertEquals(guard.targetId(), flipped.status(resumed.result().runId()).targetId());
                report.put("resumedFromDisabled", resumed);
                save(evidence, report);
                var prefixesBefore = prefixFiles(evidence);
                phase(evidence, report, "SOURCE_REVISION_AND_APPEND_INTENT");
                var nextWriter = target.newWriter();
                nextWriter.send(changes);
                visible(
                        nextWriter,
                        List.of(
                                changes.getFirst(),
                                initial.get(1),
                                initial.get(2),
                                changes.get(1)));
                assertNotEquals(sourceInitialVersion, nextWriter.stableVersion());
                ready(guard);
                var increment = owner.plan(FIRST, LAST, LOGICAL, Mode.INCREMENTAL);
                assertEquals(SECOND, increment.request().parameters().get("checkpoint_before"));
                assertEquals(original, stored.getRun(first.result().runId()).frozenJson());
                assertEquals(originalProof, stored.get(first.result().runId()).payloadJson());
                assertTrue(prefixFiles(evidence).size() > prefixesBefore.size());
                assertEquals(finalRows, read.findRange(FIRST, LAST.plusDays(1), 4, null).rows());
                phase(evidence, report, "INCREMENTAL_REFRESH_INTENT");
                var inc = owner.run(increment);
                assertEquals(SyncRunState.VERIFIED, inc.result().state());
                report.put("incremental", inc);
                save(evidence, report);
                int beforeStale = jdbc.refreshes;
                var stale = flipped.resume(failed.result().runId());
                assertEquals(SyncRunState.FAILED, stale.result().state());
                assertEquals(beforeStale, jdbc.refreshes);
                assertEquals(failedJson, stored.getRun(failed.result().runId()).frozenJson());
                report.put("changedSourceRejected", stale);
                save(evidence, report);
                var faultJdbc = new RefreshJdbc(source, true);
                faultJdbc.setQueryTimeout(20);
                var faultOwner = owner(faultJdbc, db, layout, true, ledger);
                var faultPlan = faultOwner.plan(FIRST, LAST, LOGICAL, Mode.MATERIALIZE);
                FileEvidenceStore.writeNewDurable(
                        evidence.resolve("lost-ack-plan.json"),
                        JobDefinitionJson.mapper().writeValueAsBytes(faultPlan));
                phase(evidence, report, "FAULT_REFRESH_INTENT");
                var unknown = faultOwner.run(faultPlan);
                assertEquals(SyncRunState.IN_DOUBT, unknown.result().state());
                assertEquals(1, faultJdbc.refreshes);
                report.put("injectedUnknown", unknown);
                save(evidence, report);
                var locks = new DatasetIntervalLock(ledger);
                var scope = DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily");
                var lease = locks.findOwned(unknown.result().runId(), scope);
                assertNotNull(lease);
                assertTrue(lease.inDoubt());
                assertThrows(
                        IllegalStateException.class,
                        () -> flipped.resume(unknown.result().runId()));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> flipped.reconcile(unknown.result().runId(), false));
                assertEquals(lease, locks.findOwned(unknown.result().runId(), scope));
                ready(guard);
                String unknownJson = stored.getRun(unknown.result().runId()).frozenJson();
                int refreshes = jdbc.refreshes;
                // 注入发送已经返回并结束，没有后台客户端；先等待服务端完整稳定，再明确只读核验。
                phase(evidence, report, "EXPLICIT_READONLY_RECONCILE");
                var reconciled = flipped.reconcile(unknown.result().runId(), true);
                assertEquals(SyncRunState.VERIFIED, reconciled.state());
                assertNull(locks.findOwned(unknown.result().runId(), scope));
                assertEquals(1, faultJdbc.refreshes);
                assertEquals(refreshes, jdbc.refreshes);
                assertEquals(unknownJson, stored.getRun(unknown.result().runId()).frozenJson());
                report.put("reconciled", reconciled);
                save(evidence, report);
                phase(evidence, report, "FULL_REFRESH_INTENT");
                var full = owner.repairIsolated();
                assertEquals(SyncRunState.VERIFIED, full.result().state());
                report.put("full", full);
                save(evidence, report);
                assertEquals(finalRows, read.findRange(FIRST, LAST.plusDays(1), 4, null).rows());
                var page1 = read.findRange(FIRST, LAST.plusDays(1), 1, null);
                assertTrue(page1.hasMore());
                var page2 = read.findRange(FIRST, LAST.plusDays(1), 2, page1.nextCursor());
                assertFalse(page2.hasMore());
                var all = new ArrayList<>(page1.rows());
                all.addAll(page2.rows());
                assertEquals(finalRows, all);
                assertEquals(
                        4L,
                        jdbc.queryForObject(
                                "SELECT count() FROM " + layout.sourceTable(), Long.class));
                assertEquals(
                        3L,
                        jdbc.queryForObject(
                                "SELECT count() FROM " + layout.outputTable(), Long.class));
                for (var run : stored.history(RetailSentimentDailyJobService.JOB_ID, null, 100)) {
                    assertFalse(
                            Set.of(SyncRunState.IN_DOUBT, SyncRunState.RUNNING)
                                    .contains(run.state()));
                    assertNull(locks.findOwned(run.id(), scope));
                }
                report.put("manualRefreshes", jdbc.refreshes);
                report.put("faultRefreshes", faultJdbc.refreshes);
                report.put("finalRows", finalRows);
                report.put("prefixProofs", prefixFiles(evidence));
                assertEquals(report.get("sourceIdentity"), identity(jdbc, layout.sourceTable()));
                assertEquals(report.get("mvIdentity"), identity(jdbc, layout.outputTable()));
            }
            // 客户端已关，所有意图终结后保存清理凭证；只删除本次原身份对象，MV 先于基表。
            report.put("phase", "VERIFIED_CLEANUP_INTENT");
            save(evidence, report);
            assertEquals(report.get("mvIdentity"), identity(jdbc, layout.outputTable()));
            jdbc.execute("DROP MATERIALIZED VIEW " + layout.outputTable());
            assertEquals(report.get("sourceIdentity"), identity(jdbc, layout.sourceTable()));
            jdbc.execute("DROP TABLE " + layout.sourceTable());
            assertEquals(report.get("calendarIdentity"), identity(jdbc, "exchange_calendar"));
            jdbc.execute("DROP TABLE exchange_calendar");
            for (String table :
                    List.of(layout.sourceTable(), layout.outputTable(), "exchange_calendar"))
                assertTrue(identity(jdbc, table).isEmpty());
            phase(evidence, report, "VERIFIED_AND_CLEANED");
            System.out.println("D098 v2原生MV真实验收通过；有限夹具原件：" + evidence);
        } catch (Exception | AssertionError failure) {
            report.put("failedPhase", report.get("phase"));
            report.put("phase", ddlIntent ? "INCOMPLETE_PRESERVED" : "FAILED_BEFORE_DDL");
            report.put("failureType", failure.getClass().getName());
            save(evidence, report);
            // 本测试只有明确私有实例；保留异常原因便于定位，外部正式凭据从未加载。
            throw new AssertionError("D098 v2验收未完成；原件保留：" + evidence, failure);
        }
    }

    static class RefreshJdbc extends JdbcTemplate {
        int refreshes;
        final boolean loseAck;

        RefreshJdbc(DataSource source, boolean loseAck) {
            super(source);
            this.loseAck = loseAck;
        }

        @Override
        public void execute(String sql) {
            boolean refresh = sql.startsWith("REFRESH MATERIALIZED VIEW ");
            if (refresh) refreshes++;
            super.execute(sql);
            if (refresh && loseAck)
                throw new DataAccessResourceFailureException("测试注入：真实刷新已提交，丢弃调用方确认");
        }
    }

    private static RetailSentimentDailyJobService owner(
            JdbcTemplate jdbc,
            QuestDbProperties props,
            RetailSentimentDailyLayout layout,
            boolean enabled,
            Path ledger) {
        return new RetailSentimentDailyJobService(
                new QuestDbRetailSentimentDailyTarget(jdbc, props, enabled, "", layout), ledger);
    }

    private static L2DailyFeatures empty(LocalDate date, String symbol) {
        var values = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
        for (var f : L2DailyFeatureField.values()) if (!f.identity()) values.put(f, null);
        for (var field : L2DailyFeatureField.values())
            if (field.storageType() == StorageType.BOOLEAN) values.put(field, false);
        return new L2DailyFeatures(date, symbol, values);
    }

    private static L2DailyFeatures row(
            LocalDate date, String symbol, double amount, boolean other) {
        var values = new EnumMap<L2DailyFeatureField, Object>(empty(date, symbol).features());
        for (var field : L2DailyFeatureField.values())
            if (field.storageType() == StorageType.BOOLEAN) values.put(field, true);
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("gmm_retail_ratio", other ? .75 : .25);
        metrics.put("mean_retail_entropy", other ? null : 1.);
        metrics.put("retail_total_amount", amount);
        metrics.put("retail_funds_net_inflow", other ? -10000000. : 20000000.);
        metrics.put("mean_rel_aggro", other ? .75 : .25);
        metrics.put("q1_count", other ? 4L : 2L);
        metrics.put("q3_count", other ? null : 3L);
        metrics.put("wash_trade_ratio", other ? null : .125);
        metrics.put("spoof_count", other ? null : 5L);
        metrics.put("fake_support_count", other ? null : 2L);
        metrics.put("fake_pressure_count", other ? 7L : 3L);
        metrics.put("mfi_score", other ? 60. : 40.);
        metrics.put("main_net_inflow", other ? -20000000. : 10000000.);
        metrics.forEach(
                (name, value) ->
                        values.put(Objects.requireNonNull(L2DailyFeatureField.named(name)), value));
        return new L2DailyFeatures(date, symbol, values);
    }

    // 独立手写期望值，不调用生产聚合器生成 golden；null+7 仍是 null，不能补零。
    private static RetailSentimentDaily aggregate(LocalDate date, double amount) {
        return new RetailSentimentDaily(
                date, .5, 1., amount, .1, .5, 6L, 3L, .125, 5L, 5L, 50., -.1);
    }

    private static RetailSentimentDaily nullAggregate(LocalDate date) {
        return new RetailSentimentDaily(
                date, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static RetailSentimentDaily single(LocalDate date) {
        return new RetailSentimentDaily(date, .25, 1., 1., .2, .25, 2L, 3L, .125, 5L, 5L, 40., .1);
    }

    private static List<Map<String, Object>> identity(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
    }

    private static void settled(JdbcTemplate jdbc, String table) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("夹具WAL未追平");
            Thread.sleep(200);
        }
    }

    private static void visible(L2DailyFeaturesWriteSession writer, List<L2DailyFeatures> expected)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        var keys = expected.stream().map(L2DailyFeatures::key).toList();
        while (!writer.walSettled()
                || !new HashSet<>(expected).equals(new HashSet<>(writer.readback(keys)))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("夹具完整键值未可见");
            Thread.sleep(200);
        }
    }

    private static void ready(RetailSentimentDailyMaterializationPort port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(100).toNanos();
        while (true) {
            try {
                var s = port.snapshot();
                if (s.valid() && s.caughtUp() && s.sourceSettled() && s.mvSettled()) return;
            } catch (IllegalStateException transientState) {
                if (!"D098 metadata changed during snapshot".equals(transientState.getMessage()))
                    throw transientState;
            }
            if (System.nanoTime() > deadline) throw new IllegalStateException("私有MV未在期限内稳定追平");
            Thread.sleep(200);
        }
    }

    private static List<String> prefixFiles(Path root) throws Exception {
        Path dir = root.resolve("sync-evidence");
        if (!Files.exists(dir)) return List.of();
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("retail-prefix-"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("缺少显式验收参数：" + name);
        return value;
    }

    private static void phase(Path root, Map<String, Object> report, String phase)
            throws Exception {
        report.put("phase", phase);
        save(root, report);
    }

    private static void save(Path root, Map<String, Object> report) throws Exception {
        FileEvidenceStore.replaceDurable(
                root.resolve("report.json"),
                JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(report));
    }

    private static void requireNoUnfinished(Path root) throws Exception {
        try (var runs = Files.list(root)) {
            var paths = runs.filter(Files::isDirectory).limit(101).toList();
            if (paths.size() > 100) throw new IllegalStateException("验收原件超过预算");
            for (Path dir : paths) {
                var json =
                        JobDefinitionJson.mapper()
                                .readTree(
                                        FileEvidenceStore.readBounded(
                                                dir.resolve("report.json"),
                                                1024 * 1024,
                                                () -> new IllegalStateException("原报告缺失或过大")));
                if (!List.of("VERIFIED_AND_CLEANED", "FAILED_BEFORE_DDL")
                        .contains(json.path("phase").asText())) {
                    Path receipt = dir.resolve("aborted-cleanup.json");
                    if (!Files.isRegularFile(receipt))
                        throw new IllegalStateException("存在原未完成验收，禁止新实例绕过：" + dir);
                    var closed =
                            JobDefinitionJson.mapper()
                                    .readTree(
                                            FileEvidenceStore.readBounded(
                                                    receipt,
                                                    1024 * 1024,
                                                    () -> new IllegalStateException("作废原件过大")));
                    if (!"ABORTED_UNREPRESENTABLE_INPUT_AND_CLEANED"
                                    .equals(closed.path("state").asText())
                            || !FileEvidenceStore.sha256(
                                            Files.readAllBytes(dir.resolve("report.json")))
                                    .equals(closed.path("originalReportSha256").asText()))
                        throw new IllegalStateException("作废清理原件未绑定原失败记录：" + dir);
                }
            }
        }
    }
}
