package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.fund.EtfDaily;
import com.zoutrankil.data.domain.fund.EtfDailyDataset;
import com.zoutrankil.data.domain.fund.EtfDailyKey;
import com.zoutrankil.data.etf.mapper.EtfDailyMapper;
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

/** Bounded typed reads against a D014 isolated target. */
@Repository
public class EtfDailyReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS =
            EtfDailyDataset.DEFINITION.columns().stream()
                    .map(DatasetDefinition.Column::logicalName)
                    .toList();
    private final CnTradeDateLayout layout;
    private final QuestDbBoundedReader reader;
    private final String table;
    private final EtfDailyMapper mapper = new EtfDailyMapper();

    @Autowired
    public EtfDailyReadRepository(
            QuestDbBoundedReader reader,
            @Value("${app.sync.etf-daily-table:java_d014_etf_daily_acceptance}") String table,
            @Value("${app.sync.etf-daily-schema-version:1}") int schemaVersion) {
        this(reader, table, CnTradeDateLayout.fromVersion(schemaVersion));
    }

    public EtfDailyReadRepository(QuestDbBoundedReader reader, String table) {
        this(reader, table, CnTradeDateLayout.LEGACY_V1);
    }

    public EtfDailyReadRepository(
            QuestDbBoundedReader reader, String table, CnTradeDateLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.reader = Objects.requireNonNull(reader);
        EtfDailyDataset.requireExecutionTable(table);
        this.table = table;
    }

    @Override
    public DatasetDefinition definition() {
        return EtfDailyDataset.definition(table, layout);
    }

    @Override
    public DatasetDefinition definition(int version) {
        return EtfDailyDataset.definition(table, CnTradeDateLayout.fromVersion(version));
    }

    public DatasetReadPage<EtfDaily> findByKey(EtfDailyKey key) {
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

    public DatasetReadPage<EtfDaily> findRange(
            LocalDate fromInclusive,
            LocalDate toExclusive,
            int pageSize,
            DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive);
        Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive))
            throw new IllegalArgumentException("Increasing half-open date range required");
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

    public DatasetReadPage<EtfDaily> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size()
                || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException(
                    "Typed etf_daily reads require all eleven frozen fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
