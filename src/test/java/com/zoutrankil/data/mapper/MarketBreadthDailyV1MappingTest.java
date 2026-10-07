package com.zoutrankil.data.mapper;

import static org.junit.jupiter.api.Assertions.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.materializedview.MarketBreadthDailyV1MaterializedView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MarketBreadthDailyV1MappingTest {
    private final MarketBreadthDailyV1Mapper mapper = new MarketBreadthDailyV1Mapper();

    @Test void allSevenFieldsRoundTripIncludingNullableAggregates() {
        for (var row : List.of(
                new MarketBreadthDailyV1(LocalDate.of(2026, 9, 17), 5552, 2575, 2820, 157,
                        0.12166565201729085, 18343.331240500058),
                new MarketBreadthDailyV1(LocalDate.of(2026, 9, 18), 1, 0, 0, 0, null, null))) {
            assertEquals(row, mapper.fromValues(mapper.values(row)));
            assertEquals(7, mapper.values(row).columns().size());
        }
        assertEquals(LocalDate.of(2026, 9, 17), mapper.fromStorage(new MarketBreadthDailyV1MaterializedView(
                Instant.parse("2026-09-17T00:00:00Z"), 5552L, 2575L, 2820L, 157L,
                0.12166565201729085, 18343.331240500058)).tradeDate());
    }

    @Test void definitionUsesNaturalDailyKeyAndRejectsDirectWrites() {
        var definition = MarketBreadthDailyV1Dataset.DEFINITION;
        assertEquals(DatasetDefinition.ObjectKind.MATERIALIZED_VIEW, definition.objectKind());
        assertEquals(List.of("trade_date"), definition.businessKey());
        assertTrue(definition.dedupKey().isEmpty());
        assertEquals(DatasetDefinition.Partition.MONTH, definition.partition());
        assertTrue(definition.wal());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertThrows(IllegalArgumentException.class,
                () -> definition.requireCapability(DatasetDefinition.Capability.WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBreadthDailyV1(LocalDate.of(2026, 9, 17), 1, 2, 0, 0, 1.0, 1.0));
    }
}
