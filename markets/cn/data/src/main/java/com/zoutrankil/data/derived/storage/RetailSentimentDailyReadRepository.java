package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.derived.mapper.RetailSentimentDailyMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.derived.RetailSentimentDaily;
import com.zoutrankil.data.domain.derived.RetailSentimentDailyDataset;
import com.zoutrankil.data.repository.QuestDbBoundedReader;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** Bounded typed reads; the shared reader guards MV validity, catch-up and cursor versions. */
@Repository
public class RetailSentimentDailyReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final RetailSentimentDailyMapper mapper = new RetailSentimentDailyMapper();

    public RetailSentimentDailyReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override
    public DatasetDefinition definition() {
        return RetailSentimentDailyDataset.DEFINITION;
    }

    public DatasetReadPage<RetailSentimentDaily> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<RetailSentimentDaily> findForDate(LocalDate date) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(
                new DatasetReadQuery(
                        definition().storageColumns(),
                        Map.of("trade_date", date),
                        null,
                        null,
                        null,
                        1,
                        null));
    }

    public DatasetReadPage<RetailSentimentDaily> findRange(
            LocalDate fromInclusive,
            LocalDate toExclusive,
            int pageSize,
            DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive))
            throw new IllegalArgumentException("Increasing date range required");
        return findPage(
                new DatasetReadQuery(
                        definition().storageColumns(),
                        Map.of(),
                        "trade_date",
                        fromInclusive,
                        toExclusive,
                        pageSize,
                        cursor));
    }
}
