package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.maintenance.storage.MaterializedViewCheckpoints;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class EtfMarketOverviewMaterializedViewReadGuardTest {
    @TempDir Path ledgerDirectory;
    private Path ledger() { return ledgerDirectory.resolve("sync.sqlite"); }
    private static final String OUTPUT = EtfMarketOverviewMaterializedViewReadGuard.OUTPUT;

    private static final class Store {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final Map<String, Map<String,Object>> tables = new HashMap<>();
        final Map<String,Object> alias = new HashMap<>();
        final Map<String,Object> view = new HashMap<>();

        Store(Path ledger) throws Exception {
            when(jdbc.queryForObject("SELECT current_data_id()",String.class)).thenReturn("fixture-db");
            int id = 1;
            for (String table : List.of("etf_share", "etf_daily", OUTPUT)) {
                var row = new HashMap<String,Object>();
                row.put("id", id++); row.put("directoryName", table + "~1");
                row.put("table_txn", 7L); row.put("sequencerTxn", 7L); row.put("writerTxn", 7L);
                row.put("walEnabled", true); row.put("table_suspended", false); row.put("suspended", false);
                row.put("wal_pending_row_count", 0L); row.put("bufferedTxnSize", 0L);
                tables.put(table, row);
            }
            alias.put("view_sql", "SELECT * FROM " + OUTPUT); alias.put("view_status", "valid");
            alias.put("view_table_dir_name", "mv_etf_market_overview_daily_v1~4");
            view.put("base_table_name", "etf_share"); view.put("refresh_type", "manual");
            view.put("view_status", "valid"); view.put("view_sql", EtfMarketOverviewMaterializedViewReadGuard.SQL);
            view.put("refresh_base_table_txn", 7L); view.put("base_table_txn", 7L);
            view.put("last_refresh_finish_timestamp","refresh-1");
            new MaterializedViewCheckpoints(ledger).save(OUTPUT+"@fixture-db",new MaterializedViewCheckpoints.Checkpoint(
                    EtfMarketOverviewMaterializedViewReadGuard.definitionHash(),version("etf_share"),version("etf_daily"),version(OUTPUT),"refresh-1"));
            when(jdbc.queryForList(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                if (sql.contains("materialized_views()")) return List.of(view);
                if (sql.contains("views()")) return List.of(alias);
                throw new AssertionError(sql);
            });
            when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
                Object[] arguments = (Object[]) call.getRawArguments()[1];
                return List.of(tables.get(arguments[0].toString()));
            });
        }

        String version(String table) {
            var r = tables.get(table);
            return table + ":" + r.get("id") + ":" + r.get("directoryName") + ":"
                    + r.get("table_txn") + ":" + r.get("sequencerTxn");
        }
    }

    @Test void activatedNativeAliasRequiresBothSourceReceiptAndSettledOutput() throws Exception {
        var store = new Store(ledger());
        assertTrue(EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(store.jdbc,ledger()).startsWith("etf-native:"));
        verify(store.jdbc, never()).execute(anyString());
    }

    @Test void rightSourceCorrectionCannotHideBehindUnchangedBaseCheckpoint() throws Exception {
        var store = new Store(ledger());
        store.tables.get("etf_daily").put("table_txn", 8L);
        assertThrows(IllegalStateException.class, () -> EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(store.jdbc,ledger()));
    }

    @Test void outputRecreationInvalidatesTheReceipt() throws Exception {
        var store = new Store(ledger());
        store.tables.get(OUTPUT).put("id", 99);
        assertThrows(IllegalStateException.class, () -> EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(store.jdbc,ledger()));
    }

    @Test void sourceWalMustBeVisibleBeforeNativeRead() throws Exception {
        var store = new Store(ledger());
        store.tables.get("etf_daily").put("writerTxn", 6L);
        assertThrows(IllegalStateException.class, () -> EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(store.jdbc,ledger()));
    }

    @Test void unexpectedTimerRefreshPolicyIsRejected() throws Exception {
        var store = new Store(ledger());
        store.view.put("refresh_type", "timer");
        assertThrows(IllegalStateException.class, () -> EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(store.jdbc,ledger()));
    }

    @Test void missingLocalCheckpointNeverFallsBackToQuestDbReceipt() throws Exception {
        var store=new Store(ledger());
        java.nio.file.Files.delete(ledger());
        assertThrows(IllegalStateException.class,() -> EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(store.jdbc,ledger()));
        assertFalse(java.nio.file.Files.exists(ledger()));
    }

    @Test void immediateNativeRefreshPolicyIsAcceptedWithVerifiedLocalState() throws Exception {
        var store=new Store(ledger());store.view.put("refresh_type","immediate");
        assertTrue(EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(store.jdbc,ledger()).startsWith("etf-native:"));
    }

    @Test void certificateFromAnotherQuestDbInstanceCannotBeReused() throws Exception {
        var store=new Store(ledger());
        when(store.jdbc.queryForObject("SELECT current_data_id()",String.class)).thenReturn("another-db");
        assertThrows(IllegalStateException.class,() -> EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(store.jdbc,ledger()));
    }

    @Test void canonicalReadDoesNotRequireOrQueryAnOrdinaryAlias() throws Exception {
        var store = new Store(ledger());
        assertNotNull(EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(store.jdbc,ledger()));
        verify(store.jdbc, never()).queryForList(contains("FROM views()"));
    }

    @Test
    @EnabledIfSystemProperty(named="etf.mv.private.jdbc", matches="jdbc:postgresql://127\\.0\\.0\\.1:19812/qdb")
    void privateQuestDbAcceptsLocalCheckpointAndRejectsRightTableOnlyCorrection() throws Exception {
        // This fixed task-private port must never point to the production 8812 listener.
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                System.getProperty("etf.mv.private.jdbc"), "admin", "quest"));
        new com.zoutrankil.data.maintenance.application.EtfOverviewMaintenance(jdbc,ledger().toString(),java.time.Duration.ofSeconds(30)).refresh();
        assertTrue(EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(jdbc,ledger()).startsWith("etf-native:"));
        jdbc.execute("INSERT INTO etf_daily VALUES ('2026-09-29','B',6.0)");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(jdbc,ledger());
            } catch (IllegalStateException expected) { return; }
            Thread.sleep(25);
        }
        fail("Right-table-only revision must invalidate the verified local checkpoint");
    }
}
