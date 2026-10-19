package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.DailyBasicSource;

import com.zoutrankil.data.client.dto.TushareDailyBasicDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.mapper.DailyBasicMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyBasicMappingTest {
    private final DailyBasicMapper mapper = new DailyBasicMapper();

    @Test void mapsEverySourceFieldExplicitlyAndPreservesNullsAndUnits() throws Exception {
        var value = mapper.fromSource(new TushareDailyBasicDto("000001.SZ", "20260928", "12.3", "0.51",
                "0.88", "1.2", null, "10.5", "1.1", "2.3", "2.2", "0.4", "0.5",
                "100.0", "80.0", "60.0", "120000.0", "90000.0"));
        assertEquals("000001.SZ", value.tsCode());
        assertEquals(LocalDate.of(2026, 9, 28), value.tradeDate());
        assertEquals(12.3d, value.close());
        assertNull(value.pe());
        assertEquals(60.0d, value.freeShare());
        assertEquals(90000.0d, value.circMv());
        assertEquals(new DailyBasicKey("000001.SZ", LocalDate.of(2026, 9, 28)), value.key());

        DatasetValues columns = mapper.values(value);
        assertEquals(18, columns.columns().size());
        assertEquals(value, mapper.fromValues(columns));
    }

    @Test void rejectsInvalidDateNumericCodeAndNonFinitePhysicalValue() {
        assertThrows(IOException.class, () -> mapper.fromSource(new TushareDailyBasicDto("000001.SZ", "20260931",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        assertThrows(IOException.class, () -> mapper.fromSource(new TushareDailyBasicDto("000001.SZ", "20260928",
                "NaN", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> new DailyBasicKey("000001", LocalDate.of(2026, 9, 28)));
        assertThrows(IllegalArgumentException.class, () -> new DailyBasic("000001.SZ", LocalDate.of(2026, 9, 28),
                Double.POSITIVE_INFINITY, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test void definitionMatchesEveryPhysicalColumnAndIsolatedDdlUsesFullDedupKey() {
        var definition = DailyBasicDataset.DEFINITION;
        assertEquals("daily_basic", definition.datasetId());
        assertEquals("trade_date", definition.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.YEAR, definition.partition());
        assertTrue(definition.wal());
        assertEquals(List.of("ts_code", "trade_date"), definition.businessKey());
        assertEquals(List.of("ts_code", "trade_date"), definition.dedupKey());
        assertEquals(DailyBasicSource.FIELDS, definition.columns().stream()
                .map(DatasetDefinition.Column::storageName).toList());
        String ddl = DailyBasicDataset.createTableSql("java_d008_daily_basic");
        assertTrue(ddl.contains("TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)"));
        assertThrows(IllegalArgumentException.class, () -> DailyBasicDataset.createTableSql("daily_basic; DROP TABLE x"));
    }
}
