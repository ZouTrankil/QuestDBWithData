package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.BacktestDailyCacheCoverageMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded, read-only access to Python-published cache coverage receipts. */
@Repository
public class BacktestDailyCacheCoverageReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final BacktestDailyCacheCoverageMapper mapper = new BacktestDailyCacheCoverageMapper();

    public BacktestDailyCacheCoverageReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return BacktestDailyCacheCoverageDataset.DEFINITION; }

    public DatasetReadPage<BacktestDailyCacheCoverage> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<BacktestDailyCacheCoverage> findForDate(LocalDate date, int pageSize,
                                                                    DatasetReadCursor cursor) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("trade_date", date),
                null, null, null, pageSize, cursor));
    }

    /** End date is exclusive. */
    public DatasetReadPage<BacktestDailyCacheCoverage> findRange(LocalDate fromInclusive,
            LocalDate toExclusive, int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }

    public DatasetReadPage<BacktestDailyCacheCoverage> findKey(BacktestDailyCacheCoverageKey key) {
        Objects.requireNonNull(key, "complete coverage key required");
        return findPage(new DatasetReadQuery(definition().storageColumns(),
                Map.of("trade_date", key.tradeDate(), "source_version", key.sourceVersion()),
                null, null, null, 1, null));
    }
}
