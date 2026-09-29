package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.DailyBasicMapper;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.*;

/** Explicit bounded typed reads over the registered daily_basic definition. */
@Repository
public class DailyBasicReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final DailyBasicMapper mapper = new DailyBasicMapper();
    public DailyBasicReadRepository(QuestDbBoundedReader reader) { this.reader = Objects.requireNonNull(reader); }
    @Override public DatasetDefinition definition() { return DailyBasicDataset.DEFINITION; }

    public DatasetReadPage<DailyBasic> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public Optional<DailyBasic> findByKey(DailyBasicKey key) {
        Objects.requireNonNull(key);
        var columns = definition().columns().stream().map(DatasetDefinition.Column::logicalName).toList();
        var query = new DatasetReadQuery(columns, Map.of("ts_code", key.tsCode()), "trade_date",
                key.tradeDate(), key.tradeDate().plusDays(1), 2, null);
        var page = findPage(query);
        var match = page.rows().stream().filter(row -> row.key().equals(key)).toList();
        if (match.size() > 1 || page.hasMore()) throw new IllegalStateException("daily_basic key read is ambiguous");
        return match.stream().findFirst();
    }
}
