package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.RetailSentimentDailyCacheMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded historical cache reads; the shared reader pins the physical table version for each page. */
@Repository
public class RetailSentimentDailyCacheReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final RetailSentimentDailyCacheMapper mapper = new RetailSentimentDailyCacheMapper();

    public RetailSentimentDailyCacheReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return RetailSentimentDailyCacheDataset.DEFINITION; }

    public DatasetReadPage<RetailSentimentDailyCache> findPage(DatasetReadQuery query) {
        // The row's SHA-256 source_version is a business generation, not a live physical snapshot token.
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<RetailSentimentDailyCache> findKey(RetailSentimentDailyCacheKey key) {
        Objects.requireNonNull(key, "complete cache key required");
        return findPage(new DatasetReadQuery(definition().storageColumns(),
                Map.of("trade_date", key.tradeDate(), "source_version", key.sourceVersion()),
                null, null, null, 1, null));
    }

    public DatasetReadPage<RetailSentimentDailyCache> findVersion(LocalDate date, String sourceVersion) {
        return findKey(new RetailSentimentDailyCacheKey(date, sourceVersion));
    }

    /** Includes every historical source generation for this date; no latest-generation guess. */
    public DatasetReadPage<RetailSentimentDailyCache> findForDate(LocalDate date, int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("trade_date", date),
                null, null, null, pageSize, cursor));
    }

    public DatasetReadPage<RetailSentimentDailyCache> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                              int pageSize, DatasetReadCursor cursor) {
        return findRange(fromInclusive, toExclusive, null, pageSize, cursor);
    }

    /** End date is exclusive; null sourceVersion reads all generations in this explicit interval. */
    public DatasetReadPage<RetailSentimentDailyCache> findRange(LocalDate fromInclusive, LocalDate toExclusive,
            String sourceVersion, int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        if (sourceVersion != null) RetailSentimentDailyCacheKey.requireVersion(sourceVersion);
        Map<String,Object> equalities = sourceVersion == null ? Map.of() : Map.of("source_version", sourceVersion);
        return findPage(new DatasetReadQuery(definition().storageColumns(), equalities, "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }
}
