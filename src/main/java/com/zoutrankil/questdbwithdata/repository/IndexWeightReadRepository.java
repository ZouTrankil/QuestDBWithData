package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetReadCursor;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.IndexWeight;
import com.zoutrankil.questdbwithdata.domain.IndexWeightDataset;
import com.zoutrankil.questdbwithdata.domain.IndexWeightKey;
import com.zoutrankil.questdbwithdata.mapper.IndexWeightMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded typed reads for the explicit D021 isolated target. */
@Repository
public class IndexWeightReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = IndexWeightDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final String table;
    private final IndexWeightMapper mapper = new IndexWeightMapper();
    public IndexWeightReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.index-weight-table:java_d021_index_weight_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader);
        com.zoutrankil.questdbwithdata.service.IndexWeightJobService.requireIsolatedTableName(table);
        this.table = table;
    }
    @Override public DatasetDefinition definition() { return IndexWeightDataset.definition(table); }
    public DatasetReadPage<IndexWeight> findByKey(IndexWeightKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS, Map.of("index_code", key.indexCode(),
                "con_code", key.conCode(), "trade_date", key.tradeDate()), null, null, null, 1, null));
    }
    public DatasetReadPage<IndexWeight> findRange(String indexCode, LocalDate fromInclusive,
            LocalDate toExclusive, int pageSize, DatasetReadCursor cursor) {
        if (indexCode == null || !indexCode.matches("[0-9]{6}") || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive))
            throw new IllegalArgumentException("Six digit index and increasing half-open date range required");
        return find(new DatasetReadQuery(COLUMNS, Map.of("index_code", indexCode), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }
    public DatasetReadPage<IndexWeight> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("D021 typed reads require all eleven declared fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
