package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.StockLimitMapper;
import com.zoutrankil.data.service.StockLimitJobService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.*;

/** Bounded typed reads against a D010 isolated target. */
@Repository
public class StockLimitReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = StockLimitDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final String table;
    private final StockLimitMapper mapper = new StockLimitMapper();

    public StockLimitReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.stk-limit-table:java_d010_stk_limit_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader);
        com.zoutrankil.data.service.StockLimitJobService.requireIsolatedTableName(table);
        this.table = table;
    }
    @Override public DatasetDefinition definition() { return StockLimitDataset.definition(table); }
    public DatasetReadPage<StockLimit> findByKey(StockLimitKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS, Map.of("ts_code", key.tsCode(), "trade_date", key.tradeDate()),
                null, null, null, 1, null));
    }
    public DatasetReadPage<StockLimit> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                  int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing half-open date range required");
        return find(new DatasetReadQuery(COLUMNS, Map.of(), "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }
    public DatasetReadPage<StockLimit> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("Typed stk_limit reads require all four frozen fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
