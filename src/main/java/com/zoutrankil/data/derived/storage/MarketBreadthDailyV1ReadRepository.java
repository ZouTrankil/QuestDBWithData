package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.MarketBreadthDailyV1Mapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded reads of valid native breadth MV buckets; the shared reader checks refresh state. */
@Repository
public class MarketBreadthDailyV1ReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final MarketBreadthDailyV1Mapper mapper = new MarketBreadthDailyV1Mapper();

    public MarketBreadthDailyV1ReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return MarketBreadthDailyV1Dataset.DEFINITION; }

    public DatasetReadPage<MarketBreadthDailyV1> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<MarketBreadthDailyV1> findForDate(LocalDate date) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("trade_date", date),
                null, null, null, 1, null));
    }

    public DatasetReadPage<MarketBreadthDailyV1> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                            int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }
}
