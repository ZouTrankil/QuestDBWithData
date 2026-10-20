package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.stock.basic.StockStDaily;
import com.zoutrankil.data.domain.stock.basic.StockStDailyDataset;
import com.zoutrankil.data.domain.stock.basic.StockStDailyKey;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.stock.domain.StockExecutionTables;
import com.zoutrankil.data.stock.mapper.StockStDailyMapper;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed, explicit-column reads for the D012 isolated target. */
@Repository
public class StockStDailyReadRepository implements DatasetImplementation {
    private final CnTradeDateLayout layout;
    private final QuestDbBoundedReader reader;
    private final String table;
    private final StockStDailyMapper mapper = new StockStDailyMapper();

    @Autowired
    public StockStDailyReadRepository(
            QuestDbBoundedReader reader,
            @Value("${app.sync.stk-st-daily-table:java_d012_stk_st_daily_acceptance}") String table,
            @Value("${app.sync.stk-st-daily-schema-version:1}") int schemaVersion) {
        this(reader, table, CnTradeDateLayout.fromVersion(schemaVersion));
    }

    public StockStDailyReadRepository(QuestDbBoundedReader reader, String table) {
        this(reader, table, CnTradeDateLayout.LEGACY_V1);
    }

    public StockStDailyReadRepository(
            QuestDbBoundedReader reader, String table, CnTradeDateLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.reader = Objects.requireNonNull(reader);
        StockExecutionTables.requireStockStDaily(table);
        this.table = table;
    }

    @Override
    public DatasetDefinition definition() {
        return StockStDailyDataset.definition(table, layout);
    }

    @Override
    public DatasetDefinition definition(int version) {
        return StockStDailyDataset.definition(table, CnTradeDateLayout.fromVersion(version));
    }

    private List<String> columns() {
        return definition().columns().stream().map(DatasetDefinition.Column::logicalName).toList();
    }

    public DatasetReadPage<StockStDaily> findByKey(StockStDailyKey key) {
        Objects.requireNonNull(key);
        return find(
                new DatasetReadQuery(
                        columns(),
                        Map.of(
                                "ts_code",
                                key.tsCode(),
                                StockStDailyDataset.logicalDateColumn(layout),
                                key.timestamp()),
                        null,
                        null,
                        null,
                        1,
                        null));
    }

    public DatasetReadPage<StockStDaily> findRange(
            LocalDate fromInclusive,
            LocalDate toExclusive,
            int pageSize,
            DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive);
        Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive))
            throw new IllegalArgumentException("Increasing half-open timestamp range required");
        return find(
                new DatasetReadQuery(
                        columns(),
                        Map.of(),
                        StockStDailyDataset.logicalDateColumn(layout),
                        fromInclusive,
                        toExclusive,
                        pageSize,
                        cursor));
    }

    public DatasetReadPage<StockStDaily> find(DatasetReadQuery query) {
        if (query.columns().size() != columns().size()
                || !new HashSet<>(query.columns()).equals(new HashSet<>(columns())))
            throw new IllegalArgumentException(
                    "Typed stk_st_daily reads require all three frozen fields");
        return reader.read(definition(), query, null, values -> mapper.fromValues(values, layout));
    }
}
