package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.EtfMarketOverviewDailyCacheMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded stored-generation reads; active publication is delegated by the canonical management job. */
@Repository
public class EtfMarketOverviewDailyCacheReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final EtfMarketOverviewDailyCacheMapper mapper = new EtfMarketOverviewDailyCacheMapper();

    public EtfMarketOverviewDailyCacheReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return EtfMarketOverviewDailyCacheDataset.DEFINITION; }

    public DatasetReadPage<EtfMarketOverviewDailyCache> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<EtfMarketOverviewDailyCache> findKey(EtfMarketOverviewDailyCacheKey key) {
        Objects.requireNonNull(key, "complete ETF cache key required");
        return findPage(new DatasetReadQuery(definition().storageColumns(),
                Map.of("trade_date", key.tradeDate(), "source_version", key.sourceVersion()),
                null, null, null, 1, null));
    }

    public DatasetReadPage<EtfMarketOverviewDailyCache> findVersion(LocalDate date, String sourceVersion) {
        return findKey(new EtfMarketOverviewDailyCacheKey(date, sourceVersion));
    }

    /** Reads all stored generations for a date, without choosing a latest generation or publishing misses. */
    public DatasetReadPage<EtfMarketOverviewDailyCache> findForDate(LocalDate date, int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(date, "trade date required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("trade_date", date),
                null, null, null, pageSize, cursor));
    }

    public DatasetReadPage<EtfMarketOverviewDailyCache> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                              int pageSize, DatasetReadCursor cursor) {
        return findRange(fromInclusive, toExclusive, null, pageSize, cursor);
    }

    /** A null business version selects every generation in this explicit half-open interval. */
    public DatasetReadPage<EtfMarketOverviewDailyCache> findRange(LocalDate fromInclusive, LocalDate toExclusive,
            String sourceVersion, int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        if (sourceVersion != null) EtfMarketOverviewDailyCacheKey.requireVersion(sourceVersion);
        Map<String, Object> equalities = sourceVersion == null ? Map.of() : Map.of("source_version", sourceVersion);
        return findPage(new DatasetReadQuery(definition().storageColumns(), equalities, "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }
}
