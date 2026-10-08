package com.zoutrankil.data.derived.mapper;


import static org.junit.jupiter.api.Assertions.*;

import com.zoutrankil.data.domain.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BacktestDailyCacheMappingTest {
    private static final String VERSION = "a".repeat(64);
    private final BacktestDailyCacheMapper mapper = new BacktestDailyCacheMapper();

    @Test void allFourteenFieldsRoundTripWithCompleteVersionedKey() {
        var daily = new BacktestDaily(LocalDate.of(2026, 9, 21), "000001.SZ",
                11.7, 11.73, 11.6, 11.73, 817555.31, 954184.68,
                139.008, 12.87, 10.53, 0, 0);
        var source = new BacktestDailyCache(daily, VERSION);
        assertEquals(source, mapper.fromValues(mapper.values(source)));
        assertEquals(14, mapper.values(source).columns().size());
        assertEquals(new BacktestDailyCacheKey(daily.tradeDate(), daily.tsCode(), VERSION), source.key());
    }

    @Test void sourceVersionMustBeExactSha256AndNullMetricsRemainNull() {
        var daily = new BacktestDaily(LocalDate.of(2026, 9, 21), "000001.SZ",
                null, null, null, null, null, null, null, null, null, null, null);
        var cache = new BacktestDailyCache(daily, VERSION);
        assertEquals(cache, mapper.fromValues(mapper.values(cache)));
        assertThrows(IllegalArgumentException.class, () -> new BacktestDailyCache(daily, "not-a-version"));
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestDailyCacheKey(daily.tradeDate(), "bad-code", VERSION));
    }

    @Test void datasetPreservesMonthWalVersionedKeyAndReadOnlyOwnerBoundary() {
        var definition = BacktestDailyCacheDataset.DEFINITION;
        assertEquals("backtest_daily_cache", definition.objectName());
        assertEquals(List.of("trade_date", "ts_code", "source_version"), definition.businessKey());
        assertEquals(definition.businessKey(), definition.dedupKey());
        assertEquals(DatasetDefinition.Partition.MONTH, definition.partition());
        assertTrue(definition.wal());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertThrows(IllegalArgumentException.class,
                () -> definition.requireCapability(DatasetDefinition.Capability.WRITE));
    }
}
