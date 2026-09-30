package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.IndexDailyBasicMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.*;

/** Explicit typed bounded reads for a task-scoped D020 target. */
@Repository
public class IndexDailyBasicReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = IndexDailyBasicDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final String table;
    private final IndexDailyBasicMapper mapper = new IndexDailyBasicMapper();
    public IndexDailyBasicReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.index-daily-basic-table:java_d020_index_daily_basic_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader);
        com.zoutrankil.data.service.IndexDailyBasicJobService.requireIsolatedTableName(table);
        this.table = table;
    }
    @Override public DatasetDefinition definition() { return IndexDailyBasicDataset.definition(table); }
    public DatasetReadPage<IndexDailyBasic> findByKey(IndexDailyBasicKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS, Map.of("ts_code", key.tsCode(), "trade_date", key.tradeDate()),
                null, null, null, 1, null));
    }
    public DatasetReadPage<IndexDailyBasic> findRange(String code, LocalDate fromInclusive, LocalDate toExclusive,
            int pageSize, DatasetReadCursor cursor) {
        if (!com.zoutrankil.data.service.IndexDailyBasicUniverse.valid(code)
                || fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive))
            throw new IllegalArgumentException("Known D020 index and increasing half-open date range required");
        return find(new DatasetReadQuery(COLUMNS, Map.of("ts_code", code), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }
    public DatasetReadPage<IndexDailyBasic> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("D020 typed reads require all twelve physical fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
