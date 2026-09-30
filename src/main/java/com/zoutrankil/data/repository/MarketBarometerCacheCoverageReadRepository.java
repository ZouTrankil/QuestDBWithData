package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MarketBarometerCacheCoverageMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded typed reads of Python-published multi-product cache coverage receipts. */
@Repository
public class MarketBarometerCacheCoverageReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final MarketBarometerCacheCoverageMapper mapper = new MarketBarometerCacheCoverageMapper();

    public MarketBarometerCacheCoverageReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return MarketBarometerCacheCoverageDataset.DEFINITION; }

    public DatasetReadPage<MarketBarometerCacheCoverage> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<MarketBarometerCacheCoverage> findForDate(LocalDate date, int pageSize,
                                                                      DatasetReadCursor cursor) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("trade_date", date),
                null, null, null, pageSize, cursor));
    }

    public DatasetReadPage<MarketBarometerCacheCoverage> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                                    int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }

    public DatasetReadPage<MarketBarometerCacheCoverage> findKey(MarketBarometerCacheCoverageKey key) {
        Objects.requireNonNull(key, "complete coverage key required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(
                "trade_date", key.tradeDate(), "dataset_id", key.datasetId(),
                "source_version", key.sourceVersion()), null, null, null, 1, null));
    }
}
