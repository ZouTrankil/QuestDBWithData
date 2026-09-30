package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.IndexMonthlyMapper;
import com.zoutrankil.data.service.IndexMonthlyJobService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.*;

/** Bounded complete-projection reads for the D022 isolated target. */
@Repository
public class IndexMonthlyReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS=IndexMonthlyDataset.columns().stream().map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader; private final String table; private final IndexMonthlyMapper mapper=new IndexMonthlyMapper();
    public IndexMonthlyReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.index-monthly-table:java_d022_index_monthly_acceptance}") String table) {
        this.reader=Objects.requireNonNull(reader);IndexMonthlyDataset.requireIsolatedTableName(table);this.table=table;
    }
    @Override public DatasetDefinition definition(){return IndexMonthlyDataset.definition(table);}
    public DatasetReadPage<IndexMonthly> findByKey(IndexMonthlyKey key) {
        Objects.requireNonNull(key);return find(new DatasetReadQuery(COLUMNS,Map.of("ts_code",key.tsCode(),"trade_date",key.tradeDate()),null,null,null,1,null));
    }
    public DatasetReadPage<IndexMonthly> findRange(String code,LocalDate fromInclusive,LocalDate toExclusive,int pageSize,DatasetReadCursor cursor) {
        if(!com.zoutrankil.data.service.IndexMonthlyUniverse.validProviderCode(code)||fromInclusive==null||toExclusive==null
                ||!fromInclusive.isBefore(toExclusive))throw new IllegalArgumentException("Known monthly code and increasing half-open range required");
        return find(new DatasetReadQuery(COLUMNS,Map.of("ts_code",code),"trade_date",fromInclusive,toExclusive,pageSize,cursor));
    }
    public DatasetReadPage<IndexMonthly> find(DatasetReadQuery query) {
        if(query.columns().size()!=COLUMNS.size()||!new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("Typed D022 reads require all fourteen fields");
        return reader.read(definition(),query,null,mapper::fromValues);
    }
}
