package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.mapper.MoneyflowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;

/** Explicit-column typed reads for D024; every operation has a caller-visible row bound. */
@org.springframework.stereotype.Repository
public class MoneyflowReadRepository implements DatasetImplementation {
    private final JdbcTemplate jdbc;private final String table;private final QuestDbBoundedReader bounded;private final MoneyflowMapper mapper=new MoneyflowMapper();
    @org.springframework.beans.factory.annotation.Autowired public MoneyflowReadRepository(JdbcTemplate source,
            @org.springframework.beans.factory.annotation.Value("${app.sync.moneyflow-table:java_d024_moneyflow_acceptance}")String table,QuestDbBoundedReader bounded){DatasetDefinition.identifier(table);this.table=table;this.jdbc=new JdbcTemplate(Objects.requireNonNull(source).getDataSource());this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(501);this.bounded=Objects.requireNonNull(bounded);}
    @Override public DatasetDefinition definition(){return MoneyflowDataset.definition(table);}
    public DatasetReadPage<Moneyflow> findPage(DatasetReadQuery query){return bounded.read(definition(),query,null,mapper::fromValues);}
    public List<Moneyflow> findDate(LocalDate date,int limit){Objects.requireNonNull(date);if(limit<1||limit>500)throw new IllegalArgumentException("moneyflow page limit must be 1..500");
        var rows=jdbc.query(select()+" WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT "+(limit+1),this::physical,new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS));return boundedResult(rows,limit);}
    public List<Moneyflow> findRange(LocalDate from,LocalDate toExclusive,int limit){if(from==null||toExclusive==null||!from.isBefore(toExclusive)||limit<1||limit>500)throw new IllegalArgumentException("Ordered bounded moneyflow range and 1..500 limit required");
        long a=micros(from),b=micros(toExclusive);var rows=jdbc.query(select()+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "+(limit+1),this::physical,a,b);return boundedResult(rows,limit);}
    public List<Moneyflow> findKeys(Collection<MoneyflowKey> keys){if(keys==null||keys.isEmpty()||keys.size()>250||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("1..250 unique moneyflow keys required");var clauses=new ArrayList<String>();var args=new ArrayList<Object>();
        for(var key:keys){clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");args.add(key.tsCode());args.add(micros(key.tradeDate()));}var rows=jdbc.query(select()+" WHERE "+String.join(" OR ",clauses)+" ORDER BY trade_date,ts_code LIMIT "+(keys.size()+1),this::physical,args.toArray());if(rows.size()>keys.size())throw new IllegalStateException("moneyflow complete-key read returned duplicates/extras");return List.copyOf(rows);}
    private static List<Moneyflow> boundedResult(List<Moneyflow> rows,int limit){if(rows.size()>limit)throw new IllegalStateException("moneyflow read exceeded requested page bound; use the next explicit page");return List.copyOf(rows);}
    private String select(){return "SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,buy_sm_vol,buy_sm_amount,sell_sm_vol,sell_sm_amount,buy_md_vol,buy_md_amount,sell_md_vol,sell_md_amount,buy_lg_vol,buy_lg_amount,sell_lg_vol,sell_lg_amount,buy_elg_vol,buy_elg_amount,sell_elg_vol,sell_elg_amount,net_mf_vol,net_mf_amount FROM \""+table+"\"";}
    private Moneyflow physical(java.sql.ResultSet rs,int index)throws java.sql.SQLException{Object raw=rs.getObject("trade_date_micros");if(!(raw instanceof Number n))throw new java.sql.SQLException("moneyflow timestamp required");try{var v=new LinkedHashMap<String,Object>();v.put("ts_code",rs.getString("ts_code"));v.put("trade_date",TemporalValues.CalendarTimestamp.fromStorageEpoch(n.longValue(),TemporalValues.EpochUnit.MICROS).date());
        for(String f:List.of("buy_sm_vol","sell_sm_vol","buy_md_vol","sell_md_vol","buy_lg_vol","sell_lg_vol","buy_elg_vol","sell_elg_vol","net_mf_vol")){Object x=rs.getObject(f);v.put(f,x==null?null:((Number)x).longValue());}
        for(String f:List.of("buy_sm_amount","sell_sm_amount","buy_md_amount","sell_md_amount","buy_lg_amount","sell_lg_amount","buy_elg_amount","sell_elg_amount","net_mf_amount")){Object x=rs.getObject(f);v.put(f,x==null?null:((Number)x).doubleValue());}return mapper.fromValues(new DatasetValues(v));}catch(RuntimeException e){throw new java.sql.SQLException("Invalid physical moneyflow row",e);}}
    private static long micros(LocalDate date){return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);}
}
