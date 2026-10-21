package com.zoutrankil.data.maintenance.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.config.EtfOverviewLayoutConfiguration;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.fund.EtfOverviewScope;
import com.zoutrankil.data.maintenance.storage.EtfOverviewRefreshJournal;
import com.zoutrankil.data.maintenance.storage.MaterializedViewCheckpoints;
import com.zoutrankil.data.maintenance.storage.MaterializedViewCheckpoints.Checkpoint;
import com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** 模拟 JDBC 配合真实 SQLite，验证新版绑定、原定义恢复和拒绝路径。 */
class EtfOverviewVersionedTest {
    @TempDir Path root;

    private static EtfOverviewScope scope(int share, int daily) {
        return new EtfOverviewScope(
                "java_d016_etf_share_fixture",
                share,
                "java_d014_etf_daily_fixture",
                daily,
                "java_etf_overview_v2_fixture");
    }

    @ParameterizedTest
    @CsvSource({"1,1", "1,2", "2,1", "2,2"})
    void bothSourcesHaveIndependentLayoutsAndVersionedReceipts(int share, int daily)
            throws Exception {
        var scope = scope(share, daily);
        var store = new Store(scope);
        var file = root.resolve("ledger.sqlite");
        var maintenance =
                new EtfOverviewMaintenance(store.jdbc, file, Duration.ofSeconds(1), scope);
        assertEquals(scope, EtfOverviewScope.fromDefinition(scope.definition()));
        String sql = EtfMarketOverviewMaterializedViewReadGuard.sql(scope);
        assertTrue(sql.contains("s." + scope.shareDate() + " = d." + scope.dailyDate()));
        assertEquals("VERIFIED", maintenance.refresh().get("status"));
        assertEquals(1, store.refreshes);
        assertEquals("CURRENT", maintenance.refresh().get("status"));
        assertEquals(1, store.refreshes);
        assertTrue(
                EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(
                                store.jdbc, file, scope)
                        .startsWith("etf-native:"));
        assertTrue(
                new EtfOverviewRefreshJournal(file)
                        .pending("java_etf_overview_v2_fixture@fixture-db")
                        .isEmpty());
        store.tables.get(scope.dailyTable()).put("table_txn", 8L);
        assertEquals("FULL", maintenance.refresh().get("mode"));
        assertEquals(2, store.refreshes);
    }

    @Test
    void unknownRefreshNeverAutomaticallyResendsAndUsesOriginalScopeAfterDefaultChanges()
            throws Exception {
        var original = scope(2, 1);
        var store = new Store(original);
        store.loseResponse = true;
        var file = root.resolve("ledger.sqlite");
        var maintenance =
                new EtfOverviewMaintenance(store.jdbc, file, Duration.ofSeconds(1), original);
        assertThrows(IllegalStateException.class, maintenance::refresh);
        var journal = new EtfOverviewRefreshJournal(file);
        var op = journal.pending(original.outputTable() + "@fixture-db").orElseThrow();
        String before = frozen(file, op.id());
        assertThrows(IllegalStateException.class, maintenance::refresh);
        assertThrows(IllegalStateException.class, maintenance::refreshIfInstalled);
        assertEquals(1, store.refreshes);
        assertThrows(IllegalArgumentException.class, () -> maintenance.reconcile(op.id(), false));
        var changedDefault =
                new EtfOverviewMaintenance(
                        store.jdbc, file, Duration.ofSeconds(1), EtfOverviewScope.LEGACY);
        assertEquals(
                "READ_ONLY_RECONCILIATION", changedDefault.reconcile(op.id(), true).get("mode"));
        assertEquals(before, frozen(file, op.id()));
        assertEquals("VERIFIED", journal.get(op.id()).state());
        assertEquals(1, store.refreshes);
    }

