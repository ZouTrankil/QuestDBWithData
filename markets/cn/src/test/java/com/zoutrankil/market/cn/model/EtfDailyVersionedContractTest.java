package com.zoutrankil.market.cn.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.fund.EtfDailyDataset;

import org.junit.jupiter.api.Test;

import java.util.List;

class EtfDailyVersionedContractTest {
    @Test
    void onlyPhysicalDateAndSchemaChangeWhileValuesKeysAndNanosecondsRemainFixed() {
        String table = "java_d014_etf_daily_contract";
        var old = EtfDailyDataset.definition(table);
        var next = EtfDailyDataset.definition(table, CnTradeDateLayout.TRADE_DATE_V2);
        assertEquals(old, EtfDailyDataset.definition(table, CnTradeDateLayout.LEGACY_V1));
        assertEquals(1, old.schemaVersion());
        assertEquals(2, next.schemaVersion());
        assertEquals("timestamp", old.designatedTimestamp());
        assertEquals("trade_date", next.designatedTimestamp());
        assertEquals(old.businessKey(), next.businessKey());
        assertEquals(List.of("ts_code", "trade_date"), next.dedupKey());
        assertEquals(StorageType.TIMESTAMP_NS, next.columns().get(1).storageType());
        assertEquals(old.columns().subList(2, 11), next.columns().subList(2, 11));
        assertEquals(old.columns().get(1).temporal(), next.columns().get(1).temporal());
        assertTrue(
                EtfDailyDataset.createIsolatedTableSql(table, CnTradeDateLayout.TRADE_DATE_V2)
                        .contains(
                                "TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)"));
        assertTrue(EtfDailyDataset.createIsolatedTableSql(table).contains("TIMESTAMP(timestamp)"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EtfDailyDataset.createIsolatedTableSql(
                                "etf_daily", CnTradeDateLayout.TRADE_DATE_V2));
    }
}
