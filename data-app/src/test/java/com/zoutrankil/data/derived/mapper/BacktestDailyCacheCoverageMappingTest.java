package com.zoutrankil.data.derived.mapper;


import static org.junit.jupiter.api.Assertions.*;

import com.zoutrankil.data.domain.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BacktestDailyCacheCoverageMappingTest {
    private static final String SOURCE_VERSION = "a".repeat(64);
    private static final String CONTENT_DIGEST = "b".repeat(64);
    private final BacktestDailyCacheCoverageMapper mapper = new BacktestDailyCacheCoverageMapper();

    @Test void fourFieldsRoundTripWithoutChangingHashOrLongRowCount() {
        var source = new BacktestDailyCacheCoverage(LocalDate.of(2026, 9, 21), SOURCE_VERSION,
                5_381L, CONTENT_DIGEST);
        assertEquals(source, mapper.fromValues(mapper.values(source)));
        assertEquals(Set.of("trade_date", "source_version", "row_count", "content_digest"),
                mapper.values(source).columns());
    }

    @Test void keyAndDigestMustBeLowercaseSha256AndCoverageMustBeNonempty() {
        var date = LocalDate.of(2026, 9, 21);
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestDailyCacheCoverageKey(date, "not-a-fingerprint"));
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestDailyCacheCoverage(date, SOURCE_VERSION, 0, CONTENT_DIGEST));
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestDailyCacheCoverage(date, SOURCE_VERSION, 1, "not-a-digest"));
    }

    @Test void definitionPreservesVersionedMonthWalDedupAndReadOnlyOwnerBoundary() {
        var definition = BacktestDailyCacheCoverageDataset.DEFINITION;
        assertEquals("backtest_daily_cache_coverage", definition.objectName());
        assertEquals(List.of("trade_date", "source_version"), definition.businessKey());
        assertEquals(List.of("trade_date", "source_version"), definition.dedupKey());
        assertEquals(DatasetDefinition.Partition.MONTH, definition.partition());
        assertTrue(definition.wal());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertThrows(IllegalArgumentException.class,
                () -> definition.requireCapability(DatasetDefinition.Capability.WRITE));
    }
}
