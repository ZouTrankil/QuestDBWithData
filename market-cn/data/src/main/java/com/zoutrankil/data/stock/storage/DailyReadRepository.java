package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.mapper.DailyMapper;
import java.time.LocalDate;
import org.springframework.stereotype.Repository;

/** D007 bounded typed reads with explicit projection, full key and stable keyset pagination. */
@Repository
public class DailyReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final DailyMapper mapper = new DailyMapper();

    public DailyReadRepository(QuestDbBoundedReader reader) { this.reader = reader; }

    @Override public DatasetDefinition definition() { return DailyDataset.DEFINITION; }

    public DatasetReadPage<DailyMarketBar> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<DailyMarketBar> findForDate(LocalDate date, int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(definition().columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList(), java.util.Map.of("trade_date", date),
                null, null, null, pageSize, cursor));
    }

    /** End bound is exclusive and must be the day after the requested inclusive last date. */
    public DatasetReadPage<DailyMarketBar> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                       int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(definition().columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList(), java.util.Map.of(),
                "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }
}
