package com.zoutrankil.data.maintenance.application;

import static com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard.OUTPUT;
import static com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard.SQL;
import static com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.maintenance.storage.MaterializedViewCheckpoints;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class EtfOverviewMaintenanceTest {
    @TempDir Path root;

    private static Map<String, Object> row(double size) {
        return Map.of(
                "trade_date",
                LocalDate.of(2026, 10, 12),
                "etf_count",
                2L,
                "total_share",
                100.0,
                "total_size_yi",
                size);
    }

    private static final class Store {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final Map<String, Map<String, Object>> tables = new HashMap<>();
        final Map<String, Object> view = new HashMap<>();
        double expectedSize = 10, actualSize = 10;
        boolean changeRightDuringParity;
        int refreshes;

        Store() {
            when(jdbc.queryForObject("SELECT current_data_id()", String.class))
                    .thenReturn("fixture-db");
            int id = 0;
            for (String name : List.of("etf_share", "etf_daily", OUTPUT)) {
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
            view.put("base_table_name", "etf_share");
            view.put("refresh_type", "immediate");
            view.put("view_status", "valid");
            view.put("view_sql", SQL);
            view.put("refresh_base_table_txn", 7L);
            view.put("base_table_txn", 7L);
            view.put("last_refresh_finish_timestamp", "refresh-0");
            when(jdbc.queryForList(anyString()))
                    .thenAnswer(
                            call -> {
                                String sql = call.getArgument(0);
                                if (sql.contains("materialized_views()")) return List.of(view);
                                if (sql.contains("views()"))
                                    return List.of(
                                            Map.of(
                                                    "view_sql",
                                                    "SELECT * FROM " + OUTPUT,
                                                    "view_status",
                                                    "valid",
                                                    "view_table_dir_name",
                                                    "alias~1"));
                                throw new AssertionError(sql);
                            });
            when(jdbc.queryForList(anyString(), any(Object[].class)))
                    .thenAnswer(
                            call -> {
                                String sql = call.getArgument(0);
                                Object[] args = (Object[]) call.getRawArguments()[1];
                                if (sql.contains("FROM tables()"))
                                    return List.of(tables.get(args[0]));
                                if (sql.contains("JOIN etf_daily"))
                                    return List.of(row(expectedSize));
                                if (sql.contains("FROM " + OUTPUT)) {
                                    if (changeRightDuringParity)
                                        tables.get("etf_daily").put("table_txn", 99L);
                                    return List.of(row(actualSize));
                                }
                                throw new AssertionError(sql);
                            });
            when(jdbc.queryForMap(anyString()))
                    .thenReturn(
                            Map.of(
                                    "lo",
                                    LocalDate.of(2026, 10, 12),
                                    "hi",
                                    LocalDate.of(2026, 10, 12)));
            doAnswer(
                            call -> {
                                String sql = call.getArgument(0);
                                if (!sql.startsWith("REFRESH MATERIALIZED VIEW "))
                                    throw new AssertionError(sql);
                                refreshes++;
                                view.put("last_refresh_finish_timestamp", "refresh-" + refreshes);
                                view.put("refresh_base_table_txn", view.get("base_table_txn"));
                                tables.get(OUTPUT).put("table_txn", 7L + refreshes);
                                return null;
                            })
                    .when(jdbc)
                    .execute(anyString());
        }
    }

    @Test
    void initialFullBaseOnlyIncrementalAndRightOnlyFullUseNativeRefreshWithoutQuestDbReceipt()
            throws Exception {
        var store = new Store();
        var ledger = root.resolve("sync.sqlite");
        var service = new EtfOverviewMaintenance(store.jdbc, ledger, Duration.ofSeconds(1));
        assertEquals("FULL", service.refresh().get("mode"));
        assertEquals("REUSED", service.refresh().get("mode"));
        assertEquals(1, store.refreshes);
        store.tables.get("etf_share").put("table_txn", 8L);
        store.view.put("base_table_txn", 8L);
        assertEquals("INCREMENTAL", service.refresh().get("mode"));
        store.tables.get("etf_daily").put("table_txn", 8L);
        assertEquals("FULL", service.refresh().get("mode"));
        verify(store.jdbc, times(2)).execute("REFRESH MATERIALIZED VIEW " + OUTPUT + " FULL");
        verify(store.jdbc).execute("REFRESH MATERIALIZED VIEW " + OUTPUT + " INCREMENTAL");
        assertTrue(versionIfActivated(store.jdbc, ledger).startsWith("etf-native:"));
    }

    @Test
    void alreadyCompletedNativeIncrementDoesNotWaitForANoopRefreshToChangeItsTimestamp()
            throws Exception {
        var store = new Store();
        var ledger = root.resolve("sync.sqlite");
        var service = new EtfOverviewMaintenance(store.jdbc, ledger, Duration.ofMillis(10));
        service.refresh();
        store.tables.get("etf_share").put("table_txn", 8L);
        store.tables.get(OUTPUT).put("table_txn", 9L);
        store.view.put("base_table_txn", 8L);
        store.view.put("refresh_base_table_txn", 8L);
        store.view.put("last_refresh_finish_timestamp", "automatic-2");
        assertEquals("NATIVE_INCREMENTAL", service.refresh().get("mode"));
        assertEquals(1, store.refreshes);
        assertTrue(versionIfActivated(store.jdbc, ledger).startsWith("etf-native:"));
    }

    @Test
    void parityFailureNeverAdvancesThePreviousCertificate() throws Exception {
        var store = new Store();
        var ledger = root.resolve("sync.sqlite");
        var service = new EtfOverviewMaintenance(store.jdbc, ledger, Duration.ofSeconds(1));
        service.refresh();
        var before =
                new MaterializedViewCheckpoints(ledger).find(OUTPUT + "@fixture-db").orElseThrow();
        store.tables.get("etf_daily").put("table_txn", 8L);
        store.expectedSize = 12;
        assertThrows(IllegalStateException.class, service::refresh);
        assertEquals(
                before,
                new MaterializedViewCheckpoints(ledger).find(OUTPUT + "@fixture-db").orElseThrow());
        assertThrows(IllegalStateException.class, () -> versionIfActivated(store.jdbc, ledger));
    }

    @Test
    void concurrentRightSideCorrectionCannotBeCertified() {
        var store = new Store();
        store.changeRightDuringParity = true;
        var ledger = root.resolve("sync.sqlite");
        var service = new EtfOverviewMaintenance(store.jdbc, ledger, Duration.ofSeconds(1));
        assertThrows(IllegalStateException.class, service::refresh);
        assertTrue(new MaterializedViewCheckpoints(ledger).find(OUTPUT + "@fixture-db").isEmpty());
    }

    @Test
    void missingNativeViewIsNeverInstalledByBackgroundCheck() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        assertEquals(
                "ABSENT",
                new EtfOverviewMaintenance(jdbc, root.resolve("sync.sqlite"), Duration.ofSeconds(1))
                        .refreshIfInstalled()
                        .get("status"));
        verify(jdbc, never()).execute(anyString());
    }
}
