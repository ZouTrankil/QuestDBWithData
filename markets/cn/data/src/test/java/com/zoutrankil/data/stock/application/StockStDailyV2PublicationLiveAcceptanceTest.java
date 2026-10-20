package com.zoutrankil.data.stock.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.basic.StockStDaily;
import com.zoutrankil.data.domain.stock.basic.StockStDailyDataset;
import com.zoutrankil.data.domain.stock.basic.StockStDailyKey;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.stock.domain.StockStDailyState;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.data.stock.mapper.StockStDailyMapper;
import com.zoutrankil.data.stock.port.StockDateWriteSession;
import com.zoutrankil.data.stock.port.StockStDailyStagingPort;
import com.zoutrankil.data.stock.port.StockStDailyTables;
import com.zoutrankil.data.stock.port.StockStDailyTarget;
import com.zoutrankil.data.stock.storage.QuestDbStockStDailyTarget;
import com.zoutrankil.data.stock.storage.StockStDailyReadRepository;
import com.zoutrankil.data.stock.storage.StockStDailyStorage;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只用已知完整的两行隔离夹具；真实日历证明闭市，不冒充真实名称变更历史验收。 */
@EnabledIfEnvironmentVariable(named = "D012_PUBLICATION_V2_LIVE_ACCEPTANCE", matches = "true")
class StockStDailyV2PublicationLiveAcceptanceTest {
    private static final CnTradeDateLayout LAYOUT = CnTradeDateLayout.TRADE_DATE_V2;
    private static final LocalDate CLOSED = LocalDate.of(2026, 9, 26);

