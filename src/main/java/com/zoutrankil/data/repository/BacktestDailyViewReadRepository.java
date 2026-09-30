package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.BacktestDailyViewMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded typed reads of the existing current backtest view. */
@Repository
public class BacktestDailyViewReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final BacktestDailyViewMapper mapper = new BacktestDailyViewMapper();

    public BacktestDailyViewReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return BacktestDailyViewDataset.DEFINITION; }

    public DatasetReadPage<BacktestDailyViewValue> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<BacktestDailyViewValue> findForDate(LocalDate date, int pageSize,
                                                               DatasetReadCursor cursor) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("trade_date", date),
                null, null, null, pageSize, cursor));
    }

    public DatasetReadPage<BacktestDailyViewValue> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                              int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }

    public DatasetReadPage<BacktestDailyViewValue> findKey(BacktestDailyKey key) {
        Objects.requireNonNull(key, "complete business key required");
        return findPage(new DatasetReadQuery(definition().storageColumns(),
                Map.of("trade_date", key.tradeDate(), "ts_code", key.tsCode()), null, null, null, 1, null));
    }
}
