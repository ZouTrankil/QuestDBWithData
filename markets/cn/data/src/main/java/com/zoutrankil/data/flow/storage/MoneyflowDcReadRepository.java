package com.zoutrankil.data.flow.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.flow.mapper.MoneyflowDcMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;

/** Typed, explicit-column D026 reads with caller-visible page/key limits. */
@org.springframework.stereotype.Repository
public class MoneyflowDcReadRepository implements DatasetImplementation {
    private final JdbcTemplate jdbc; private final String table; private final QuestDbBoundedReader bounded; private final MoneyflowDcMapper mapper=new MoneyflowDcMapper();
    @org.springframework.beans.factory.annotation.Autowired public MoneyflowDcReadRepository(JdbcTemplate source,
            @org.springframework.beans.factory.annotation.Value("${app.sync.moneyflow-dc-table:java_d026_moneyflow_dc_acceptance}")String table,QuestDbBoundedReader bounded){DatasetDefinition.identifier(table);this.table=table;this.jdbc=new JdbcTemplate(Objects.requireNonNull(source).getDataSource());this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(501);this.bounded=Objects.requireNonNull(bounded);}
    @Override public DatasetDefinition definition(){return MoneyflowDcDataset.definition(table);}
    public DatasetReadPage<MoneyflowDc> findPage(DatasetReadQuery query){return bounded.read(definition(),query,null,mapper::fromValues);}
    public List<MoneyflowDc> findDate(LocalDate date,int limit){Objects.requireNonNull(date);if(limit<1||limit>500)throw new IllegalArgumentException("moneyflow_dc page limit must be 1..500");
        var rows=jdbc.query(select()+" WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT "+(limit+1),this::physical,micros(date));return boundedResult(rows,limit);}
    public List<MoneyflowDc> findRange(LocalDate from,LocalDate toExclusive,int limit){if(from==null||toExclusive==null||!from.isBefore(toExclusive)||limit<1||limit>500)throw new IllegalArgumentException("Ordered bounded moneyflow_dc range and 1..500 limit required");
        var rows=jdbc.query(select()+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "+(limit+1),this::physical,micros(from),micros(toExclusive));return boundedResult(rows,limit);}
    public List<MoneyflowDc> findKeys(Collection<MoneyflowDcKey> keys){if(keys==null||keys.isEmpty()||keys.size()>250||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("1..250 unique moneyflow_dc keys required");var clauses=new ArrayList<String>();var args=new ArrayList<Object>();
        for(var key:keys){clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");args.add(key.tsCode());args.add(micros(key.tradeDate()));}var rows=jdbc.query(select()+" WHERE "+String.join(" OR ",clauses)+" ORDER BY trade_date,ts_code LIMIT "+(keys.size()+1),this::physical,args.toArray());if(rows.size()>keys.size())throw new IllegalStateException("moneyflow_dc complete-key read returned duplicates/extras");return List.copyOf(rows);}
    private static List<MoneyflowDc> boundedResult(List<MoneyflowDc> rows,int limit){if(rows.size()>limit)throw new IllegalStateException("moneyflow_dc read exceeded requested page bound");return List.copyOf(rows);}
    private String select(){return "SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,name,pct_change,close,net_amount,net_amount_rate,buy_elg_amount,buy_elg_amount_rate,buy_lg_amount,buy_lg_amount_rate,buy_md_amount,buy_md_amount_rate,buy_sm_amount,buy_sm_amount_rate FROM \""+table+"\"";}
    private MoneyflowDc physical(java.sql.ResultSet rs,int index)throws java.sql.SQLException{Object raw=rs.getObject("trade_date_micros");if(!(raw instanceof Number n))throw new java.sql.SQLException("moneyflow_dc timestamp required");try{var v=new LinkedHashMap<String,Object>();v.put("ts_code",rs.getString("ts_code"));v.put("trade_date",TemporalValues.CalendarTimestamp.fromStorageEpoch(n.longValue(),TemporalValues.EpochUnit.MICROS).date());v.put("name",rs.getString("name"));
        for(String f:MoneyflowDcDataset.columns().stream().map(DatasetDefinition.Column::storageName).filter(f->!Set.of("ts_code","trade_date","name").contains(f)).toList()){Object x=rs.getObject(f);v.put(f,x==null?null:((Number)x).doubleValue());}return mapper.fromValues(new DatasetValues(v));}catch(RuntimeException e){throw new java.sql.SQLException("Invalid physical moneyflow_dc row",e);}}
    private static long micros(LocalDate date){return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);}
}
