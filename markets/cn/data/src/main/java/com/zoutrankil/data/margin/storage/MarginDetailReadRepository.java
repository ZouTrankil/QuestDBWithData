package com.zoutrankil.data.margin.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.margin.mapper.MarginDetailMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit-column, bounded typed read surface for an isolated margin_detail target. */
@Repository
public class MarginDetailReadRepository implements DatasetImplementation {
    private final JdbcTemplate jdbc;private final String table;private final QuestDbBoundedReader bounded;private final MarginDetailMapper mapper=new MarginDetailMapper();
    @Autowired public MarginDetailReadRepository(JdbcTemplate source,
            @Value("${app.sync.margin-detail-table:java_d029_margin_detail_acceptance}")String table,QuestDbBoundedReader bounded){
        DatasetDefinition.identifier(table);this.table=table;this.jdbc=new JdbcTemplate(Objects.requireNonNull(source).getDataSource());this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(501);this.bounded=Objects.requireNonNull(bounded);
    }
    @Override public DatasetDefinition definition(){return MarginDetailDataset.definition(table);}
    public DatasetReadPage<MarginDetail> findPage(DatasetReadQuery query){return bounded.read(definition(),query,null,mapper::fromValues);}
    public List<MarginDetail> findDate(LocalDate date,int limit){Objects.requireNonNull(date);if(limit<1||limit>500)throw new IllegalArgumentException("D029 read limit must be 1..500");
        var rows=jdbc.query(select()+" WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT "+(limit+1),this::physical,micros(date));return boundedResult(rows,limit);}
    public List<MarginDetail> findRange(LocalDate from,LocalDate toExclusive,int limit){if(from==null||toExclusive==null||!from.isBefore(toExclusive)||limit<1||limit>500)throw new IllegalArgumentException("D029 ordered bounded range and limit required");
        var rows=jdbc.query(select()+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "+(limit+1),this::physical,micros(from),micros(toExclusive));return boundedResult(rows,limit);}
    public List<MarginDetail> findKeys(Collection<MarginDetailKey> keys){if(keys==null||keys.isEmpty()||keys.size()>250||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("D029 requires 1..250 unique full keys");
        var clauses=new ArrayList<String>();var args=new ArrayList<Object>();for(var key:keys){clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");args.add(key.tsCode());args.add(micros(key.tradeDate()));}
        var rows=jdbc.query(select()+" WHERE "+String.join(" OR ",clauses)+" ORDER BY trade_date,ts_code LIMIT "+(keys.size()+1),this::physical,args.toArray());if(rows.size()>keys.size())throw new IllegalStateException("D029 full-key read returned duplicate/extra physical rows");return List.copyOf(rows);}
    private String select(){return "SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,name,rzye,rzmre,rzche,rqye,rqyl,rqchl,rqmcl,rzrqye FROM \""+table+"\"";}
    private MarginDetail physical(ResultSet rs,int index)throws SQLException{Object raw=rs.getObject("trade_date_micros");if(!(raw instanceof Number micros))throw new SQLException("D029 trade_date timestamp required");
        var values=new LinkedHashMap<String,Object>();values.put("ts_code",rs.getString("ts_code"));values.put("trade_date",TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(),TemporalValues.EpochUnit.MICROS).date());values.put("name",rs.getString("name"));
        for(String field:List.of("rzye","rzmre","rzche","rqye","rqyl","rqchl","rqmcl","rzrqye")){Object value=rs.getObject(field);values.put(field,value==null?null:((Number)value).doubleValue());}
        try{return mapper.fromValues(new DatasetValues(values));}catch(RuntimeException invalid){throw new SQLException("Invalid physical margin_detail row",invalid);}}
    private static List<MarginDetail> boundedResult(List<MarginDetail> rows,int limit){if(rows.size()>limit)throw new IllegalStateException("D029 typed read exceeded requested row limit");return List.copyOf(rows);}
    private static long micros(LocalDate date){return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);}
}
