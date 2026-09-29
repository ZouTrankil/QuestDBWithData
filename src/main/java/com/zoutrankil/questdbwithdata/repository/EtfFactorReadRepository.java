package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.DatasetReadCursor;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.EtfFactor;
import com.zoutrankil.questdbwithdata.domain.EtfFactorDataset;
import com.zoutrankil.questdbwithdata.domain.EtfFactorKey;
import com.zoutrankil.questdbwithdata.mapper.EtfFactorMapper;
import com.zoutrankil.questdbwithdata.repository.QuestDbBoundedReader;
import com.zoutrankil.questdbwithdata.service.EtfFactorJobService;
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
