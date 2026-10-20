package com.zoutrankil.market.cn.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.fund.EtfAdjDataset;

import org.junit.jupiter.api.Test;

import java.util.List;

class CnTradeDateLayoutTest {
    @Test
    void layoutsOnlyApplyToTheSixReviewedTradingDateAliases() {
        for (var id :
                List.of(
                        "etf_adj",
                        "etf_daily",
                        "etf_share",
                        "stk_st_daily",
                        "stk_suspend",
                        "l2_daily_features")) {
            assertEquals("trade_date", CnTradeDateLayout.TRADE_DATE_V2.physicalDateColumn(id));
            assertEquals(
                    id.equals("l2_daily_features") ? "ts" : "timestamp",
                    CnTradeDateLayout.LEGACY_V1.physicalDateColumn(id));
        }
        for (var id : List.of("stk_shock", "shibor", "etf_basic", "rmb_index_daily", "unknown"))
            for (var layout : CnTradeDateLayout.values())
                assertThrows(IllegalArgumentException.class, () -> layout.physicalDateColumn(id));
        assertThrows(IllegalArgumentException.class, () -> CnTradeDateLayout.fromVersion(0));
        assertThrows(IllegalArgumentException.class, () -> CnTradeDateLayout.fromVersion(3));
    }

    @Test
    void adjustmentV2OnlyChangesTheDeclaredPhysicalDateNameAndVersion() {
        var old = EtfAdjDataset.DEFINITION;
        var standard = EtfAdjDataset.definition("etf_adj", CnTradeDateLayout.TRADE_DATE_V2);
        assertEquals(old, EtfAdjDataset.definition("etf_adj", CnTradeDateLayout.LEGACY_V1));
        assertEquals(2, standard.schemaVersion());
        assertEquals("trade_date", standard.designatedTimestamp());
        assertEquals(List.of("ts_code", "trade_date"), standard.dedupKey());
        assertEquals(old.businessKey(), standard.businessKey());
        assertEquals(old.partition(), standard.partition());
        assertEquals(old.wal(), standard.wal());
        assertEquals(old.columns().get(1).temporal(), standard.columns().get(1).temporal());
        assertEquals(
                DatasetDefinition.StorageType.TIMESTAMP, standard.columns().get(1).storageType());
        assertTrue(
                EtfAdjDataset.createIsolatedTableSql(
                                "java_d015_etf_adj_v2", CnTradeDateLayout.TRADE_DATE_V2)
                        .contains(
                                "TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)"));
        assertTrue(
                EtfAdjDataset.createIsolatedTableSql("java_d015_etf_adj_v1")
                        .contains(
                                "TIMESTAMP(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, timestamp)"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EtfAdjDataset.createIsolatedTableSql(
                                "etf_adj", CnTradeDateLayout.TRADE_DATE_V2));
    }
}