    @Test
    void interruptedRealRenameRetainsTheWindowLeaseThenRecoversFromTheOriginalV2Journal()
            throws Exception {
        Path root =
                Path.of(
                                System.getenv()
                                        .getOrDefault(
                                                "D012_PUBLICATION_EVIDENCE_ROOT",
                                                "var/st-publication-v2-acceptance"))
                        .toAbsolutePath();
        Files.createDirectories(root);
        requireNoUnfinished(root);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Path evidence = Files.createDirectory(root.resolve(suffix));
        String table = "java_d012_stk_st_daily_pub2_" + suffix;
        String run = "stk-st-v2-live-" + UUID.randomUUID();
        Path ledgerPath = evidence.resolve("ledger.sqlite3");
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1);
        report.put("observedAt", Instant.now());
        report.put("table", table);
        report.put("runId", run);
        report.put("scope", "isolated-two-row-closed-session-fixture");
        report.put("productionMigration", false);
        report.put("realNamechangeRequests", 0);
        report.put("state", "PREPARED");
        save(evidence, report);
        boolean ddlAttempted = false;
        try {
            var env = ProjectConfiguration.load("data");
            var db = Binder.get(env).bind("app.questdb", QuestDbProperties.class).get();
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    env.getRequiredProperty("spring.datasource.url"),
                                    db.getUsername(),
                                    db.getPassword()));
            jdbc.setQueryTimeout(30);
            var reader = new QuestDbBoundedReader(jdbc, ledgerPath.toString());
            var calendarBefore = state(jdbc, "exchange_calendar");
            var calendar = new StockStTradingDates(new ExchangeCalendarReadRepository(reader));
            assertEquals(List.of(), calendar.read(CLOSED, CLOSED));
            report.put("closedSession", CLOSED);
            report.put("calendarState", calendarBefore);
            save(evidence, report);
            // 夹具故意放一行闭市日旧正记录，用来验证撤销；两行都不是正式市场记录。
            var outside = new StockStDaily("000001.SZ", CLOSED.minusDays(1), 1);
            var revoked = new StockStDaily("000002.SZ", CLOSED, 1);
            var fixture = List.of(outside, revoked);
            FileEvidenceStore.writeNewDurable(
                    evidence.resolve("fixture.json"),
                    JobDefinitionJson.mapper().writeValueAsBytes(fixture));
            report.put("state", "DDL_INTENT_SAVED");
            save(evidence, report);
            ddlAttempted = true;
            jdbc.execute(StockStDailyDataset.createIsolatedTableSql(table, LAYOUT));
            StockStDailyPublication.Entry entry;
            StockStDailyState.Snapshot recovered;
            String logical;
            try (var qdb = QuestDB.connect(db.qwpConfig())) {
                var base = new QuestDbStockStDailyTarget(table, jdbc, qdb, LAYOUT);
                var target = new InterruptedTarget(base);
                String physical = target.physicalTargetId();
                logical = target.targetId();
                report.put("physicalBefore", physical);
                report.put("logicalTarget", logical);
                report.put("state", "TARGET_FROZEN");
                save(evidence, report);
                var writer = target.newWriter(physical);
                writer.send(fixture);
                visible(writer, fixture);
                var before = target.openTable().snapshot();
                report.put("before", before);
                report.put("state", "FIXTURE_VERIFIED");
                save(evidence, report);
                var request =
                        StockStDailySyncJobOwner.definition(2)
                                .freeze(
                                        Mode.BACKFILL,
                                        Map.of(
                                                "targetId",
                                                logical,
                                                "physicalTargetId",
                                                physical,
                                                "trade_dates",
                                                "NONE"),
                                        CLOSED,
                                        CLOSED,
                                        LocalDate.of(2026, 10, 21));
                FileEvidenceStore.writeNewDurable(
                        evidence.resolve("request.json"),
                        JobDefinitionJson.mapper().writeValueAsBytes(request));
                var pages = mock(TusharePageService.class);
                var result =
                        SyncRunExecution.execute(
                                ledgerPath,
                                run,
                                null,
                                logical,
                                request,
                                "无法读取验收取消状态",
                                () ->
                                        new StockStDailySyncAdapter(
                                                new StockStDailySource(
                                                        pages,
                                                        new StockStDailyMapper(),
                                                        evidence.resolve("sync-evidence")
                                                                .resolve(run)
                                                                .resolve("source")),
                                                calendar,
                                                target.newWriter(physical),
                                                evidence.resolve("sync-evidence").resolve(run),
                                                run,
                                                ledgerPath,
                                                table,
                                                logical,
                                                target));
                assertEquals(SyncRunState.IN_DOUBT, result.state());
                var ledger = SyncRunLedger.openReadOnly(ledgerPath);
                String frozen = ledger.getRun(run).frozenJson();
                var locks = new DatasetIntervalLock(ledgerPath);
                var scope = new DatasetIntervalLock.Scope("stk_st_daily", CLOSED, CLOSED);
                assertNotNull(locks.findOwned(run, scope));
                assertTrue(locks.findOwned(run, scope).inDoubt());
                var publisher =
                        new StockStDailyPublication(
                                target.newPublicationTables(), ledgerPath, LAYOUT);
                entry = publisher.forRun(run);
                assertEquals(StockStDailyPublication.State.IN_DOUBT, entry.state());
                assertEquals(1, target.tables.renames);
                assertTrue(state(jdbc, table).isEmpty());
                assertFalse(state(jdbc, entry.intent().backup()).isEmpty());
                assertFalse(state(jdbc, entry.intent().stage()).isEmpty());
                assertThrows(IllegalStateException.class, publisher::requireNoPendingPublication);
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new StockStDailyPublication(
                                                ((StockStDailyTarget.Versioned) base)
                                                        .forVersion(1)
                                                        .newPublicationTables(),
                                                ledgerPath)
                                        .forRun(run));
                report.put("state", "INTERRUPTED_WITH_LOCKS_RETAINED");
                report.put("publication", entry);
                save(evidence, report);
                // 同步调用已返回、夹具 Sender 已关闭，故障只丢失 rename 确认；恢复不发送原行。
                target.tables.failAfterFirst = false;
                var finished =
                        StockStDailyRunRecovery.finishInterrupted(target, ledgerPath, run, true);
                assertEquals(SyncRunState.VERIFIED_EMPTY, finished.state());
                assertEquals(frozen, ledger.getRun(run).frozenJson());
                assertEquals(2, target.tables.renames);
                assertNull(locks.findOwned(run, scope));
                publisher.requireNoPendingPublication();
                entry = publisher.forRun(run);
                assertEquals(StockStDailyPublication.State.VERIFIED, entry.state());
                var typed = new StockStDailyReadRepository(reader, table, LAYOUT);
                assertEquals(List.of(outside), typed.findByKey(outside.key()).rows());
                assertTrue(typed.findByKey(revoked.key()).rows().isEmpty());
                recovered = target.openTable().snapshot();
                assertEquals(1, recovered.content().rows());
                assertEquals(
                        before.content(),
                        new StockStDailyStorage(jdbc, entry.intent().backup(), LAYOUT)
                                .snapshot()
                                .content());
                assertEquals(logical, target.targetId());
                assertNotEquals(physical, target.physicalTargetId());
                assertTrue(state(jdbc, entry.intent().stage()).isEmpty());
                assertEquals(calendarBefore, state(jdbc, "exchange_calendar"));
                verifyNoInteractions(pages);
                report.put("after", recovered);
                report.put("publication", entry);
                report.put("state", "RECOVERY_VERIFIED");
                save(evidence, report);
            }
            // 客户端已停；只清理本测试日志明确拥有、身份和全值仍相符的两个对象。
            requireOwned(jdbc, table, entry.intent().stageIdentity());
            requireOwned(jdbc, entry.intent().backup(), entry.intent().beforeIdentity());
            assertEquals(
                    recovered.content(),
                    new StockStDailyStorage(jdbc, table, LAYOUT).snapshot().content());
            assertEquals(
                    entry.intent().before(),
                    new StockStDailyStorage(jdbc, entry.intent().backup(), LAYOUT)
                            .snapshot()
                            .content());
            jdbc.execute("DROP TABLE " + table);
            jdbc.execute("DROP TABLE " + entry.intent().backup());
            assertTrue(state(jdbc, table).isEmpty());
            assertTrue(state(jdbc, entry.intent().backup()).isEmpty());
            report.put("state", "VERIFIED_AND_CLEANED");
            report.put("renames", 2);
            report.put("outsideRowsPreserved", 1);
            report.put("closedSessionRowsRemoved", 1);
            report.put("namechangeHistoryAccepted", false);
            save(evidence, report);
        } catch (Exception | AssertionError failure) {
            report.put("failedAt", report.get("state"));
            report.put("state", ddlAttempted ? "INCOMPLETE_PRESERVED" : "FAILED_BEFORE_DDL");
            report.put("failureType", failure.getClass().getName());
            if (failure instanceof AssertionError)
                report.put("assertionMessage", failure.getMessage());
            save(evidence, report);
            fail("ST v2 发布验收未完成；原表和原件保留：" + evidence + "；类型：" + failure.getClass().getSimpleName());
        }
    }

    private static void visible(
            StockDateWriteSession<StockStDaily, StockStDailyKey> writer, List<StockStDaily> rows)
            throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!writer.walSettled()
                || !rows.equals(writer.readback(rows.stream().map(StockStDaily::key).toList()))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("验收夹具完整键值不可见");
            Thread.sleep(100);
        }
    }

    private static List<Map<String, Object>> state(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList(
                "SELECT id,directoryName,table_txn FROM tables() WHERE table_name=?", table);
    }

    private static void requireOwned(
            JdbcTemplate jdbc, String table, StockStDailyState.Identity expected) throws Exception {
        var actual = new StockStDailyStorage(jdbc, table, LAYOUT).preflight();
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.directory(), actual.directory());
        assertTrue(QuestDbWriteChecks.walSettled(jdbc, table));
    }

    private static void requireNoUnfinished(Path root) throws Exception {
        try (var paths = Files.list(root)) {
            var runs = paths.filter(Files::isDirectory).limit(101).toList();
            if (runs.size() > 100) throw new IllegalStateException("验收历史超过上限");
            for (var run : runs) {
                var bytes =
                        FileEvidenceStore.readBounded(
                                run.resolve("report.json"),
                                1024 * 1024,
                                () -> new IllegalStateException("原报告不可核对"));
                String state = JobDefinitionJson.mapper().readTree(bytes).path("state").asText();
                if (!List.of("VERIFIED_AND_CLEANED", "FAILED_BEFORE_DDL").contains(state))
                    throw new IllegalStateException("先核对原验收与原表：" + run);
            }
        }
    }

    private static void save(Path root, Map<String, Object> report) throws Exception {
        FileEvidenceStore.writeNewDurable(
                root.resolve("report-" + UUID.randomUUID() + ".json"),
                JobDefinitionJson.mapper().writeValueAsBytes(report));
        FileEvidenceStore.replaceDurable(
                root.resolve("report.json"),
                JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(report));
    }

    private static final class InterruptedTables implements StockStDailyTables.Versioned {
        final StockStDailyTables base;
        int renames;
        boolean failAfterFirst = true;

        InterruptedTables(StockStDailyTables base) {
            this.base = base;
        }

        public int datasetVersion() {
            return 2;
        }

        public StockStDailyState.Snapshot snapshot(String table) throws Exception {
            return base.snapshot(table);
        }

        public StockStDailyState.Snapshot snapshotIfPresent(String table) throws Exception {
            return base.snapshotIfPresent(table);
        }

        public String targetId(String table, StockStDailyState.Identity identity) {
            return base.targetId(table, identity);
        }

        public void rename(String from, String to) {
            base.rename(from, to);
            renames++;
            if (failAfterFirst && renames == 1)
                throw new IllegalStateException("验收故障：rename 已执行但确认丢失");
        }
    }

    private static final class InterruptedTarget implements StockStDailyTarget.Versioned {
        final StockStDailyTarget base;
        final InterruptedTables tables;

        InterruptedTarget(StockStDailyTarget base) {
            this.base = base;
            tables = new InterruptedTables(base.newPublicationTables());
        }

        public int datasetVersion() {
            return 2;
        }

        public StockStDailyTarget forVersion(int version) {
            return version == 2 ? this : ((StockStDailyTarget.Versioned) base).forVersion(version);
        }

        public String tableName() {
            return base.tableName();
        }

        public String targetId() {
            return base.targetId();
        }

        public String physicalTargetId() {
            return base.physicalTargetId();
        }

        public StockTargetRange range() {
            return base.range();
        }

        public StockDateWriteSession<StockStDaily, StockStDailyKey> newWriter(String id) {
            return base.newWriter(id);
        }

        public StockDateWriteSession<StockStDaily, StockStDailyKey> stageWriter(
                String table, String id) {
            return base.stageWriter(table, id);
        }

        public StockStDailyTables newPublicationTables() {
            return tables;
        }

        public StockStDailyStagingPort newStaging() {
            return base.newStaging();
        }

        public StockStDailyTables.Table openTable() {
            return base.openTable();
        }

        public StockStDailyState.Content fingerprintWindow(
                List<StockStDaily> rows, LocalDate from, LocalDate to) throws Exception {
            return base.fingerprintWindow(rows, from, to);
        }
    }
}
