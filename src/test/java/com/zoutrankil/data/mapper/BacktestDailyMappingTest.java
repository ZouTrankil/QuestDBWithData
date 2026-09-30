package com.zoutrankil.data.mapper;

import static org.junit.jupiter.api.Assertions.*;

import com.zoutrankil.data.domain.*;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BacktestDailyMappingTest {
    private final BacktestDailyMapper mapper = new BacktestDailyMapper();

    @Test void allThirteenFieldsRoundTripWithoutChangingNullsOrPrecision() {
        var source = new BacktestDaily(LocalDate.of(2026, 9, 17), "000001.SZ", 11.68, 11.74, 11.57,
                11.61, 691925.35, 806153.13, 139.008, 12.87, 10.53, 0, 1);
        var restored = mapper.fromValues(mapper.values(source));
        assertEquals(source, restored);
        assertEquals(13, mapper.values(source).columns().size());
    }

    @Test void nullableMetricsAndFlagsRemainNull() {
        var source = new BacktestDaily(LocalDate.of(2026, 9, 17), "000001.SZ", null, null, null, null,
                null, null, null, null, null, null, null);
        assertEquals(source, mapper.fromValues(mapper.values(source)));
    }

    @Test void definitionPreservesAuditedPhysicalKeyAndForbidsWrites() {
        var definition = BacktestDailyDataset.DEFINITION;
        assertEquals("backtest_daily", definition.objectName());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertEquals(DatasetDefinition.Partition.DAY, definition.partition());
        assertTrue(definition.wal());
        assertEquals(java.util.List.of("trade_date", "ts_code"), definition.businessKey());
        assertEquals(java.util.List.of("trade_date", "ts_code"), definition.dedupKey());
        assertThrows(IllegalArgumentException.class,
                () -> definition.requireCapability(DatasetDefinition.Capability.WRITE));
    }

    @Test void keyRequiresCalendarDayAndQualifiedEquityCode() {
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestDailyKey(LocalDate.of(2026, 9, 17), "000001"));
        assertThrows(NullPointerException.class, () -> new BacktestDailyKey(null, "000001.SZ"));
    }
}
