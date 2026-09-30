package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.StockFactorMapper;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.*;

/** Bounded full-row read plus explicit complete-key and inclusive/exclusive date-range helpers. */
@Repository
public class StockFactorReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS=StockFactorDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final StockFactorMapper mapper=new StockFactorMapper();
    public StockFactorReadRepository(QuestDbBoundedReader reader) { this.reader=Objects.requireNonNull(reader); }
    @Override public DatasetDefinition definition() { return StockFactorDataset.DEFINITION; }
    public DatasetReadPage<StockFactor> findByKey(StockFactorKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS,Map.of("ts_code",key.tsCode(),"trade_date",key.tradeDate()),
                null,null,null,1,null));
    }
    public DatasetReadPage<StockFactor> findRange(String tsCode,LocalDate fromInclusive,LocalDate toExclusive,
                                                   int pageSize,DatasetReadCursor cursor) {
        if(tsCode!=null && !new StockFactorKey(tsCode,Objects.requireNonNull(fromInclusive)).tsCode().equals(tsCode))
            throw new IllegalArgumentException("Exact Tushare stock code required");
        Objects.requireNonNull(fromInclusive);Objects.requireNonNull(toExclusive);
        if(!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing half-open trade-date range required");
        Map<String,Object> equalities=tsCode==null?Map.of():Map.of("ts_code",tsCode);
        return find(new DatasetReadQuery(COLUMNS,equalities,"trade_date",fromInclusive,toExclusive,pageSize,cursor));
    }
    public DatasetReadPage<StockFactor> find(DatasetReadQuery query) {
        if(query.columns().size()!=COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("Typed StockFactor requires all 35 frozen physical fields");
        return reader.read(definition(),query,null,mapper::fromValues);
    }
}
