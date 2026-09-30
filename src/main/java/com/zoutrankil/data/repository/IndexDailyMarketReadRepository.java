package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.IndexDailyMarketMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.*;

/** Bounded typed reads for an explicitly isolated D019 target. */
@Repository
public class IndexDailyMarketReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = IndexDailyMarketDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final String table;
    private final IndexDailyMarketMapper mapper = new IndexDailyMarketMapper();
    public IndexDailyMarketReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.index-daily-market-table:java_d019_index_daily_market_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader);
        com.zoutrankil.data.service.IndexDailyMarketJobService.requireIsolatedTableName(table);
        this.table = table;
    }
    @Override public DatasetDefinition definition() { return IndexDailyMarketDataset.definition(table); }
    public DatasetReadPage<IndexDailyMarket> findByKey(IndexDailyMarketKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS, Map.of("ts_code", key.tsCode(), "trade_date", key.tradeDate()), null, null, null, 1, null));
    }
    public DatasetReadPage<IndexDailyMarket> findRange(String tsCode, LocalDate fromInclusive,
            LocalDate toExclusive, int pageSize, DatasetReadCursor cursor) {
        if (!com.zoutrankil.data.service.IndexDailyMarketUniverse.valid(tsCode) || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Known index and increasing half-open range required");
        return find(new DatasetReadQuery(COLUMNS, Map.of("ts_code", tsCode), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }
    public DatasetReadPage<IndexDailyMarket> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("Typed index_daily_market reads require all twelve fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
