package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.BacktestDailyMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded, typed reads of the legacy table; deliberately exposes no write or sync entry point. */
@Repository
public class BacktestDailyReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final BacktestDailyMapper mapper = new BacktestDailyMapper();

    public BacktestDailyReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return BacktestDailyDataset.DEFINITION; }

    public DatasetReadPage<BacktestDaily> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<BacktestDaily> findForDate(LocalDate date, int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("trade_date", date),
                null, null, null, pageSize, cursor));
    }

    /** The end date is exclusive. */
    public DatasetReadPage<BacktestDaily> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                     int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }

    public DatasetReadPage<BacktestDaily> findKey(BacktestDailyKey key) {
        Objects.requireNonNull(key, "complete business key required");
        return findPage(new DatasetReadQuery(definition().storageColumns(),
                Map.of("trade_date", key.tradeDate(), "ts_code", key.tsCode()), null, null, null, 1, null));
    }
}