    @Test
    void changedSourceDoesNotRecertifyTheOriginalUnknownOperation() throws Exception {
        var scope = scope(2, 2);
        var store = new Store(scope);
        store.loseResponse = true;
        var file = root.resolve("ledger.sqlite");
        var maintenance =
                new EtfOverviewMaintenance(store.jdbc, file, Duration.ofSeconds(1), scope);
        assertThrows(IllegalStateException.class, maintenance::refresh);
        var journal = new EtfOverviewRefreshJournal(file);
        var op = journal.pending(scope.outputTable() + "@fixture-db").orElseThrow();
        String before = frozen(file, op.id());
        store.tables.get(scope.dailyTable()).put("table_txn", 9L);
        assertThrows(IllegalStateException.class, () -> maintenance.reconcile(op.id(), true));
        assertEquals(before, frozen(file, op.id()));
        assertEquals("IN_DOUBT", journal.get(op.id()).state());
        assertEquals(1, store.refreshes);
    }

    @Test
    void wrongDatePrecisionAndDuplicateKeysStopBeforeAnyDdlOrIntent() throws Exception {
        var scope = scope(2, 2);
        var store = new Store(scope);
        var file = root.resolve("ledger.sqlite");
        var maintenance =
                new EtfOverviewMaintenance(store.jdbc, file, Duration.ofSeconds(1), scope);
        store.wrongPrecision = true;
        assertThrows(IllegalStateException.class, maintenance::refresh);
        assertFalse(Files.exists(file));
        assertEquals(0, store.refreshes);
        store.wrongPrecision = false;
        store.duplicates = true;
        assertThrows(IllegalStateException.class, maintenance::refresh);
        assertFalse(Files.exists(file));
        assertEquals(0, store.refreshes);
        verify(store.jdbc, never()).execute(anyString());
    }

    @Test
    void sourceChangeDuringPreflightStopsBeforeIntentAndDdl() throws Exception {
        var scope = scope(2, 2);
        var store = new Store(scope);
        store.changeDuringBudget = true;
        var file = root.resolve("ledger.sqlite");
        assertThrows(
                IllegalStateException.class,
                () ->
                        new EtfOverviewMaintenance(store.jdbc, file, Duration.ofSeconds(1), scope)
                                .refresh());
        assertFalse(Files.exists(file));
        verify(store.jdbc, never()).execute(anyString());
    }

