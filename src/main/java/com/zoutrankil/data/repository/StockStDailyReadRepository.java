package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.StockStDailyMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed, explicit-column reads for the D012 isolated target. */
@Repository
public class StockStDailyReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = StockStDailyDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final String table;
    private final StockStDailyMapper mapper = new StockStDailyMapper();

    public StockStDailyReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.stk-st-daily-table:java_d012_stk_st_daily_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader);
        com.zoutrankil.data.service.StockStDailyJobService.requireAdmittedTableName(table);
        this.table = table;
    }
    @Override public DatasetDefinition definition() { return StockStDailyDataset.definition(table); }
    public DatasetReadPage<StockStDaily> findByKey(StockStDailyKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS, Map.of("ts_code", key.tsCode(), "timestamp", key.timestamp()),
                null, null, null, 1, null));
    }
    public DatasetReadPage<StockStDaily> findRange(LocalDate fromInclusive, LocalDate toExclusive,
            int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing half-open timestamp range required");
        return find(new DatasetReadQuery(COLUMNS, Map.of(), "timestamp", fromInclusive, toExclusive, pageSize, cursor));
    }
    public DatasetReadPage<StockStDaily> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("Typed stk_st_daily reads require all three frozen fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
