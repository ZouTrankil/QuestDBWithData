package com.zoutrankil.data.margin.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.margin.mapper.MarginZrzMapper;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Typed D031 reads map every physical field and preserve nullable source metrics. */
@Repository
public class MarginZrzReadRepository implements DatasetImplementation {
    private static final List<String> FIELDS=MarginZrzDataset.DEFINITION.columns().stream().map(DatasetDefinition.Column::logicalName).toList();
    private final JdbcTemplate jdbc;private final String table;private final QuestDbBoundedReader bounded;private final MarginZrzMapper mapper=new MarginZrzMapper();
    public MarginZrzReadRepository(JdbcTemplate jdbc,@Value("${app.sync.margin-zrz-table:margin_zrz}")String table,QuestDbBoundedReader bounded){
        this.jdbc=queryJdbc(jdbc);DatasetDefinition.identifier(table);this.table=table;this.bounded=Objects.requireNonNull(bounded);}
    @Override public DatasetDefinition definition(){return MarginZrzDataset.definition(table);}
    public DatasetReadPage<MarginZrz> find(DatasetReadQuery query){if(query.columns().size()!=FIELDS.size()||!new HashSet<>(query.columns()).equals(new HashSet<>(FIELDS)))throw new IllegalArgumentException("D031 reads require all six columns");return bounded.read(definition(),query,null,mapper::fromValues);}
    public DatasetReadPage<MarginZrz> findByKey(MarginZrzKey key){Objects.requireNonNull(key);return find(new DatasetReadQuery(FIELDS,Map.of("trade_date",key.tradeDate()),null,null,null,2,null));}
    public DatasetReadPage<MarginZrz> findRange(LocalDate from,LocalDate to,int pageSize,DatasetReadCursor cursor){
        Objects.requireNonNull(from);Objects.requireNonNull(to);if(!from.isBefore(to))throw new IllegalArgumentException("D031 range must increase and be half-open");
        return find(new DatasetReadQuery(FIELDS,Map.of(),"trade_date",from,to,pageSize,cursor));}
    private MarginZrz physical(java.sql.ResultSet rs,int n)throws java.sql.SQLException{
        Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number micros))throw new java.sql.SQLException("D031 trade_date missing");var v=new LinkedHashMap<String,Object>();
        v.put("trade_date",TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(),TemporalValues.EpochUnit.MICROS).date());
        for(String f:List.of("ob","auc_amount","repo_amount","repay_amount","cb")){Object value=rs.getObject(f);if(value==null)v.put(f,null);else if(value instanceof Number number&&Double.isFinite(number.doubleValue()))v.put(f,number.doubleValue());else throw new java.sql.SQLException("D031 invalid numeric field "+f);}
        try{return mapper.fromValues(new DatasetValues(v));}catch(RuntimeException invalid){throw new java.sql.SQLException("Invalid physical D031 row",invalid);}}
    private static JdbcTemplate queryJdbc(JdbcTemplate source){var jdbc=new JdbcTemplate(Objects.requireNonNull(source).getDataSource());jdbc.setQueryTimeout(20);jdbc.setMaxRows(10001);return jdbc;}
}