    @Test
    void configurationKeepsLegacyDefaultsAndMixedLayoutsAreExplicit() {
        var explicit = new StandardEnvironment();
        explicit.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "explicit",
                                Map.of("app.materialized-views.etf-overview.layout-version", 2)));
        assertFalse(EtfOverviewLayoutConfiguration.resolve(explicit).legacy());
        assertEquals(1, EtfOverviewLayoutConfiguration.resolve(explicit).shareVersion());
        var env = new StandardEnvironment();
        assertEquals(EtfOverviewScope.LEGACY, EtfOverviewLayoutConfiguration.resolve(env));
        env.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "fixture",
                                Map.of(
                                        "app.sync.etf-share-schema-version",
                                        2,
                                        "app.materialized-views.etf-overview.v2-output-table",
                                        "java_etf_overview_v2_selected")));
        var scope = EtfOverviewLayoutConfiguration.resolve(env);
        assertEquals(2, scope.shareVersion());
        assertEquals(1, scope.dailyVersion());
        assertEquals("java_d014_etf_daily_acceptance", scope.dailyTable());
        assertEquals(
                2,
                new EtfOverviewScope("etf_share", 2, "etf_daily", 1, "java_etf_overview_v2_x")
                        .shareVersion());
    }

    @Test
    void forgedGenericDefinitionCannotBypassTheJoinGuard() {
        var s = scope(2, 1);
        var d = s.definition();
        var forged =
                new DatasetDefinition(
                        "fake",
                        d.schemaVersion(),
                        d.provider(),
                        d.owner(),
                        d.objectName(),
                        d.objectKind(),
                        d.columns(),
                        d.businessKey(),
                        d.dedupKey(),
                        d.designatedTimestamp(),
                        d.partition(),
                        d.wal(),
                        d.capabilities(),
                        d.dependencies(),
                        d.storageRationale());
        assertThrows(IllegalArgumentException.class, () -> EtfOverviewScope.fromDefinition(forged));
    }

    @Test
    void oldReceiptNeedsIndependentDateProofAndIsNotResigned() throws Exception {
        var scope = scope(2, 2);
        var source = new Store(scope);
        var file = root.resolve("ledger.sqlite");
        var checkpoints = new MaterializedViewCheckpoints(file);
        String key = scope.outputTable() + "@fixture-db";
        var old =
                new Checkpoint(
                        EtfMarketOverviewMaterializedViewReadGuard.definitionHash(scope),
                        EtfMarketOverviewMaterializedViewReadGuard.snapshot(
                                source.jdbc, scope.shareTable(), scope),
                        EtfMarketOverviewMaterializedViewReadGuard.snapshot(
                                source.jdbc, scope.dailyTable(), scope),
                        EtfMarketOverviewMaterializedViewReadGuard.snapshot(
                                source.jdbc, scope.outputTable(), scope),
                        "refresh-0");
        checkpoints.save(key, old);
        String before;
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file);
                var q = db.createStatement();
                var r = q.executeQuery("SELECT verified_at FROM native_mv_checkpoints")) {
            assertTrue(r.next());
            before = r.getString(1);
        }
        assertThrows(
                IllegalStateException.class,
                () ->
                        EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(
                                source.jdbc, file, scope));
        assertEquals(
                "CURRENT",
                new EtfOverviewMaintenance(source.jdbc, file, Duration.ofSeconds(1), scope)
                        .refresh()
                        .get("status"));
        assertEquals(0, source.refreshes);
        assertEquals(old, checkpoints.find(key).orElseThrow());
        assertTrue(checkpoints.hasDateVerification(key, old));
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file);
                var q = db.createStatement();
                var r = q.executeQuery("SELECT verified_at FROM native_mv_checkpoints")) {
            assertTrue(r.next());
            assertEquals(before, r.getString(1));
        }
    }

    @Test
    void jdbcBusinessDateCarrierDoesNotShiftWithShanghaiDefaultZone() {
        var old = TimeZone.getDefault();
        try {
            for (String zone : List.of("Asia/Shanghai", "UTC", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var expected =
                        Map.<String, Object>of(
                                "trade_date",
                                LocalDate.of(2026, 10, 12),
                                "etf_count",
                                2L,
                                "total_share",
                                100.0,
                                "total_size_yi",
                                0.1);
                var actual = new HashMap<>(expected);
                actual.put("trade_date", Timestamp.valueOf("2026-10-12 00:00:00"));
                assertDoesNotThrow(
                        () ->
                                EtfOverviewMaintenance.requireParity(
                                        List.of(expected), List.of(actual)),
                        zone);
            }
        } finally {
            TimeZone.setDefault(old);
        }
    }

    private static String frozen(Path file, String id) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file);
                var q =
                        db.prepareStatement(
                                "SELECT scope_json||'|'||definition_json||'|'||definition_sql||'|'||left_version||'|'||right_version||'|'||output_before FROM native_etf_refresh_ops WHERE id=?")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }

    private static final class Store {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final Map<String, Map<String, Object>> tables = new HashMap<>();
        final Map<String, Object> view = new HashMap<>();
        final EtfOverviewScope scope;
        int refreshes;
        boolean loseResponse, wrongPrecision, duplicates, changeDuringBudget;

        Store(EtfOverviewScope scope) {
            this.scope = scope;
            when(jdbc.queryForObject("SELECT current_data_id()", String.class))
                    .thenReturn("fixture-db");
            int id = 0;
            for (String name :
                    List.of(scope.shareTable(), scope.dailyTable(), scope.outputTable())) {
                var t = new HashMap<String, Object>();
                t.put("id", ++id);
                t.put("directoryName", name + "~" + id);
                t.put("table_txn", 7L);
                t.put("sequencerTxn", 7L);
                t.put("writerTxn", 7L);
                t.put("walEnabled", true);
                t.put("table_suspended", false);
                t.put("suspended", false);
                t.put("wal_pending_row_count", 0L);
                t.put("bufferedTxnSize", 0L);
                tables.put(name, t);
            }
            view.put("base_table_name", scope.shareTable());
            view.put("refresh_type", "immediate");
            view.put("view_status", "valid");
            view.put("view_sql", EtfMarketOverviewMaterializedViewReadGuard.sql(scope));
            view.put("refresh_base_table_txn", 7L);
            view.put("base_table_txn", 7L);
            view.put("last_refresh_finish_timestamp", "refresh-0");
            when(jdbc.queryForList(anyString()))
                    .thenAnswer(
                            call -> {
                                String sql = call.getArgument(0);
                                if (sql.contains("materialized_views"))
                                    return List.of(new HashMap<>(view));
                                if (sql.contains("table_columns")) {
                                    boolean share = sql.contains(scope.shareTable());
                                    String date = share ? scope.shareDate() : scope.dailyDate();
                                    String type = share ? "TIMESTAMP" : "TIMESTAMP_NS";
                                    return List.of(
                                            Map.of(
                                                    "column",
                                                    date,
                                                    "type",
                                                    wrongPrecision ? "STRING" : type,
                                                    "upsertKey",
                                                    true,
                                                    "designated",
                                                    true),
                                            Map.of(
                                                    "column",
                                                    "ts_code",
                                                    "type",
                                                    "SYMBOL",
                                                    "upsertKey",
                                                    true,
                                                    "designated",
                                                    false),
                                            Map.of(
                                                    "column",
                                                    share ? "fd_share" : "close",
                                                    "type",
                                                    "DOUBLE",
                                                    "upsertKey",
                                                    false,
                                                    "designated",
                                                    false));
                                }
                                return List.of();
                            });
            when(jdbc.queryForList(anyString(), any(Object[].class)))
                    .thenAnswer(
                            call -> {
                                String sql = call.getArgument(0);
                                Object[] args = (Object[]) call.getRawArguments()[1];
                                if (sql.contains("FROM tables()"))
                                    return List.of(new HashMap<>(tables.get(args[0])));
                                if (sql.startsWith("SELECT * FROM (SELECT"))
                                    return duplicates ? List.of(Map.of("n", 2L)) : List.of();
                                if (sql.contains(" JOIN " + scope.dailyTable())
                                        || sql.contains("FROM " + scope.outputTable()))
                                    return List.of(
                                            Map.of(
                                                    "trade_date",
                                                    LocalDate.of(2026, 10, 12),
                                                    "etf_count",
                                                    2L,
                                                    "total_share",
                                                    100.0,
                                                    "total_size_yi",
                                                    0.1));
                                return List.of();
                            });
            when(jdbc.queryForMap(anyString()))
                    .thenReturn(
                            Map.of(
                                    "lo",
                                    LocalDate.of(2026, 10, 12),
                                    "hi",
                                    LocalDate.of(2026, 10, 12)));
            when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                    .thenAnswer(
                            call -> {
                                if (changeDuringBudget)
                                    tables.get(scope.dailyTable()).put("table_txn", 8L);
                                return 2L;
                            });
            doAnswer(
                            call -> {
                                String sql = call.getArgument(0);
                                if (sql.startsWith("REFRESH")) {
                                    refreshes++;
                                    view.put(
                                            "last_refresh_finish_timestamp",
                                            "refresh-" + refreshes);
                                    tables.get(scope.outputTable())
                                            .put("table_txn", 7L + refreshes);
                                    if (loseResponse)
                                        throw new IllegalStateException("已提交，客户端确认丢失");
                                }
                                return null;
                            })
                    .when(jdbc)
                    .execute(anyString());
        }
    }
}
