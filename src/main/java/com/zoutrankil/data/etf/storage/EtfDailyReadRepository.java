package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.repository.QuestDbBoundedReader;


import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.etf.mapper.EtfDailyMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.*;

/** Bounded typed reads against a D014 isolated target. */
@Repository
public class EtfDailyReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = EtfDailyDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final String table;
    private final EtfDailyMapper mapper = new EtfDailyMapper();

    public EtfDailyReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.etf-daily-table:java_d014_etf_daily_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader);
        EtfDailyDataset.requireExecutionTable(table);
        this.table = table;
    }
    @Override public DatasetDefinition definition() { return EtfDailyDataset.definition(table); }
    public DatasetReadPage<EtfDaily> findByKey(EtfDailyKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS, Map.of("ts_code", key.tsCode(), "trade_date", key.tradeDate()),
                null, null, null, 1, null));
    }
    public DatasetReadPage<EtfDaily> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                  int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing half-open date range required");
        return find(new DatasetReadQuery(COLUMNS, Map.of(), "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }
    public DatasetReadPage<EtfDaily> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("Typed etf_daily reads require all eleven frozen fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
