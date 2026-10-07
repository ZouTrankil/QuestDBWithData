package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MarketBreadthDailyViewMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded typed alias reads; the shared reader guards its exact MV binding and freshness. */
@Repository
public class MarketBreadthDailyViewReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final MarketBreadthDailyViewMapper mapper = new MarketBreadthDailyViewMapper();

    public MarketBreadthDailyViewReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return MarketBreadthDailyViewDataset.DEFINITION; }

    public DatasetReadPage<MarketBreadthDailyView> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<MarketBreadthDailyView> findForDate(LocalDate date) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("trade_date", date),
                null, null, null, 1, null));
    }

    public DatasetReadPage<MarketBreadthDailyView> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                            int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }
}
