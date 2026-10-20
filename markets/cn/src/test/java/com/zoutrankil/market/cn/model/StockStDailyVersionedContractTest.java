package com.zoutrankil.market.cn.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.stock.basic.StockStDailyDataset;

import org.junit.jupiter.api.Test;

import java.util.List;

class StockStDailyVersionedContractTest {
    @Test
    void explicitV2HasTradeDateKeysButKeepsTheOldContractAndBusinessRules() {
        var old = StockStDailyDataset.DEFINITION;
        var next = StockStDailyDataset.definition("stk_st_daily", CnTradeDateLayout.TRADE_DATE_V2);
        assertEquals(
                old, StockStDailyDataset.definition("stk_st_daily", CnTradeDateLayout.LEGACY_V1));
        assertEquals(List.of("ts_code", "timestamp"), old.businessKey());
        assertEquals(List.of("ts_code", "trade_date"), next.businessKey());
        assertEquals(next.businessKey(), next.dedupKey());
        assertEquals("trade_date", next.designatedTimestamp());
        assertEquals(2, next.schemaVersion());
        assertEquals(old.columns().subList(0, 2), next.columns().subList(0, 2));
        assertEquals(old.columns().get(2).temporal(), next.columns().get(2).temporal());
        assertEquals(StorageType.TIMESTAMP, next.columns().get(2).storageType());
        assertEquals(old.dependencies(), next.dependencies());
        assertTrue(
                StockStDailyDataset.createIsolatedTableSql(
                                "java_d012_stk_st_daily_layout", CnTradeDateLayout.TRADE_DATE_V2)
                        .contains(
                                "TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code,trade_date)"));
        assertTrue(
                StockStDailyDataset.createIsolatedTableSql("java_d012_stk_st_daily_layout")
                        .contains("TIMESTAMP(timestamp)"));
    }
}
