package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.DatasetReadCursor;
import com.zoutrankil.questdbwithdata.domain.EtfAdj;
import com.zoutrankil.questdbwithdata.domain.EtfAdjDataset;
import com.zoutrankil.questdbwithdata.domain.EtfAdjKey;
import com.zoutrankil.questdbwithdata.mapper.EtfAdjMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded typed reads against a D015 isolated target. */
@Repository
public class EtfAdjReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = EtfAdjDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final String table;
    private final EtfAdjMapper mapper = new EtfAdjMapper();

    public EtfAdjReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.etf-adj-table:java_d015_etf_adj_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader);
        com.zoutrankil.questdbwithdata.service.EtfAdjJobService.requireIsolatedTableName(table);
        this.table = table;
    }
    @Override public DatasetDefinition definition() { return EtfAdjDataset.definition(table); }
    public DatasetReadPage<EtfAdj> findByKey(EtfAdjKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS, Map.of("ts_code", key.tsCode(), "trade_date", key.tradeDate()),
                null, null, null, 1, null));
    }
    public DatasetReadPage<EtfAdj> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing half-open date range required");
        return find(new DatasetReadQuery(COLUMNS, Map.of(), "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }
    public DatasetReadPage<EtfAdj> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("Typed etf_adj reads require all three frozen fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
