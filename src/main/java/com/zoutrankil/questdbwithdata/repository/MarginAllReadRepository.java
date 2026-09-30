package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.mapper.MarginAllMapper;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Typed D028 reads use every frozen source/storage field and reject duplicate natural keys. */
@Repository
public class MarginAllReadRepository implements DatasetImplementation {
    private static final List<String> FIELDS=MarginAllDataset.DEFINITION.columns().stream().map(DatasetDefinition.Column::logicalName).toList();
    private final JdbcTemplate jdbc; private final String table; private final QuestDbBoundedReader bounded; private final MarginAllMapper mapper=new MarginAllMapper();
    public MarginAllReadRepository(JdbcTemplate jdbc,@Value("${app.sync.margin-all-table:margin_all}")String table,QuestDbBoundedReader bounded){
        this.jdbc=queryJdbc(jdbc);DatasetDefinition.identifier(table);this.table=table;this.bounded=Objects.requireNonNull(bounded);}
    @Override public DatasetDefinition definition(){return MarginAllDataset.definition(table);}
    public DatasetReadPage<MarginAll> find(DatasetReadQuery query){if(query.columns().size()!=FIELDS.size()||!new HashSet<>(query.columns()).equals(new HashSet<>(FIELDS)))throw new IllegalArgumentException("D028 reads require all nine columns");return bounded.read(definition(),query,null,mapper::fromValues);}
    public DatasetReadPage<MarginAll> findByKey(MarginAllKey key){Objects.requireNonNull(key);return find(new DatasetReadQuery(FIELDS,Map.of("trade_date",key.tradeDate(),"exchange_id",key.exchangeId()),null,null,null,2,null));}
    public DatasetReadPage<MarginAll> findRange(LocalDate from,LocalDate to,int pageSize,DatasetReadCursor cursor){
        Objects.requireNonNull(from);Objects.requireNonNull(to);if(!from.isBefore(to))throw new IllegalArgumentException("D028 range must increase and be half-open");
        return find(new DatasetReadQuery(FIELDS,Map.of(),"trade_date",from,to,pageSize,cursor));}
    private String select(){return "SELECT cast(trade_date AS long) AS trade_micros,exchange_id,rzye,rzmre,rzche,rqye,rqmcl,rzrqye,rqyl FROM \""+table+"\"";}
    private MarginAll physical(java.sql.ResultSet rs,int n)throws java.sql.SQLException{
        Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number micros))throw new java.sql.SQLException("D028 trade_date missing");var v=new LinkedHashMap<String,Object>();
        v.put("trade_date",TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(),TemporalValues.EpochUnit.MICROS).date());v.put("exchange_id",rs.getString("exchange_id"));
        for(String f:List.of("rzye","rzmre","rzche","rqye","rqmcl","rzrqye","rqyl")){Object value=rs.getObject(f);if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue()))throw new java.sql.SQLException("D028 required finite field "+f);v.put(f,number.doubleValue());}
        try{return mapper.fromValues(new DatasetValues(v));}catch(RuntimeException invalid){throw new java.sql.SQLException("Invalid physical D028 row",invalid);}}
    private static JdbcTemplate queryJdbc(JdbcTemplate source){var jdbc=new JdbcTemplate(Objects.requireNonNull(source).getDataSource());jdbc.setQueryTimeout(20);jdbc.setMaxRows(10001);return jdbc;}
}
