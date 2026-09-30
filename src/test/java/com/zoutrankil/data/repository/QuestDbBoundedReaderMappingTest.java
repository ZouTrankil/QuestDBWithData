package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class QuestDbBoundedReaderMappingTest {
    private DatasetDefinition definition() {
        return new DatasetDefinition("ticks", 1, "fixture", "test", "ticks", ObjectKind.TABLE,
                List.of(new Column("ts", "event_time", "ts", StorageType.TIMESTAMP_NS, false, "Event instant",
                                new TemporalContract(TemporalKind.INSTANT, "ISO_OFFSET_DATE_TIME", "UTC", "NANOS", "Event instant")),
                        new Column("id", "id", "id", StorageType.SYMBOL, false, "Full identity component", null),
                        new Column("value", "value", "value", StorageType.DOUBLE, true, "Nullable value", null)),
                List.of("event_time", "id"), List.of("ts", "id"), "ts", Partition.DAY, true,
                Set.of(Capability.READ), List.of(), "Typed reader fixture");
    }
    private JdbcTemplate jdbc(Map<String, String> actual, String... identities) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(PreparedStatementCreator.class), org.mockito.ArgumentMatchers.<ResultSetExtractor<Map<String, String>>>any()))
                .thenReturn(actual);
        when(jdbc.query(any(PreparedStatementCreator.class), org.mockito.ArgumentMatchers.<RowMapper<DatasetValues>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<DatasetValues> mapper = invocation.getArgument(1);
                    var rows = new ArrayList<DatasetValues>();
                    for (int i = 0; i < identities.length; i++) {
                        var rs = mock(ResultSet.class);
                        when(rs.getObject("event_time", Long.class)).thenReturn(1790643723123456789L);
                        when(rs.getString("id")).thenReturn(identities[i]);
                        when(rs.getObject("value", Double.class)).thenReturn(null);
                        rows.add(mapper.mapRow(rs, i));
                    }
                    return rows;
                });
        return jdbc;
    }
    private DatasetReadQuery query() { return new DatasetReadQuery(List.of("value", "id", "event_time"), Map.of(), null, null, null, 2, null); }

    @Test void namesNullsAndNanosecondsSurviveWithoutDefaultTimezone() throws Exception {
        var reader = new QuestDbBoundedReader(jdbc(Map.of("ts", "TIMESTAMP_NS", "id", "SYMBOL", "value", "DOUBLE"), "a", "b", "c"));
        var result = reader.read(definition(), query(), null, row -> {
            assertNull(row.get("value", Double.class));
            assertEquals(Instant.parse("2026-09-29T01:02:03.123456789Z"), row.get("event_time", Instant.class));
            return row.get("id", String.class);
        });
        assertEquals(List.of("a", "b"), result.rows());
        assertTrue(result.hasMore());
        assertEquals("b", result.nextCursor().keyValues().get(1));
        assertNull(result.sourceVersion());
        var encoded = QuestDbBoundedReader.storageValue(definition().columns().getFirst(), result.nextCursor().keyValues().getFirst());
        assertEquals(1790643723123456789L, encoded);
    }
    @Test void schemaMismatchAndBoundaryDuplicateCannotYieldACursor() throws Exception {
        var wrongSchema = new QuestDbBoundedReader(jdbc(Map.of("ts", "TIMESTAMP", "id", "SYMBOL", "value", "DOUBLE"), "a"));
        assertThrows(IllegalStateException.class, () -> wrongSchema.read(definition(), query(), null, row -> row));
        var duplicate = new QuestDbBoundedReader(jdbc(Map.of("ts", "TIMESTAMP_NS", "id", "SYMBOL", "value", "DOUBLE"), "a", "b", "b"));
        assertThrows(IllegalStateException.class, () -> duplicate.read(definition(), query(), null, row -> row));
    }
}
