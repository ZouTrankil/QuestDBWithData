package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.DatasetReadCursor;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeatures;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeaturesDataset;
import com.zoutrankil.questdbwithdata.mapper.L2IntradayBarFeaturesMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** Bounded typed D087 reads with an explicit full 60-column projection and keyset cursor. */
@Repository
public class L2IntradayBarFeaturesReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final String table;
    private final L2IntradayBarFeaturesMapper mapper = new L2IntradayBarFeaturesMapper();

    public L2IntradayBarFeaturesReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.l2-intraday-bar-features.target-table:l2_intraday_bar_features}") String table) {
        this.reader = reader;
        this.table = table;
    }

    @Override public DatasetDefinition definition() { return L2IntradayBarFeaturesDataset.definition(table); }

    public DatasetReadPage<L2IntradayBarFeatures> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<L2IntradayBarFeatures> findForSymbolAndDate(
            String symbol, LocalDate date, int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2IntradayBarFeaturesMapper.columns(),
                Map.of("symbol", symbol, "trade_date", date), null, null, null, pageSize, cursor));
    }

    public DatasetReadPage<L2IntradayBarFeatures> findRange(Instant fromInclusive, Instant toExclusive,
                                                              int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2IntradayBarFeaturesMapper.columns(), Map.of(),
                "minute", fromInclusive, toExclusive, pageSize, cursor));
    }
}
