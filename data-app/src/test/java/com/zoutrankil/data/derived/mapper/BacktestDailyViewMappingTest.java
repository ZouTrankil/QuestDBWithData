package com.zoutrankil.data.derived.mapper;


import static org.junit.jupiter.api.Assertions.*;

import com.zoutrankil.data.domain.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BacktestDailyViewMappingTest {
    private final BacktestDailyViewMapper mapper = new BacktestDailyViewMapper();

    @Test void allThirteenFieldsRoundTripWithPromotedLongSuspensionFlag() {
        var source = new BacktestDailyViewValue(LocalDate.of(2026, 9, 17), "000001.SZ",
                11.68, 11.74, 11.57, 11.61, 691925.35, 806153.13,
                139.008, 12.87, 10.53, 0L, 0);
        assertEquals(source, mapper.fromValues(mapper.values(source)));
        assertEquals(13, mapper.values(source).columns().size());
        assertEquals(new BacktestDailyKey(source.tradeDate(), source.tsCode()), source.key());
    }

    @Test void nullableMetricsRemainNullAndInvalidKeyIsRejected() {
        var source = new BacktestDailyViewValue(LocalDate.of(2026, 9, 17), "000001.SZ",
                null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(source, mapper.fromValues(mapper.values(source)));
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestDailyViewValue(source.tradeDate(), "bad-code",
                        null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test void ordinaryViewHasNoPhysicalWalPartitionDedupOrDirectWrites() {
        var definition = BacktestDailyViewDataset.DEFINITION;
        assertEquals("v_backtest_daily", definition.objectName());
        assertEquals(DatasetDefinition.ObjectKind.VIEW, definition.objectKind());
        assertEquals(List.of("trade_date", "ts_code"), definition.businessKey());
        assertTrue(definition.dedupKey().isEmpty());
        assertNull(definition.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.NONE, definition.partition());
        assertFalse(definition.wal());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertEquals(DatasetDefinition.StorageType.LONG,
                definition.columns().stream().filter(c -> c.storageName().equals("is_suspended")).findFirst().orElseThrow().storageType());
        assertThrows(IllegalArgumentException.class,
                () -> definition.requireCapability(DatasetDefinition.Capability.WRITE));
    }
}
