package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.EtfShare;
import com.zoutrankil.data.domain.EtfShareDataset;
import com.zoutrankil.data.domain.EtfShareKey;
import com.zoutrankil.data.mapper.EtfShareMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded typed reads against an explicitly isolated D016 table. */
@Repository
public class EtfShareReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = EtfShareDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final String table;
    private final EtfShareMapper mapper = new EtfShareMapper();

    public EtfShareReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.etf-share-table:java_d016_etf_share_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader);
        EtfShareDataset.requireIsolatedTable(table);
        this.table = table;
    }
    @Override public DatasetDefinition definition() { return EtfShareDataset.definition(table); }
    public DatasetReadPage<EtfShare> findByKey(EtfShareKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS,
                Map.of("ts_code", key.tsCode(), "trade_date", key.tradeDate()), null, null, null, 1, null));
    }
    public DatasetReadPage<EtfShare> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                 int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing half-open etf_share date range required");
        return find(new DatasetReadQuery(COLUMNS, Map.of(), "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }
    public DatasetReadPage<EtfShare> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("Typed etf_share reads require all six frozen fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
