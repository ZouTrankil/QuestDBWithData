package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.StockSuspendMapper;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.*;

/** Bounded full-row typed read, keyed by code plus business date. */
@Repository
public class StockSuspendReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = StockSuspendDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final StockSuspendMapper mapper = new StockSuspendMapper();
    public StockSuspendReadRepository(QuestDbBoundedReader reader) { this.reader = Objects.requireNonNull(reader); }
    @Override public DatasetDefinition definition() { return StockSuspendDataset.DEFINITION; }

    public DatasetReadPage<StockSuspend> findByKey(StockSuspendKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS,
                Map.of("ts_code", key.tsCode(), "trade_date", key.tradeDate()), null, null, null, 1, null));
    }

    /** Inclusive/exclusive business-date range. */
    public DatasetReadPage<StockSuspend> findRange(String tsCode, LocalDate fromInclusive, LocalDate toExclusive,
                                                   int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing half-open date range required");
        Map<String,Object> equalities = tsCode == null ? Map.of() : Map.of("ts_code", tsCode);
        if (tsCode != null) new StockSuspendKey(tsCode, fromInclusive);
        return find(new DatasetReadQuery(COLUMNS, equalities, "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }

    public DatasetReadPage<StockSuspend> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("Typed StockSuspend read requires all frozen business fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
