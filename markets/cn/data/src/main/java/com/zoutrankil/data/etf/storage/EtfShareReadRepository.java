package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.fund.EtfShare;
import com.zoutrankil.data.domain.fund.EtfShareDataset;
import com.zoutrankil.data.domain.fund.EtfShareKey;
import com.zoutrankil.data.etf.mapper.EtfShareMapper;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.beans.factory.annotation.Autowired;
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
    private static final List<String> COLUMNS =
            EtfShareDataset.DEFINITION.columns().stream()
                    .map(DatasetDefinition.Column::logicalName)
                    .toList();
    private final CnTradeDateLayout layout;
    private final QuestDbBoundedReader reader;
    private final String table;
    private final EtfShareMapper mapper = new EtfShareMapper();

    @Autowired
    public EtfShareReadRepository(
            QuestDbBoundedReader reader,
            @Value("${app.sync.etf-share-table:java_d016_etf_share_acceptance}") String table,
            @Value("${app.sync.etf-share-schema-version:1}") int version) {
        this(reader, table, CnTradeDateLayout.fromVersion(version));
    }

    public EtfShareReadRepository(QuestDbBoundedReader reader, String table) {
        this(reader, table, CnTradeDateLayout.LEGACY_V1);
    }

    public EtfShareReadRepository(
            QuestDbBoundedReader reader, String table, CnTradeDateLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.reader = Objects.requireNonNull(reader);
        EtfShareDataset.requireIsolatedTable(table);
        this.table = table;
    }

    @Override
    public DatasetDefinition definition() {
        return EtfShareDataset.definition(table, layout);
    }

    @Override
    public DatasetDefinition definition(int version) {
        return EtfShareDataset.definition(table, CnTradeDateLayout.fromVersion(version));
    }

    public DatasetReadPage<EtfShare> findByKey(EtfShareKey key) {
        Objects.requireNonNull(key);
        return find(
                new DatasetReadQuery(
                        COLUMNS,
                        Map.of("ts_code", key.tsCode(), "trade_date", key.tradeDate()),
                        null,
                        null,
                        null,
                        1,
                        null));
    }

    public DatasetReadPage<EtfShare> findRange(
            LocalDate fromInclusive,
            LocalDate toExclusive,
            int pageSize,
            DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive);
        Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive))
            throw new IllegalArgumentException(
                    "Increasing half-open etf_share date range required");
        return find(
                new DatasetReadQuery(
                        COLUMNS,
                        Map.of(),
                        "trade_date",
                        fromInclusive,
                        toExclusive,
                        pageSize,
                        cursor));
    }

    public DatasetReadPage<EtfShare> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size()
                || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException(
                    "Typed etf_share reads require all six frozen fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
