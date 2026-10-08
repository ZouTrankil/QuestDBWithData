package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.RetailSentimentDailyViewMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded typed reads; the shared reader guards MV validity, catch-up and cursor versions. */
@Repository
public class RetailSentimentDailyViewReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final RetailSentimentDailyViewMapper mapper = new RetailSentimentDailyViewMapper();

    public RetailSentimentDailyViewReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return RetailSentimentDailyViewDataset.DEFINITION; }

    public DatasetReadPage<RetailSentimentDailyView> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<RetailSentimentDailyView> findForDate(LocalDate date) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("trade_date", date),
                null, null, null, 1, null));
    }

    public DatasetReadPage<RetailSentimentDailyView> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                           int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }
}
