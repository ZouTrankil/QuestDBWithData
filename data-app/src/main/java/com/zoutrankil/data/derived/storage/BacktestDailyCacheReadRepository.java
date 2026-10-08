package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.BacktestDailyCacheMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded typed reads of Python-published versioned cache rows. */
@Repository
public class BacktestDailyCacheReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final BacktestDailyCacheMapper mapper = new BacktestDailyCacheMapper();

    public BacktestDailyCacheReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return BacktestDailyCacheDataset.DEFINITION; }

    public DatasetReadPage<BacktestDailyCache> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<BacktestDailyCache> findVersion(LocalDate date, String sourceVersion,
                                                            int pageSize, DatasetReadCursor cursor) {
        new BacktestDailyCacheCoverageKey(date, sourceVersion);
        return findPage(new DatasetReadQuery(definition().storageColumns(),
                Map.of("trade_date", date, "source_version", sourceVersion),
                null, null, null, pageSize, cursor));
    }

    public DatasetReadPage<BacktestDailyCache> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                          int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(),
                "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }

    public DatasetReadPage<BacktestDailyCache> findKey(BacktestDailyCacheKey key) {
        Objects.requireNonNull(key, "complete versioned key required");
        return findPage(new DatasetReadQuery(definition().storageColumns(),
                Map.of("trade_date", key.tradeDate(), "ts_code", key.tsCode(),
                        "source_version", key.sourceVersion()), null, null, null, 1, null));
    }
}
