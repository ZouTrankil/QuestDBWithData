package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.EtfFactor;
import com.zoutrankil.data.domain.EtfFactorDataset;
import com.zoutrankil.data.domain.EtfFactorKey;
import com.zoutrankil.data.mapper.EtfFactorMapper;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.service.EtfFactorJobService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded explicit-column reads for a D017 isolated acceptance table. */
@Repository
public class EtfFactorReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final String table;
    private final EtfFactorMapper mapper = new EtfFactorMapper();
    public EtfFactorReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.etf-factor-table:java_d017_etf_factor_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader); EtfFactorJobService.requireIsolatedTableName(table); this.table = table;
    }
    @Override public DatasetDefinition definition() { return EtfFactorDataset.definition(table); }
    public DatasetReadPage<EtfFactor> findByKey(EtfFactorKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(columns(), Map.of("ts_code", key.tsCode(), "trade_date", key.tradeDate()), null, null, null, 1, null));
    }
    public DatasetReadPage<EtfFactor> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                 int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing half-open etf_factor date range required");
        return find(new DatasetReadQuery(columns(), Map.of(), "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }
    public DatasetReadPage<EtfFactor> find(DatasetReadQuery query) {
        if (query.columns().size() != columns().size() || !new HashSet<>(query.columns()).equals(new HashSet<>(columns())))
            throw new IllegalArgumentException("Typed etf_factor reads require every frozen physical column");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
    private static List<String> columns() { return EtfFactorDataset.DEFINITION.columns().stream().map(DatasetDefinition.Column::logicalName).toList(); }
}
