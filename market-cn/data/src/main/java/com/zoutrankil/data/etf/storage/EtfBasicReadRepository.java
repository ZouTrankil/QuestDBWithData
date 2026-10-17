package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.repository.QuestDbBoundedReader;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.etf.mapper.EtfBasicMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/** Explicit full-row typed reads against an isolated D013 table. */
@Repository
public class EtfBasicReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final EtfBasicMapper mapper = new EtfBasicMapper();
    private final String table;

    public EtfBasicReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.etf-basic-table:java_d013_etf_basic_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader);
        EtfBasicDataset.requireIsolatedTable(table);
        this.table = table;
    }

    @Override public DatasetDefinition definition() { return EtfBasicDataset.definition(table); }
    public String tableName() { return table; }

    public DatasetReadPage<EtfBasic> findByKey(EtfBasicKey key) {
        Objects.requireNonNull(key);
        String reason = EtfBasicDataset.DEFINITION.columns().stream()
                .filter(column -> column.logicalName().equals("timestamp"))
                .findFirst().orElseThrow().temporal().meaning();
        var query = new DatasetReadQuery(columns(), Map.of("ts_code", key.tsCode(), "timestamp",
                new TemporalValues.TechnicalTimestamp(key.timestamp(), reason)), null,
                null, null, 2, null);
        var page = find(query);
        if (page.hasMore() || page.rows().size() > 1)
            throw new IllegalStateException("etf_basic complete-key read is ambiguous");
        return page;
    }

    /** Bounded half-open range over Java observation time; this is not a provider revision time. */
    public DatasetReadPage<EtfBasic> findObservedBetween(Instant fromInclusive, Instant toExclusive,
                                                          int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing update_time range required");
        return find(new DatasetReadQuery(columns(), Map.of(), "update_time", fromInclusive, toExclusive,
                pageSize, cursor));
    }

    public DatasetReadPage<EtfBasic> find(DatasetReadQuery query) {
        if (query.columns().size() != columns().size()
                || !new HashSet<>(query.columns()).equals(new HashSet<>(columns())))
            throw new IllegalArgumentException("Typed etf_basic reads require all 27 frozen physical fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    private static List<String> columns() {
        return EtfBasicDataset.DEFINITION.columns().stream().map(DatasetDefinition.Column::logicalName).toList();
    }
}
