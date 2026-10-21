package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.derived.mapper.BacktestDailyViewMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.derived.BacktestDailyKey;
import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.data.domain.derived.BacktestDailyViewValue;
import com.zoutrankil.data.repository.QuestDbBoundedReader;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** Bounded typed reads of the existing current backtest view. */
@Repository
public class BacktestDailyViewReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final BacktestDailyScope scope;
    private final BacktestDailyViewMapper mapper = new BacktestDailyViewMapper();

    public BacktestDailyViewReadRepository(QuestDbBoundedReader reader) {
        this(reader, BacktestDailyScope.LEGACY);
    }

    @Autowired
    public BacktestDailyViewReadRepository(QuestDbBoundedReader reader, BacktestDailyScope scope) {
        this.reader = Objects.requireNonNull(reader);
        this.scope = Objects.requireNonNull(scope);
    }

    @Override
    public DatasetDefinition definition() {
        return scope.definition();
    }

    public DatasetReadPage<BacktestDailyViewValue> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<BacktestDailyViewValue> findForDate(
            LocalDate date, int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(
                new DatasetReadQuery(
                        definition().storageColumns(),
                        Map.of("trade_date", date),
                        null,
                        null,
                        null,
                        pageSize,
                        cursor));
    }

    public DatasetReadPage<BacktestDailyViewValue> findRange(
            LocalDate fromInclusive,
            LocalDate toExclusive,
            int pageSize,
            DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive))
            throw new IllegalArgumentException("Increasing date range required");
        return findPage(
                new DatasetReadQuery(
                        definition().storageColumns(),
                        Map.of(),
                        "trade_date",
                        fromInclusive,
                        toExclusive,
                        pageSize,
                        cursor));
    }

    public DatasetReadPage<BacktestDailyViewValue> findKey(BacktestDailyKey key) {
        Objects.requireNonNull(key, "complete business key required");
        return findPage(
                new DatasetReadQuery(
                        definition().storageColumns(),
                        Map.of("trade_date", key.tradeDate(), "ts_code", key.tsCode()),
                        null,
                        null,
                        null,
                        1,
                        null));
    }
}
