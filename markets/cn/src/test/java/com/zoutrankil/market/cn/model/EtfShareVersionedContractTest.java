package com.zoutrankil.market.cn.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.fund.EtfShareDataset;

import org.junit.jupiter.api.Test;

import java.util.List;

class EtfShareVersionedContractTest {
    @Test
    void dateLayoutChangesButObservationMetadataValuesAndIsolationStayFixed() {
        String table = "java_d016_etf_share_layout_contract";
        var old = EtfShareDataset.definition(table);
        var next = EtfShareDataset.definition(table, CnTradeDateLayout.TRADE_DATE_V2);
        assertEquals(old, EtfShareDataset.definition(table, CnTradeDateLayout.LEGACY_V1));
        assertEquals(1, old.schemaVersion());
        assertEquals(2, next.schemaVersion());
        assertEquals("timestamp", old.designatedTimestamp());
        assertEquals("trade_date", next.designatedTimestamp());
        assertEquals(old.businessKey(), next.businessKey());
        assertEquals(List.of("ts_code", "trade_date"), next.dedupKey());
        assertEquals(old.columns().subList(2, 6), next.columns().subList(2, 6));
        assertEquals(StorageType.TIMESTAMP, next.columns().get(1).storageType());
        assertEquals("update_time", next.columns().get(5).storageName());
        assertEquals(StorageType.TIMESTAMP, next.columns().get(5).storageType());
        assertTrue(
                EtfShareDataset.createIsolatedTableSql(table, CnTradeDateLayout.TRADE_DATE_V2)
                        .contains(
                                "TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)"));
        assertTrue(EtfShareDataset.createIsolatedTableSql(table).contains("TIMESTAMP(timestamp)"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        EtfShareDataset.createIsolatedTableSql(
                                "etf_share", CnTradeDateLayout.TRADE_DATE_V2));
    }
}
