package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.EtfMarketOverviewDailyViewMapper;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Bounded typed direct-view reads; the shared reader pins both bases and the exact view definition. */
@Repository
public class EtfMarketOverviewDailyViewReadRepository implements DatasetImplementation {
    public static final int MAX_WINDOW_DAYS = 31;
    public static final int MAX_PAGE_SIZE = 31;
    private final QuestDbBoundedReader reader;
    private final EtfMarketOverviewDailyViewMapper mapper = new EtfMarketOverviewDailyViewMapper();

    public EtfMarketOverviewDailyViewReadRepository(QuestDbBoundedReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    @Override public DatasetDefinition definition() { return EtfMarketOverviewDailyViewDataset.DEFINITION; }

    public DatasetReadPage<EtfMarketOverviewDailyView> findPage(DatasetReadQuery query) {
        Objects.requireNonNull(query, "bounded ETF overview query required");
        if (query.pageSize() > MAX_PAGE_SIZE) throw new IllegalArgumentException("ETF view page must be at most 31 rows");
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<EtfMarketOverviewDailyView> findKey(EtfMarketOverviewDailyViewKey key) {
        Objects.requireNonNull(key, "ETF overview date key required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("trade_date", key.tradeDate()),
                null, null, null, 1, null));
    }

    public DatasetReadPage<EtfMarketOverviewDailyView> findForDate(LocalDate date) {
        return findKey(new EtfMarketOverviewDailyViewKey(date));
    }

    public DatasetReadPage<EtfMarketOverviewDailyView> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                             int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive, "range start required");
        Objects.requireNonNull(toExclusive, "exclusive range end required");
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing date range required");
        if (ChronoUnit.DAYS.between(fromInclusive, toExclusive) > MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("ETF view range must be at most 31 calendar days");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(), "trade_date",
                fromInclusive, toExclusive, pageSize, cursor));
    }
}
