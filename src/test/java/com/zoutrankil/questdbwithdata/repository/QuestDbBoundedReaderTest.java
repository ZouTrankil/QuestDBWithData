package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestDbBoundedReaderTest {
    private final QuestDbBoundedReader reader = new QuestDbBoundedReader(mock(JdbcTemplate.class));
    private final DatasetDefinition definition = StockBasicDataset.DEFINITION;
    private final List<String> columns = definition.columns().stream().map(DatasetDefinition.Column::logicalName).toList();
    private DatasetReadQuery query(Map<String, Object> filters, DatasetReadCursor cursor) {
        return new DatasetReadQuery(columns, filters, null, null, null, 2, cursor);
    }

    @Test void filterValuesAreBoundAndUnknownFieldsFailBeforeSql() {
        String attack = "x' OR 1=1 --";
        var prepared = reader.prepare(definition, query(Map.of("ts_code", attack), null), null);
        assertFalse(prepared.sql().contains(attack));
        assertTrue(prepared.sql().contains("\"ts_code\" = ?"));
        assertEquals(attack, prepared.parameters().getFirst().storageValue());
        assertThrows(IllegalArgumentException.class, () -> reader.prepare(definition, query(Map.of("ts_code; drop table x", "x"), null), null));
        assertThrows(IllegalArgumentException.class, () -> reader.prepare(definition, query(Map.of("snapshot_ts", "20260101"), null), null));
    }

    @Test void cursorUsesCompleteLexicographicKeyAndCannotCrossQueryOrVersion() {
        var base = query(Map.of("area", "深圳"), null);
        var fingerprint = reader.prepare(definition, base, "v1").fingerprint();
        var cursor = new DatasetReadCursor(fingerprint, List.of(Instant.parse("2026-09-29T00:00:00Z"), "000001.SZ"), "v1");
        var sql = reader.prepare(definition, base.after(cursor), "v1").sql();
        assertTrue(sql.contains("\"snapshot_ts\" > cast(? as TIMESTAMP)"));
        assertTrue(sql.contains("\"snapshot_ts\" = cast(? as TIMESTAMP) AND \"ts_code\" > ?"));
        assertTrue(sql.endsWith("ORDER BY \"snapshot_ts\" ASC, \"ts_code\" ASC LIMIT 3"));
        assertThrows(IllegalArgumentException.class, () -> reader.prepare(definition, query(Map.of("area", "北京"), cursor), "v1"));
        assertThrows(IllegalArgumentException.class, () -> reader.prepare(definition, base.after(cursor), "v2"));
        var invalidType = new DatasetReadCursor(fingerprint, List.of("wrong", "000001.SZ"), "v1");
        assertThrows(IllegalArgumentException.class, () -> reader.prepare(definition, base.after(invalidType), "v1"));
    }

    @Test void typedCalendarRangesNullsAndPrecisionAreStrict() {
        var range = new DatasetReadQuery(columns, Map.of(), "list_date", LocalDate.of(1990, 1, 1), LocalDate.of(2000, 1, 1), 5, null);
        var prepared = reader.prepare(definition, range, null);
        assertEquals(List.of("19900101", "20000101"), prepared.parameters().stream().map(QuestDbBoundedReader.Bound::storageValue).toList());
        assertThrows(IllegalArgumentException.class, () -> reader.prepare(definition,
                new DatasetReadQuery(columns, Map.of(), "list_date", LocalDate.of(2000, 1, 1), LocalDate.of(1990, 1, 1), 5, null), null));
        var nullFilter = new HashMap<String, Object>(); nullFilter.put("area", null);
        assertTrue(reader.prepare(definition, query(nullFilter, null), null).sql().contains("\"area\" IS NULL"));
        assertThrows(IllegalArgumentException.class, () -> reader.prepare(definition, query(Map.of("snapshot_ts",
                Instant.parse("2026-09-29T00:00:00.000000001Z")), null), null));
        var nullable = new HashMap<String, Object>(); nullable.put("value", null);
        assertNull(new DatasetValues(nullable).get("value", Double.class));
        assertThrows(IllegalArgumentException.class, () -> new DatasetValues(Map.of("value", 1L)).get("value", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> new DatasetValues(Map.of()).get("missing", String.class));
    }
}
