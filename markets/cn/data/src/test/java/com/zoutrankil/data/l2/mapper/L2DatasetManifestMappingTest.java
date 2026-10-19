package com.zoutrankil.data.l2.mapper;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.L2DatasetManifest;
import com.zoutrankil.data.domain.L2DatasetManifestDataset;
import com.zoutrankil.data.domain.L2DatasetManifestKey;
import com.zoutrankil.data.l2.mapper.L2DatasetManifestMapper;
import com.zoutrankil.data.l2.storage.L2DatasetManifestWritePort;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class L2DatasetManifestMappingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final L2DatasetManifestMapper mapper = new L2DatasetManifestMapper();
    private static final String SOURCE = """
            {"trade_date":"20260924","symbol":"000001.SZ","market":"SZSE","board":"main",
             "source_root":"D:/l2/source","output_root":"D:/l2/output","feature_version":"v4",
             "daily_feature_ok":true,"t0_ok":false,"raw_row_counts":"{\\"deal\\":7}",
             "output_paths":"{\\"daily\\":\\"x.parquet\\"}","cost_config":"{\\"rate\\":0.001}",
             "horizons_min":"[1,5,15]","errors":null,"batch_id":3}
            """;

    @Test void mapsEverySourceFieldAndDerivesCalendarTimestampCarrier() throws Exception {
        L2DatasetManifest row = mapper.fromParquet(JSON.readTree(SOURCE));
        var day = LocalDate.of(2026, 9, 24);
        assertEquals(new L2DatasetManifest(day, "000001.SZ", "SZSE", "main", "D:/l2/source",
                "D:/l2/output", "v4", true, false, "{\"deal\":7}",
                "{\"daily\":\"x.parquet\"}", "{\"rate\":0.001}", "[1,5,15]", null, 3L, day), row);
        assertEquals(new L2DatasetManifestKey(day, "000001.SZ", 3), row.key());
        assertEquals(row, mapper.fromValues(mapper.values(row)));
        assertEquals(row, mapper.fromStorage(mapper.toStorage(row)));
        assertEquals(Instant.parse("2026-09-24T00:00:00Z"), mapper.partitionCarrier(row));
        assertEquals(16, mapper.values(row).columns().size());
    }

    @Test void freezesBusinessAndPhysicalKeysAndSchema() {
        var definition = L2DatasetManifestDataset.DEFINITION;
        assertEquals("trade_date_ts", definition.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.DAY, definition.partition());
        assertTrue(definition.wal());
        assertEquals(List.of("trade_date_ts", "symbol", "batch_id"), definition.businessKey());
        assertEquals(List.of("symbol", "batch_id", "trade_date_ts"), definition.dedupKey());
        assertEquals("java_d085_l2_dataset_manifest_acceptance", 
                L2DatasetManifestDataset.definition("java_d085_l2_dataset_manifest_acceptance").objectName());
        assertEquals(List.of("trade_date", "symbol", "market", "board", "source_root", "output_root",
                "feature_version", "daily_feature_ok", "t0_ok", "raw_row_counts", "output_paths",
                "cost_config", "horizons_min", "errors", "batch_id", "trade_date_ts"),
                definition.columns().stream().map(DatasetDefinition.Column::storageName).toList());
        var day = LocalDate.of(2026, 9, 24);
        assertNotEquals(new L2DatasetManifestKey(day, "000001.SZ", 2), new L2DatasetManifestKey(day, "000001.SZ", 3));
        assertThrows(IllegalStateException.class,
                () -> L2DatasetManifestWritePort.requireIsolatedTableName("l2_dataset_manifest"));
    }

    @Test void rejectsSchemaTypeDateAndIdentityDrift() throws Exception {
        var wrongType = JSON.readTree(SOURCE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongType).put("daily_feature_ok", "true");
        assertThrows(IllegalArgumentException.class, () -> mapper.fromParquet(wrongType));
        var invalidDate = JSON.readTree(SOURCE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalidDate).put("trade_date", "20260931");
        assertThrows(RuntimeException.class, () -> mapper.fromParquet(invalidDate));
        var invalidSymbol = JSON.readTree(SOURCE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalidSymbol).put("symbol", "000001");
        assertThrows(IllegalArgumentException.class, () -> mapper.fromParquet(invalidSymbol));
        var invalidBatch = JSON.readTree(SOURCE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalidBatch).put("batch_id", -1);
        assertThrows(IllegalArgumentException.class, () -> mapper.fromParquet(invalidBatch));
        var extraColumn = JSON.readTree(SOURCE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) extraColumn).put("unmapped", "x");
        assertThrows(IllegalArgumentException.class, () -> mapper.fromParquet(extraColumn));
        assertThrows(IllegalArgumentException.class, () -> new L2DatasetManifest(
                LocalDate.of(2026, 9, 24), "000001.SZ", null, null, null, null, null,
                null, null, null, null, null, null, null, 1,
                LocalDate.of(2026, 9, 23)));
    }
}
