package com.zoutrankil.questdbwithdata.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeatureField;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeaturesDataset;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class L2IntradayBarFeaturesMappingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.parse("2026-09-21");
    private static final Instant MINUTE = Instant.parse("2026-09-21T01:15:00Z");

    @Test
    void mapsAllSixtyColumnsAndNormalizesShanghaiWallTimeToUtc() throws Exception {
        var mapper = new L2IntradayBarFeaturesMapper();
        var row = mapper.fromParquet(sourceRow(), DAY);
        assertEquals(60, L2IntradayBarFeatureField.values().length);
        assertEquals(55, row.features().size());
        assertEquals(DAY, row.tradeDate());
        assertEquals("000001.SZ", row.symbol());
        assertEquals("SZ", row.market());
        assertEquals("MAIN", row.board());
        assertEquals(MINUTE, row.minute());
        assertEquals(0L, row.feature("tick_count"));
        assertEquals(0.0d, row.feature("bid_depth_1"));
        assertNull(row.feature("vwap"));
        assertEquals(List.of("symbol", "minute"), L2IntradayBarFeaturesDataset.DEFINITION.businessKey());
        assertEquals(List.of("symbol", "minute"), L2IntradayBarFeaturesDataset.DEFINITION.dedupKey());
        assertEquals("minute", L2IntradayBarFeaturesDataset.DEFINITION.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.DAY, L2IntradayBarFeaturesDataset.DEFINITION.partition());
        assertTrue(L2IntradayBarFeaturesDataset.DEFINITION.wal());

        var roundTrip = mapper.fromValues(mapper.values(row));
        assertEquals(row, roundTrip);
        assertEquals(row.key(), roundTrip.key());
        assertEquals(DAY, mapper.values(row).get("trade_date", LocalDate.class));
    }

    @Test
    void rejectsOffsetTimestampsAndTradeDateDrift() throws Exception {
        var mapper = new L2IntradayBarFeaturesMapper();
        var offset = sourceRow();
        offset.put("minute", "2026-09-21T09:15:00+08:00");
        assertThrows(IOException.class, () -> mapper.fromParquet(offset, DAY));

        var drift = sourceRow();
        drift.put("trade_date", "20260922");
        assertThrows(IOException.class, () -> mapper.fromParquet(drift, LocalDate.parse("2026-09-22")));

        var missing = sourceRow();
        missing.remove("symbol");
        assertThrows(IOException.class, () -> mapper.fromParquet(missing, DAY));
    }

    @Test
    void completeKeyRequiresCanonicalSymbolAndWholeMinute() throws Exception {
        var mapper = new L2IntradayBarFeaturesMapper();
        var row = mapper.fromParquet(sourceRow(), DAY);
        assertThrows(IllegalArgumentException.class,
                () -> new com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeaturesKey(
                        "000001.SZ", MINUTE.plusSeconds(1)));
        var values = new LinkedHashMap<>(mapper.values(row).asMap());
        values.put("symbol", "000001");
        assertThrows(IllegalArgumentException.class,
                () -> mapper.fromValues(new com.zoutrankil.questdbwithdata.domain.DatasetValues(values)));
    }

    @Test
    void parquetDoubleCanBeIntegralButRequiredLongCannotBeFractional() throws Exception {
        var mapper = new L2IntradayBarFeaturesMapper();
        var row = sourceRow();
        row.put("bid_depth_1", 123L);
        row.put("tick_count", 2);
        var mapped = mapper.fromParquet(row, DAY);
        assertEquals(123.0d, mapped.feature("bid_depth_1"));
        assertEquals(2L, mapped.feature("tick_count"));

        row.put("tick_count", 2.5d);
        assertThrows(IOException.class, () -> mapper.fromParquet(row, DAY));
    }

    private static ObjectNode sourceRow() {
        var row = JSON.createObjectNode();
        for (var field : L2IntradayBarFeatureField.values()) {
            switch (field) {
                case TRADE_DATE -> row.put(field.fieldName(), "20260921");
                case SYMBOL -> row.put(field.fieldName(), "000001.SZ");
                case MARKET -> row.put(field.fieldName(), "SZ");
                case BOARD -> row.put(field.fieldName(), "MAIN");
                case MINUTE -> row.put(field.fieldName(), "2026-09-21T09:15:00");
                case VOLUME, AMOUNT -> row.put(field.fieldName(), 0.0d);
                case TICK_COUNT, HAS_TRADE_1M -> row.put(field.fieldName(), 0L);
                default -> {
                    if (!field.nullable()) throw new AssertionError("Unexpected required D087 field: " + field.fieldName());
                    if ("bid_depth_1".equals(field.fieldName())) row.put(field.fieldName(), 0L);
                    else row.putNull(field.fieldName());
                }
            }
        }
        return row;
    }
}
