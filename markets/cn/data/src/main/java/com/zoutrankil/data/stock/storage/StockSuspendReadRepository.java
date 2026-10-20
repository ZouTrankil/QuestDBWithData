package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.domain.stock.market.StockSuspendDataset;
import com.zoutrankil.data.domain.stock.market.StockSuspendKey;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.stock.mapper.StockSuspendMapper;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded full-row typed read, keyed by code plus business date. */
@Repository
public class StockSuspendReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS =
            StockSuspendDataset.DEFINITION.columns().stream()
                    .map(DatasetDefinition.Column::logicalName)
                    .toList();
    private final String table;
    private final CnTradeDateLayout layout;
    private final QuestDbBoundedReader reader;
    private final StockSuspendMapper mapper = new StockSuspendMapper();

    @Autowired
    public StockSuspendReadRepository(
            QuestDbBoundedReader reader,
            @Value("${app.read.stk-suspend-table:stk_suspend}") String table,
            @Value("${app.sync.stk-suspend-schema-version:1}") int version) {
        this(reader, table, CnTradeDateLayout.fromVersion(version));
    }

    public StockSuspendReadRepository(QuestDbBoundedReader reader) {
        this(reader, StockSuspendDataset.DEFAULT_TABLE, CnTradeDateLayout.LEGACY_V1);
    }

    public StockSuspendReadRepository(
            QuestDbBoundedReader reader, String table, CnTradeDateLayout layout) {
        DatasetDefinition.identifier(table);
        this.table = table;
        this.layout = Objects.requireNonNull(layout);
        this.reader = Objects.requireNonNull(reader);
    }

    @Override
    public DatasetDefinition definition() {
        return StockSuspendDataset.definition(table, layout);
    }

    @Override
    public DatasetDefinition definition(int version) {
        return StockSuspendDataset.definition(table, CnTradeDateLayout.fromVersion(version));
    }

    public DatasetReadPage<StockSuspend> findByKey(StockSuspendKey key) {
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

    /** Inclusive/exclusive business-date range. */
    public DatasetReadPage<StockSuspend> findRange(
            String tsCode,
            LocalDate fromInclusive,
            LocalDate toExclusive,
            int pageSize,
            DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive);
        Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive))
            throw new IllegalArgumentException("Increasing half-open date range required");
        Map<String, Object> equalities = tsCode == null ? Map.of() : Map.of("ts_code", tsCode);
        if (tsCode != null) new StockSuspendKey(tsCode, fromInclusive);
        return find(
                new DatasetReadQuery(
                        COLUMNS,
                        equalities,
                        "trade_date",
                        fromInclusive,
                        toExclusive,
                        pageSize,
                        cursor));
    }

    public DatasetReadPage<StockSuspend> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size()
                || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException(
                    "Typed StockSuspend read requires all frozen business fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
