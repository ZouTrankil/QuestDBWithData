package com.zoutrankil.data.mapper;

import static org.junit.jupiter.api.Assertions.*;

import com.zoutrankil.data.domain.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MarketBarometerCacheCoverageMappingTest {
    private static final String VERSION = "a".repeat(64);
    private static final String DIGEST = "b".repeat(64);
    private final MarketBarometerCacheCoverageMapper mapper = new MarketBarometerCacheCoverageMapper();

    @Test void allFiveFieldsRoundTripForNonemptyAndEmptyDayReceipts() {
        for (long count : new long[]{0, 1}) {
            var source = new MarketBarometerCacheCoverage(LocalDate.of(2026, 9, 18),
                    "market_breadth_daily", VERSION, count, DIGEST);
            assertEquals(source, mapper.fromValues(mapper.values(source)));
            assertEquals(5, mapper.values(source).columns().size());
            assertEquals(new MarketBarometerCacheCoverageKey(source.tradeDate(), source.datasetId(), VERSION),
                    source.key());
        }
    }

    @Test void completeKeyAndDigestRejectMalformedOrImpossibleReceipts() {
        var day = LocalDate.of(2026, 9, 18);
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBarometerCacheCoverageKey(day, "bad dataset", VERSION));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBarometerCacheCoverageKey(day, "market_breadth_daily", "bad-version"));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBarometerCacheCoverage(day, "market_breadth_daily", VERSION, -1, DIGEST));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBarometerCacheCoverage(day, "market_breadth_daily", VERSION, 2, DIGEST));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBarometerCacheCoverage(day, "market_breadth_daily", VERSION, 1, "bad-digest"));
    }

    @Test void definitionPreservesMonthWalFullDedupKeyAndReadOnlyPublisherBoundary() {
        var definition = MarketBarometerCacheCoverageDataset.DEFINITION;
        assertEquals("market_barometer_cache_coverage", definition.objectName());
        assertEquals(List.of("trade_date", "dataset_id", "source_version"), definition.businessKey());
        assertEquals(definition.businessKey(), definition.dedupKey());
        assertEquals(DatasetDefinition.Partition.MONTH, definition.partition());
        assertTrue(definition.wal());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertThrows(IllegalArgumentException.class,
                () -> definition.requireCapability(DatasetDefinition.Capability.WRITE));
    }
}
