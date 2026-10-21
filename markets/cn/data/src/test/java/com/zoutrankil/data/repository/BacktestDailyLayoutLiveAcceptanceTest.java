package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.derived.storage.BacktestDailyViewReadRepository;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.data.domain.derived.BacktestDailyViewValue;
import com.zoutrankil.data.domain.stock.basic.StockStDailyDataset;
import com.zoutrankil.data.domain.stock.feature.StockFactorDataset;
import com.zoutrankil.data.domain.stock.market.StockLimitDataset;
import com.zoutrankil.data.domain.stock.market.StockSuspendDataset;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅在已核验的私有空库运行；失败保留原件，不重发、不自动清理。 */
@EnabledIfEnvironmentVariable(named = "BACKTEST_LAYOUT_LIVE_ACCEPTANCE", matches = "true")
class BacktestDailyLayoutLiveAcceptanceTest {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 14);
    private static final LocalDate TO = LocalDate.of(2026, 10, 17);
    private static final String A = "000001.SZ", B = "000002.SZ", C = "000003.SZ";

    @Test
    void nativeSourcesRevisionsSuspendedPricesAndTypedCursorsKeepTheFrozenLayout()
            throws Exception {
        Path marker = Path.of(System.getenv("BACKTEST_LAYOUT_FIXTURE")).toRealPath();
        byte[] original = Files.readAllBytes(marker);
        assertEquals(System.getenv("BACKTEST_LAYOUT_FIXTURE_SHA256"), sha(original));
        var fixture = JobDefinitionJson.mapper().readTree(original);
        Path repository = Path.of("").toRealPath();
        assertEquals(repository, Path.of(fixture.path("repository").asText()).toRealPath());
        Path evidence = marker.getParent();
        assertEquals(
                repository.resolve("var/cn-backtest-layout-live").toRealPath(),
                evidence.getParent());
        Path root = Path.of(fixture.path("root").asText()).toRealPath();
        assertEquals(evidence.resolve("database").toRealPath(), root);
        assertFalse(Files.exists(evidence.resolve("report.json")), "不能覆盖原验收结果");
        var scope =
                new BacktestDailyScope(
                        fixture.path("suspend").asText(),
                        fixture.path("suspendVersion").asInt(),
                        fixture.path("st").asText(),
                        fixture.path("stVersion").asInt(),
                        fixture.path("output").asText());
        var attestor =
                new PrivateQuestDbInstanceAttestor(
                        new PrivateQuestDbInstanceAttestor.Spec(
                                "BACKTEST_LAYOUT", repository.resolve("var"), root, 18862, 19050),
                        checked -> {
                            if (!checked.equals(root)
                                    || !Arrays.equals(original, Files.readAllBytes(marker)))
                                throw new IOException("私有 fixture 或目录变化");
                        });
        var report = new LinkedHashMap<String, Object>();
        report.put("state", "PREPARED");
        report.put("scope", scope);
        report.put("fixtureSha256", sha(original));
        report.put("sourceCoverageVerified", false);
        report.put("formalMigrationExecuted", false);
        var stages = new ArrayList<Map<String, Object>>();
        report.put("stages", stages);
        save(evidence, report);
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:18862/qdb", "admin", "quest"));
        jdbc.setQueryTimeout(20);
        var store = new Sources(jdbc, scope, attestor);
        Path ledger = evidence.resolve("not-created.sqlite");
        try {
            attestor.attest();
            assertEquals(0L, jdbc.queryForObject("SELECT count() FROM tables()", Long.class));
            report.put("build", jdbc.queryForList("SELECT build"));
            for (var d : store.definitions) jdbc.execute(sourceDdl(d));
            store.price("2026-10-14", A, 10.0, 100.0, 1000.0, 1.0);
            store.price("2026-10-14", B, 20.0, 200.0, 4000.0, 2.0);
            store.price("2026-10-15", A, 11.0, null, 1100.0, null);
            store.flag(scope.suspendTable(), scope.suspendDate(), "is_suspended", "2026-10-15", B);
            store.flag(scope.stTable(), scope.stDate(), "is_st", "2026-10-15", B);
            store.settled();
            store.limit("2026-10-14", A, 11.0, 9.0);
            store.limit("2026-10-14", B, 22.0, 18.0);
            // 当日涨跌停缺 A 行，不能补前一日上限；B 行仍提供该交易日。
            store.limit("2026-10-15", B, 22.0, 18.0);
            store.settled();
            attestor.attest();
            String sql = BacktestDailyMaterializedViewPort.createSql(scope);
            report.put("createSql", sql);
            report.put("definition", scope.definition());
            jdbc.execute(sql);
            var port = new BacktestDailyMaterializedViewPort(jdbc, scope);
            var reader =
                    new BacktestDailyViewReadRepository(
                            new QuestDbBoundedReader(jdbc, ledger.toString()), scope);
            var expected =
                    new ArrayList<BacktestDailyViewValue>(
                            List.of(
                                    row(
                                            "2026-10-14",
                                            A,
                                            10.0,
                                            100.0,
                                            1000.0,
                                            1.0,
                                            11.0,
                                            9.0,
                                            0L,
                                            0),
                                    row(
                                            "2026-10-14",
                                            B,
                                            20.0,
                                            200.0,
                                            4000.0,
                                            2.0,
                                            22.0,
                                            18.0,
                                            0L,
                                            0),
                                    row(
                                            "2026-10-15",
                                            A,
                                            11.0,
                                            null,
                                            1100.0,
                                            null,
                                            null,
                                            null,
                                            0L,
                                            0),
                                    row("2026-10-15", B, 20.0, 0.0, 0.0, 2.0, 22.0, 18.0, 1L, 1)));
            stages.add(verify("initial", port, reader, store, expected));
            var initialPage = reader.findRange(FROM, TO, 2, null);
            assertNotNull(initialPage.nextCursor());
            report.put("initialCursor", initialPage.nextCursor());
            save(evidence, report);

            // 先齐关联来源，再提交基表；新证券不能反向填进尚无价格的历史行。
            store.price("2026-10-16", A, 12.0, 100.0, 1200.0, 1.0);
            store.price("2026-10-16", C, 30.0, 300.0, 9000.0, 3.0);
            store.flag(scope.suspendTable(), scope.suspendDate(), "is_suspended", "2026-10-16", B);
            store.flag(scope.stTable(), scope.stDate(), "is_st", "2026-10-16", B);
            store.settled();
            assertStale(reader, stages, "appendBeforeBase");
            store.limit("2026-10-16", A, 13.2, 10.8);
            store.limit("2026-10-16", B, 22.0, 18.0);
            store.limit("2026-10-16", C, 33.0, 27.0);
            store.settled();
            expected.addAll(
                    List.of(
                            row("2026-10-16", A, 12.0, 100.0, 1200.0, 1.0, 13.2, 10.8, 0L, 0),
                            row("2026-10-16", B, 20.0, 0.0, 0.0, 2.0, 22.0, 18.0, 1L, 1),
                            row("2026-10-16", C, 30.0, 300.0, 9000.0, 3.0, 33.0, 27.0, 0L, 0)));
            stages.add(verify("append", port, reader, store, expected));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reader.findRange(FROM, TO, 2, initialPage.nextCursor()));
            report.put("oldCursorRejected", true);
            save(evidence, report);

            // 价格修订不能凭旧 MV 基表水位宣称已刷新。
            store.price("2026-10-16", A, 12.5, 100.0, 1250.0, 1.0);
            store.settled();
            assertStale(reader, stages, "priceRevisionBeforeBase");
            store.limit("2026-10-16", A, 13.75, 10.8);
            store.settled();
            expected.set(4, row("2026-10-16", A, 12.5, 100.0, 1250.0, 1.0, 13.75, 10.8, 0L, 0));
            stages.add(verify("priceRevision", port, reader, store, expected));
            save(evidence, report);

            // 旧价格修订须影响后续停牌沿用价；原样本金额单位不换算。
            store.price("2026-10-14", B, 21.0, 200.0, 4200.0, 2.0);
            store.settled();
            assertStale(reader, stages, "historyRevisionBeforeBase");
            store.limit("2026-10-14", B, 23.1, 18.9);
            store.settled();
            expected.set(1, row("2026-10-14", B, 21.0, 200.0, 4200.0, 2.0, 23.1, 18.9, 0L, 0));
            expected.set(3, row("2026-10-15", B, 21.0, 0.0, 0.0, 2.0, 22.0, 18.0, 1L, 1));
            expected.set(5, row("2026-10-16", B, 21.0, 0.0, 0.0, 2.0, 22.0, 18.0, 1L, 1));
            port.awaitCurrent(Duration.ofSeconds(45));
            var valuesChanged =
                    assertThrows(
                            BacktestDailyMaterializationReadGuard.NotReadyException.class,
                            () -> reader.findRange(FROM, TO, 3, null));
            assertEquals(
                    BacktestDailyMaterializationReadGuard.NotReadyException.Reason.VALUES_CHANGED,
                    valuesChanged.reason());
            stages.add(
                    Map.of(
                            "name",
                            "historyAfterBaseBeforeFull",
                            "readRefused",
                            true,
                            "reason",
                            valuesChanged.reason().name(),
                            "staleRows",
                            jdbc.queryForList(
                                    "SELECT cast(trade_date AS long) AS trade_date,ts_code,close FROM "
                                            + scope.outputTable()
                                            + " ORDER BY trade_date,ts_code")));
            save(evidence, report);
            // 仅本机空库夹具显式 FULL；生产读取保护不发送刷新，也不把普通重试变成 FULL。
            attestor.attest();
            String fullSql = "REFRESH MATERIALIZED VIEW " + scope.outputTable() + " FULL";
            report.put("fixtureFullIntent", fullSql);
            save(evidence, report);
            jdbc.execute(fullSql);
            report.put("fixtureFullSubmitted", true);
            save(evidence, report);
            long fullDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (true) {
                var observed =
                        jdbc.queryForList(
                                "SELECT close FROM "
                                        + scope.outputTable()
                                        + " WHERE trade_date='2026-10-15' AND ts_code='000002.SZ'");
                if (observed.size() == 1
                        && Double.valueOf(21.0).equals(observed.getFirst().get("close"))) break;
                if (observed.size() > 1) throw new IllegalStateException("FULL 后业务键重复");
                if (System.nanoTime() >= fullDeadline)
                    throw new IllegalStateException("显式 FULL 未完成停牌沿用价重算");
                Thread.sleep(25);
            }
            report.put("fixtureExplicitFull", true);
            stages.add(verify("historyRevision", port, reader, store, expected));
            save(evidence, report);

            store.flag(scope.stTable(), scope.stDate(), "is_st", "2026-10-15", A);
            store.settled();
            assertStale(reader, stages, "stRevisionBeforeBase");
            store.limit("2026-10-15", B, 23.0, 17.0);
            store.settled();
            expected.set(2, row("2026-10-15", A, 11.0, null, 1100.0, null, null, null, 0L, 1));
            expected.set(3, row("2026-10-15", B, 21.0, 0.0, 0.0, 2.0, 23.0, 17.0, 1L, 1));
            stages.add(verify("stRevision", port, reader, store, expected));
            save(evidence, report);

            // 当日有真实价格时，停牌记录不让原算法把成交量和金额强制清零。
            store.flag(scope.suspendTable(), scope.suspendDate(), "is_suspended", "2026-10-14", B);
            store.settled();
            assertStale(reader, stages, "suspensionRevisionBeforeBase");
            store.limit("2026-10-14", B, 25.0, 15.0);
            store.settled();
            expected.set(1, row("2026-10-14", B, 21.0, 200.0, 4200.0, 2.0, 25.0, 15.0, 1L, 0));
            stages.add(verify("suspensionRevision", port, reader, store, expected));
            assertFalse(Files.exists(ledger), "原生回测读取不能创建发布账本");
            report.put("ledgerCreated", false);
            report.put("state", "VERIFIED_BEFORE_CLEANUP");
            save(evidence, report);
            attestor.attest();
            jdbc.execute("DROP MATERIALIZED VIEW " + scope.outputTable());
            for (var d : store.definitions) jdbc.execute("DROP TABLE " + d.objectName());
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

    private static void assertStale(
            BacktestDailyViewReadRepository reader, List<Map<String, Object>> stages, String name) {
        var refused =
                assertThrows(
                        BacktestDailyMaterializationReadGuard.NotReadyException.class,
                        () -> reader.findRange(FROM, TO, 3, null));
        assertEquals(
                BacktestDailyMaterializationReadGuard.NotReadyException.Reason.SOURCES_CHANGED,
                refused.reason());
        stages.add(Map.of("name", name, "readRefused", true, "reason", refused.reason().name()));
    }

    /** 每一阶段比较全部键、13 列及完整来源字段，前后仍是同一版本。 */
    private static Map<String, Object> verify(
            String name,
            BacktestDailyMaterializedViewPort port,
            BacktestDailyViewReadRepository reader,
            Sources sources,
            List<BacktestDailyViewValue> expected)
            throws Exception {
        port.awaitCurrent(Duration.ofSeconds(45));
        var before = port.sourceState();
        sources.verify();
        var actual = new ArrayList<BacktestDailyViewValue>();
        var pages = new ArrayList<Integer>();
        DatasetReadCursor cursor = null;
        do {
            var page = reader.findRange(FROM, TO, 3, cursor);
            actual.addAll(page.rows());
            pages.add(page.rows().size());
            cursor = page.nextCursor();
        } while (cursor != null && pages.size() < 5);
        assertEquals(null, cursor);
        assertEquals(expected, actual);
        assertEquals(
                (long) expected.size(),
                sources.jdbc.queryForObject(
                        "SELECT count() FROM " + sources.scope.outputTable(), Long.class));
        assertEquals(before, port.sourceState());
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("verifiedRows", actual.size());
        result.put("pages", pages);
        result.put("rows", actual);
        result.put("sourceState", before);
        result.put("sources", JobDefinitionJson.mapper().valueToTree(sources.expected));
        result.put(
                "sourceMetadata",
                sources.jdbc.queryForList(
                        "SELECT table_name,id,directoryName,designatedTimestamp,partitionBy,walEnabled,dedup FROM tables() ORDER BY table_name"));
        return result;
    }

    private static BacktestDailyViewValue row(
            String date,
            String code,
            double price,
            Double vol,
            Double amount,
            Double factor,
            Double up,
            Double down,
            long suspended,
            int st) {
        return new BacktestDailyViewValue(
                LocalDate.parse(date),
                code,
                price,
                price,
                price,
                price,
                vol,
                amount,
                factor,
                up,
                down,
                suspended,
                st);
    }

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

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void save(Path folder, Map<String, Object> report) throws Exception {
        Files.writeString(
                folder.resolve("report.json"),
                JobDefinitionJson.mapper().writeValueAsString(report));
    }

    private static final class Sources {
        final JdbcTemplate jdbc;
        final BacktestDailyScope scope;
        final PrivateQuestDbInstanceAttestor attestor;
        final List<DatasetDefinition> definitions;
        final Map<String, Map<String, Map<String, Object>>> expected = new LinkedHashMap<>();

        Sources(
                JdbcTemplate jdbc,
                BacktestDailyScope scope,
                PrivateQuestDbInstanceAttestor attestor) {
            this.jdbc = jdbc;
            this.scope = scope;
            this.attestor = attestor;
            definitions =
                    List.of(
                            StockFactorDataset.DEFINITION,
                            StockLimitDataset.DEFINITION,
                            StockSuspendDataset.definition(
                                    scope.suspendTable(),
                                    CnTradeDateLayout.fromVersion(scope.suspendVersion())),
                            StockStDailyDataset.definition(
                                    scope.stTable(),
                                    CnTradeDateLayout.fromVersion(scope.stVersion())));
            definitions.forEach(d -> expected.put(d.objectName(), new LinkedHashMap<>()));
        }

        void price(
                String date, String code, double price, Double volume, Double amount, Double adj) {
            var values = new LinkedHashMap<String, Object>();
            values.put("open", price);
            values.put("high", price);
            values.put("low", price);
            values.put("close", price);
            values.put("vol", volume);
            values.put("amount", amount);
            values.put("adj_factor", adj);
            insert("stk_factor", "trade_date", date, code, values);
        }

        void limit(String date, String code, double up, double down) {
            insert(
                    "stk_limit",
                    "trade_date",
                    date,
                    code,
                    Map.of("up_limit", up, "down_limit", down));
        }

        void flag(String table, String dateColumn, String flag, String date, String code) {
            Object value = flag.equals("is_st") ? (Object) Integer.valueOf(1) : Long.valueOf(1);
            insert(table, dateColumn, date, code, Map.of(flag, value));
        }

        void insert(
                String table,
                String dateColumn,
                String date,
                String code,
                Map<String, Object> fields) {
            attestor.attest();
            var columns = new ArrayList<>(List.of(dateColumn, "ts_code"));
            columns.addAll(fields.keySet());
            var values = new ArrayList<>(List.of("'" + date + "'", "'" + code + "'"));
            for (Object value : fields.values())
                values.add(value == null ? "null" : value.toString());
            jdbc.execute(
                    "INSERT INTO "
                            + table
                            + " ("
                            + String.join(",", columns)
                            + ") VALUES ("
                            + String.join(",", values)
                            + ")");
            var row = new LinkedHashMap<String, Object>();
            definitions.stream()
                    .filter(d -> d.objectName().equals(table))
                    .findFirst()
                    .orElseThrow()
                    .columns()
                    .forEach(c -> row.put(c.storageName(), null));
            row.putAll(fields);
            row.put("ts_code", code);
            row.put(dateColumn, LocalDate.parse(date).toEpochDay() * 86_400_000_000L);
            expected.get(table).put(date + ":" + code, row);
        }

        void settled() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (true) {
                if (definitions.stream()
                        .allMatch(d -> QuestDbWriteChecks.walSettled(jdbc, d.objectName()))) return;
                if (System.nanoTime() >= deadline) throw new IllegalStateException("来源 WAL 未应用完成");
                Thread.sleep(25);
            }
        }

        void verify() {
            attestor.attest();
            for (var d : definitions) {
                QuestDbWriteChecks.preflight(jdbc, d.objectName(), d);
                var actual =
                        jdbc.queryForList(
                                "SELECT "
                                        + String.join(
                                                ",",
                                                d.columns().stream()
                                                        .map(
                                                                c ->
                                                                        c.storageName()
                                                                                        .equals(
                                                                                                d
                                                                                                        .designatedTimestamp())
                                                                                ? "cast("
                                                                                        + c
                                                                                                .storageName()
                                                                                        + " AS long) AS "
                                                                                        + c
                                                                                                .storageName()
                                                                                : "\""
                                                                                        + c
                                                                                                .storageName()
                                                                                        + "\"")
                                                        .toList())
                                        + " FROM "
                                        + d.objectName()
                                        + " ORDER BY "
                                        + d.designatedTimestamp()
                                        + ",ts_code");
                var keyed = new HashMap<String, Map<String, Object>>();
                for (var row : actual) {
                    var date =
                            LocalDate.ofEpochDay(
                                    ((Number) row.get(d.designatedTimestamp())).longValue()
                                            / 86_400_000_000L);
                    assertEquals(null, keyed.put(date + ":" + row.get("ts_code"), row));
                }
                assertEquals(expected.get(d.objectName()), keyed);
            }
        }
    }
}
