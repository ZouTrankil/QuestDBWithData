package com.zoutrankil.data.maintenance.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class CnTimeColumnRehearsalTest {
    private static final String OWNED = "java_cn_time_rehearsal_0123456789abcdef0123456789abcdef_0";

    @Test
    void ddlPreservesNanosecondsAndKeysButCannotTargetFormalOrUnlistedTables() {
        String suffix =
                "(timestamp TIMESTAMP_NS, ts_code SYMBOL) timestamp(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(timestamp,ts_code);";
        assertEquals(
                "CREATE TABLE \"" + OWNED + "\" " + suffix,
                CnTimeColumnRehearsal.isolatedDdl(
                        "etf_daily", OWNED, "CREATE TABLE 'etf_daily' " + suffix));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CnTimeColumnRehearsal.isolatedDdl(
                                "etf_daily", "etf_daily", "CREATE TABLE 'etf_daily' " + suffix));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CnTimeColumnRehearsal.isolatedDdl(
                                "daily", OWNED, "CREATE TABLE 'daily' " + suffix));
        assertThrows(
                IllegalArgumentException.class,
                () -> CnTimeColumnRehearsal.renameSql("etf_daily", "timestamp", "trade_date"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CnTimeColumnRehearsal.renameSql(OWNED, "timestamp", "close"));
    }

    @Test
    void schemaCheckRejectsNanosecondLossAndMissingDedupKey() {
        var before =
                List.<Map<String, Object>>of(
                        Map.of("column", "timestamp", "type", "TIMESTAMP_NS", "upsertKey", true));
        var correct =
                List.<Map<String, Object>>of(
                        Map.of("column", "trade_date", "type", "TIMESTAMP_NS", "upsertKey", true));
        CnTimeColumnRehearsal.requireColumns(before, correct, "timestamp", "trade_date");
        var precisionLoss =
                List.<Map<String, Object>>of(
                        Map.of("column", "trade_date", "type", "TIMESTAMP", "upsertKey", true));
        assertThrows(
                IllegalStateException.class,
                () ->
                        CnTimeColumnRehearsal.requireColumns(
                                before, precisionLoss, "timestamp", "trade_date"));
        var missingKey =
                List.<Map<String, Object>>of(
                        Map.of("column", "trade_date", "type", "TIMESTAMP_NS", "upsertKey", false));
        assertThrows(
                IllegalStateException.class,
                () ->
                        CnTimeColumnRehearsal.requireColumns(
                                before, missingKey, "timestamp", "trade_date"));
    }

    @Test
    void replacementKeepsTimestampTypeAndMapsOnlyDeclaredColumnAndKeys() {
        String ddl =
                "CREATE TABLE 'etf_daily' ( \n\ttimestamp TIMESTAMP_NS,\n\tts_code SYMBOL,\n\tupdate_time TIMESTAMP\n) timestamp(timestamp) PARTITION BY YEAR\nDEDUP UPSERT KEYS(ts_code,timestamp);";
        String result =
                CnTimeColumnRehearsal.replacementDdl("etf_daily", OWNED + "_v2", ddl, "timestamp");
        assertTrue(result.contains("trade_date TIMESTAMP_NS"));
        assertTrue(result.contains("update_time TIMESTAMP"));
        assertTrue(result.contains(") timestamp(trade_date) PARTITION BY YEAR"));
        assertTrue(result.contains("DEDUP UPSERT KEYS(ts_code,trade_date)"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CnTimeColumnRehearsal.replacementDdl(
                                "etf_daily", "etf_daily", ddl, "timestamp"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CnTimeColumnRehearsal.replacementDdl("etf_daily", OWNED + "_v2", ddl, "ts"));
    }

    @Test
    void cleanupCannotDropFormalOrReplacedObjects() {
        var jdbc = mock(JdbcTemplate.class);
        var rehearsal = new CnTimeColumnRehearsal(jdbc);
        assertFalse(rehearsal.cleanup("etf_daily", 1L, new HashMap<>()));
        verifyNoInteractions(jdbc);
        when(jdbc.queryForList(anyString(), eq(OWNED)))
                .thenReturn(List.of(Map.<String, Object>of("id", 999L)));
        assertFalse(rehearsal.cleanup(OWNED, 1L, new HashMap<>()));
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void runtimeDictionaryCapacityDoesNotMasqueradeAsBusinessSchema() {
        var original =
                List.<Map<String, Object>>of(
                        Map.of(
                                "column",
                                "ts_code",
                                "type",
                                "SYMBOL",
                                "indexed",
                                false,
                                "indexBlockCapacity",
                                256,
                                "symbolCapacity",
                                4096));
        var sample =
                List.<Map<String, Object>>of(
                        Map.of(
                                "column",
                                "ts_code",
                                "type",
                                "SYMBOL",
                                "indexed",
                                false,
                                "indexBlockCapacity",
                                0,
                                "symbolCapacity",
                                128));
        CnTimeColumnRehearsal.requireColumns(original, sample, "timestamp", "trade_date");
    }
}
