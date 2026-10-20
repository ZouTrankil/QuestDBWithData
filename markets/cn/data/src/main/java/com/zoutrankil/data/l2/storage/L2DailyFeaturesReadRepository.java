package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesDataset;
import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** D086 typed reads project every source field and page by the full (ts,symbol) key. */
@Repository
public class L2DailyFeaturesReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final String table;
    private final CnTradeDateLayout layout;
    private final L2DailyFeaturesMapper mapper = new L2DailyFeaturesMapper();

    @Autowired
    public L2DailyFeaturesReadRepository(
            QuestDbBoundedReader reader,
            @Value("${app.sync.l2-daily-features.target-table:l2_daily_features}") String table) {
        this(reader, table, CnTradeDateLayout.LEGACY_V1);
    }

    /** 当前公共入口仍为 v1；新版仅由显式装配选择。 */
    public L2DailyFeaturesReadRepository(
            QuestDbBoundedReader reader, String table, CnTradeDateLayout layout) {
        DatasetDefinition.identifier(table);
        this.reader = Objects.requireNonNull(reader);
        this.table = table;
        this.layout = Objects.requireNonNull(layout);
    }

    @Override
    public DatasetDefinition definition() {
        return L2DailyFeaturesDataset.definition(table, layout);
    }

    public DatasetReadPage<L2DailyFeatures> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, values -> mapper.fromValues(values, layout));
    }

    public DatasetReadPage<L2DailyFeatures> findForDate(
            LocalDate date, int pageSize, DatasetReadCursor cursor) {
        return findPage(
                new DatasetReadQuery(
                        L2DailyFeaturesMapper.columns(layout),
                        Map.of(layout.physicalDateColumn("l2_daily_features"), date),
                        null,
                        null,
                        null,
                        pageSize,
                        cursor));
    }

    public DatasetReadPage<L2DailyFeatures> findRange(
            LocalDate fromInclusive,
            LocalDate toExclusive,
            int pageSize,
            DatasetReadCursor cursor) {
        return findPage(
                new DatasetReadQuery(
                        L2DailyFeaturesMapper.columns(layout),
                        Map.of(),
                        layout.physicalDateColumn("l2_daily_features"),
                        fromInclusive,
                        toExclusive,
                        pageSize,
                        cursor));
    }

    public DatasetReadPage<L2DailyFeatures> findForSymbolAndDate(
            String symbol, LocalDate date, int pageSize, DatasetReadCursor cursor) {
        return findPage(
                new DatasetReadQuery(
                        L2DailyFeaturesMapper.columns(layout),
                        Map.of(
                                layout.physicalDateColumn("l2_daily_features"),
                                date,
                                "symbol",
                                symbol),
                        null,
                        null,
                        null,
                        pageSize,
                        cursor));
    }
}
