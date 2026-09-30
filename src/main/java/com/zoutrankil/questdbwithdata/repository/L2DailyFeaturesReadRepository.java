package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.DatasetReadCursor;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.L2DailyFeatures;
import com.zoutrankil.questdbwithdata.domain.L2DailyFeaturesDataset;
import com.zoutrankil.questdbwithdata.mapper.L2DailyFeaturesMapper;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** D086 typed reads project every source field and page by the full (ts,symbol) key. */
@Repository
public class L2DailyFeaturesReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final String table;
    private final L2DailyFeaturesMapper mapper = new L2DailyFeaturesMapper();

    public L2DailyFeaturesReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.l2-daily-features.target-table:l2_daily_features}") String table) {
        this.reader = reader;
        this.table = table;
    }

    @Override public DatasetDefinition definition() { return L2DailyFeaturesDataset.definition(table); }

    public DatasetReadPage<L2DailyFeatures> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<L2DailyFeatures> findForDate(LocalDate date, int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2DailyFeaturesMapper.columns(), Map.of("ts", date),
                null, null, null, pageSize, cursor));
    }

    public DatasetReadPage<L2DailyFeatures> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                       int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2DailyFeaturesMapper.columns(), Map.of(),
                "ts", fromInclusive, toExclusive, pageSize, cursor));
    }

    public DatasetReadPage<L2DailyFeatures> findForSymbolAndDate(String symbol, LocalDate date,
                                                                   int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2DailyFeaturesMapper.columns(), Map.of("ts", date, "symbol", symbol),
                null, null, null, pageSize, cursor));
    }
}
