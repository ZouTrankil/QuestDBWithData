package com.zoutrankil.data.l2.mapper;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.L2EventResponseFeatureField;
import com.zoutrankil.data.domain.L2EventResponseFeaturesDataset;
import com.zoutrankil.data.domain.L2EventResponseFeaturesKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class L2EventResponseFeaturesMappingTest {
    private static final LocalDate DAY = LocalDate.parse("2026-09-21");
    private static final Instant MINUTE = DAY.atTime(9, 15).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    private static final ObjectMapper JSON = JobDefinitionJson.mapper();

    @Test
    void freezesAll73ColumnsAndFullThreePartBusinessKey() throws Exception {
        var mapper = new L2EventResponseFeaturesMapper();
        var row = mapper.fromParquet(sourceRow(), DAY);
        assertEquals(73, L2EventResponseFeatureField.values().length);
        assertEquals(73, L2EventResponseFeaturesMapper.columns().size());
        assertEquals(List.of("symbol", "minute", "event_type"), L2EventResponseFeaturesDataset.DEFINITION.businessKey());
        assertEquals(List.of("symbol", "minute", "event_type"), L2EventResponseFeaturesDataset.DEFINITION.dedupKey());
        assertEquals("minute", L2EventResponseFeaturesDataset.DEFINITION.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.DAY, L2EventResponseFeaturesDataset.DEFINITION.partition());
        assertTrue(L2EventResponseFeaturesDataset.DEFINITION.wal());
        assertEquals(MINUTE, row.minute());
        assertEquals("large_turnover", row.eventType());
        assertEquals("large_turnover", row.key().eventType());
        assertEquals(0L, row.feature("tick_count"));
        assertNull(row.feature("future_vwap_return_30m"));
        var roundTrip = mapper.fromValues(mapper.values(row));
        assertEquals(row, roundTrip);
        assertEquals(row.key(), roundTrip.key());
        assertEquals(DAY, mapper.values(row).get("trade_date", LocalDate.class));
    }

    @Test
    void rejectsOffsetTimestampsDateDriftAndUnknownEventTypes() throws Exception {
        var mapper = new L2EventResponseFeaturesMapper();
        var offset = sourceRow(); offset.put("minute", "2026-09-21T09:15:00+08:00");
        assertThrows(IOException.class, () -> mapper.fromParquet(offset, DAY));
        var drift = sourceRow(); drift.put("trade_date", "20260922");
        assertThrows(IOException.class, () -> mapper.fromParquet(drift, LocalDate.parse("2026-09-22")));
        var missing = sourceRow(); missing.remove("symbol");
        assertThrows(IOException.class, () -> mapper.fromParquet(missing, DAY));
        var badType = sourceRow(); badType.put("event_type", "unknown");
        assertThrows(IOException.class, () -> mapper.fromParquet(badType, DAY));
    }

    @Test
    void completeKeyRequiresCanonicalSymbolEventTypeAndWholeMinute() {
        assertThrows(IllegalArgumentException.class, () -> new L2EventResponseFeaturesKey("000001", MINUTE, "large_turnover"));
        assertThrows(IllegalArgumentException.class, () -> new L2EventResponseFeaturesKey("000001.SZ", MINUTE.plusSeconds(1), "large_turnover"));
        assertThrows(IllegalArgumentException.class, () -> new L2EventResponseFeaturesKey("000001.SZ", MINUTE, "unknown"));
        var first = new L2EventResponseFeaturesKey("000001.SZ", MINUTE, "large_turnover");
        var second = new L2EventResponseFeaturesKey("000001.SZ", MINUTE, "high_cancel_ratio");
        assertNotEquals(first, second, "event_type is part of the collision-safe key");
    }

    @Test
    void parquetDoubleMayBeIntegralButLongCannotBeFractional() throws Exception {
        var mapper = new L2EventResponseFeaturesMapper();
        var row = sourceRow(); row.put("bid_depth_1", 123L); row.put("tick_count", 2);
        var mapped = mapper.fromParquet(row, DAY);
        assertEquals(123.0d, mapped.feature("bid_depth_1"));
        assertEquals(2L, mapped.feature("tick_count"));
        row.put("tick_count", 2.5d);
        assertThrows(IOException.class, () -> mapper.fromParquet(row, DAY));
    }

    private static ObjectNode sourceRow() {
        var row = JSON.createObjectNode();
        for (var field : L2EventResponseFeatureField.values()) {
            switch (field) {
                case TRADE_DATE -> row.put(field.fieldName(), "20260921");
                case SYMBOL -> row.put(field.fieldName(), "000001.SZ");
                case MARKET -> row.put(field.fieldName(), "SZ");
                case BOARD -> row.put(field.fieldName(), "MAIN");
                case MINUTE -> row.put(field.fieldName(), "2026-09-21T09:15:00");
                case EVENT_TYPE -> row.put(field.fieldName(), "large_turnover");
                case VOLUME, AMOUNT -> row.put(field.fieldName(), 0.0d);
                case TICK_COUNT, HAS_TRADE_1M -> row.put(field.fieldName(), 0L);
                default -> {
                    if (!field.nullable()) throw new AssertionError("Unexpected required D088 field: " + field.fieldName());
                    row.putNull(field.fieldName());
                }
            }
        }
        return row;
    }
}
