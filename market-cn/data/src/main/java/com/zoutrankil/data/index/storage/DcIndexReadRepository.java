package com.zoutrankil.data.index.storage;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.domain.DcIndexState.*;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.index.mapper.DcIndexMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;

/** Bounded explicit-column typed read surface for D023. */
@org.springframework.stereotype.Repository
public class DcIndexReadRepository implements DatasetImplementation {
    private final JdbcTemplate jdbc;
    private final String table;
    private final DcIndexMapper mapper=new DcIndexMapper();
    private final QuestDbBoundedReader boundedReader;
    public DcIndexReadRepository(JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${app.sync.dc-index-table:java_d023_dc_index_acceptance}") String table,
            QuestDbBoundedReader boundedReader) {
        DatasetDefinition.identifier(table);this.table=table;this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());this.boundedReader=Objects.requireNonNull(boundedReader);
        this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(501);
    }
    @Override public DatasetDefinition definition(){return DcIndexDataset.definition(table);}
    public DatasetReadPage<DcIndex> findPage(DatasetReadQuery query){return boundedReader.read(definition(),query,null,mapper::fromValues);}
    public List<DcIndex> findDate(LocalDate date,int limit) {
        Objects.requireNonNull(date);if(limit<1||limit>500)throw new IllegalArgumentException("dc_index page limit must be 1..500");
        return jdbc.query(select()+" WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT "+(limit+1),
                (rs,index)->new DcIndexMapper().fromValues(new DatasetValues(readValues(rs))),
                new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS));
    }
    public List<DcIndex> findRange(LocalDate fromInclusive,LocalDate toExclusive,int limit) {
        if(fromInclusive==null||toExclusive==null||!fromInclusive.isBefore(toExclusive)||limit<1||limit>500)
            throw new IllegalArgumentException("Ordered bounded dc_index date range and 1..500 limit required");
        long from=new TemporalValues.CalendarTimestamp(fromInclusive).storageEpoch(TemporalValues.EpochUnit.MICROS);
        long to=new TemporalValues.CalendarTimestamp(toExclusive).storageEpoch(TemporalValues.EpochUnit.MICROS);
        return jdbc.query(select()+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "+(limit+1),
                (rs,index)->new DcIndexMapper().fromValues(new DatasetValues(readValues(rs))),from,to);
    }
    public List<DcIndex> findKeys(Collection<DcIndexKey> keys) {
        if(keys==null||keys.isEmpty()||keys.size()>250||new HashSet<>(keys).size()!=keys.size())
            throw new IllegalArgumentException("1..250 unique dc_index keys required");
        var clauses=new ArrayList<String>();var args=new ArrayList<Object>();
        for(var key:keys){clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");args.add(key.tsCode());args.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));}
        return jdbc.query(select()+" WHERE "+String.join(" OR ",clauses)+" ORDER BY trade_date,ts_code LIMIT "+(keys.size()+1),
                (rs,index)->new DcIndexMapper().fromValues(new DatasetValues(readValues(rs))),args.toArray());
    }
    private String select(){return "SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,name,leading,leading_code,pct_change,leading_pct,total_mv,turnover_rate,up_num,down_num FROM \""+table+"\"";}
    private static Map<String,Object> readValues(java.sql.ResultSet rs)throws java.sql.SQLException {
        var v=new LinkedHashMap<String,Object>();Object raw=rs.getObject("trade_date_micros");
        if(!(raw instanceof Number n))throw new java.sql.SQLException("dc_index trade_date timestamp required");
        try{v.put("trade_date",TemporalValues.CalendarTimestamp.fromStorageEpoch(n.longValue(),TemporalValues.EpochUnit.MICROS).date());}
        catch(RuntimeException e){throw new java.sql.SQLException("Invalid dc_index calendar timestamp",e);}
        v.put("ts_code",rs.getString("ts_code"));v.put("name",rs.getString("name"));v.put("leading",rs.getString("leading"));v.put("leading_code",rs.getString("leading_code"));
        v.put("pct_change",nullableDouble(rs,"pct_change"));v.put("leading_pct",nullableDouble(rs,"leading_pct"));v.put("total_mv",nullableDouble(rs,"total_mv"));v.put("turnover_rate",nullableDouble(rs,"turnover_rate"));
        v.put("up_num",nullableInteger(rs,"up_num"));v.put("down_num",nullableInteger(rs,"down_num"));return v;
    }
    private static Double nullableDouble(java.sql.ResultSet rs,String field)throws java.sql.SQLException {double v=rs.getDouble(field);return rs.wasNull()?null:v;}
    private static Integer nullableInteger(java.sql.ResultSet rs,String field)throws java.sql.SQLException {int v=rs.getInt(field);return rs.wasNull()?null:v;}
}
