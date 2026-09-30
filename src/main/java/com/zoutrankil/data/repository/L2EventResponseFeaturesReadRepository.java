package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.L2EventResponseFeatures;
import com.zoutrankil.data.domain.L2EventResponseFeaturesDataset;
import com.zoutrankil.data.domain.L2EventResponseFeaturesKey;
import com.zoutrankil.data.mapper.L2EventResponseFeaturesMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** Bounded typed D088 reads with all 73 explicit columns and the three-part keyset cursor. */
@Repository
public class L2EventResponseFeaturesReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final String table;
    private final L2EventResponseFeaturesMapper mapper = new L2EventResponseFeaturesMapper();

    public L2EventResponseFeaturesReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.l2-event-response-features.target-table:l2_event_response_features}") String table) {
        this.reader = reader; this.table = table;
    }

    @Override public DatasetDefinition definition() { return L2EventResponseFeaturesDataset.definition(table); }
    public DatasetReadPage<L2EventResponseFeatures> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }
    public DatasetReadPage<L2EventResponseFeatures> findForKey(L2EventResponseFeaturesKey key, int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2EventResponseFeaturesMapper.columns(),
                Map.of("symbol", key.symbol(), "minute", key.minute(), "event_type", key.eventType()),
                null, null, null, pageSize, cursor));
    }
    public DatasetReadPage<L2EventResponseFeatures> findForSymbolDateAndType(
            String symbol, LocalDate date, String eventType, int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2EventResponseFeaturesMapper.columns(),
                Map.of("symbol", symbol, "trade_date", date, "event_type", eventType),
                null, null, null, pageSize, cursor));
    }
    public DatasetReadPage<L2EventResponseFeatures> findRange(Instant fromInclusive, Instant toExclusive,
            int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2EventResponseFeaturesMapper.columns(), Map.of(),
                "minute", fromInclusive, toExclusive, pageSize, cursor));
    }
}
