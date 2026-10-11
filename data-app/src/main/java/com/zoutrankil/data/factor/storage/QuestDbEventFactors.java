package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.PointInTimeFactorJoin;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Announcement event inputs use the Python equity product's 460-calendar-day window. */
public final class QuestDbEventFactors {
    private record Binding(String table,String field,String dateColumn,String order,String extra) {}
    private static final Map<String,Binding> BINDINGS=Map.of(
            "ownership_holder_change",new Binding("stk_holdertrade","change_ratio","ann_date","ann_date,ts_code","in_de"),
            "event_repurchase_amount",new Binding("repurchase","amount","ann_date","ann_date,ts_code,end_date",""),
            "event_unlock_ratio",new Binding("share_float","float_ratio","float_date","float_date,ts_code,ann_date",""),
            "event_cash_dividend",new Binding("dividend","cash_div_tax","ann_date","ann_date,ts_code,end_date",""),
            "event_earnings_forecast",new Binding("forecast","p_change_min","ann_date","ann_date,ts_code,end_date","p_change_max"));
    public record Snapshot(Map<String,Map<String,List<PointInTimeFactorJoin.Observation>>> factors,
            List<Map<String,Object>> sourceState,Map<String,Long> sourceRows,LocalDate readFrom) {
        public double[] align(String factor,String symbol,LocalDate[] dates) {
            return PointInTimeFactorJoin.join(dates,factors.get(factor).getOrDefault(symbol,List.of()),false);
        }
    }
    private final JdbcTemplate jdbc;
    public QuestDbEventFactors(JdbcTemplate template) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(90);
    }
    public Snapshot read(LocalDate from,LocalDate through,List<String> factorIds,int maxRows) {
        if(through.isBefore(from) || maxRows<1 || maxRows>1_000_000)throw new IllegalArgumentException("Bounded event request required");
        LocalDate start=from.minusDays(460);
        var factors=new LinkedHashMap<String,Map<String,List<PointInTimeFactorJoin.Observation>>>();
        var states=new ArrayList<Map<String,Object>>();var counts=new LinkedHashMap<String,Long>();
        var marginIds=factorIds.stream().filter(id->Set.of("ownership_margin_balance","ownership_short_balance").contains(id)).toList();
        if(!marginIds.isEmpty()) {
            var margin=new QuestDbMarginFactors(jdbc).read(from,through,marginIds,maxRows);
            factors.putAll(margin.factors());states.addAll(margin.sourceState());counts.putAll(margin.sourceRows());
        }
        for(String factor:factorIds) {
            if(marginIds.contains(factor))continue;
            var binding=BINDINGS.get(factor);
            if(binding==null)throw new IllegalArgumentException("Event source not implemented: "+factor);
            if(factors.containsKey(factor))throw new IllegalArgumentException("Duplicate event selection");
            var before=state(binding.table());states.addAll(before);
            String available=binding.dateColumn().equals("float_date")
                    ?" AND ann_date<='"+through.format(DateTimeFormatter.BASIC_ISO_DATE)+"'":"";
            String sql="SELECT ts_code,ann_date,"+binding.field()+(binding.extra().isEmpty()?"":","+binding.extra())
                    +" FROM "+binding.table()+" WHERE "+binding.dateColumn()+">='"+start+"' AND "+binding.dateColumn()
                    +"<'"+through.plusDays(1)+"'"+available+" ORDER BY "+binding.order()+" LIMIT "+(maxRows+1);
            var grouped=new LinkedHashMap<String,TreeMap<LocalDate,PointInTimeFactorJoin.Observation>>();long[] count={0};
            jdbc.query(sql,(RowCallbackHandler) rs -> {
                if(++count[0]>maxRows)throw new IllegalStateException("Event source exceeds row budget: "+binding.table());
                String symbol=rs.getString(1);if(symbol==null || symbol.isBlank())throw new IllegalStateException("Missing event entity");
                LocalDate announced;
                if(binding.dateColumn().equals("float_date")) announced=parseDate(rs.getString(2));
                else { var timestamp=rs.getTimestamp(2);announced=timestamp==null?null:timestamp.toLocalDateTime().toLocalDate(); }
                if(announced==null || announced.isAfter(through))return;
                double value=rs.getDouble(3);if(rs.wasNull())value=Double.NaN;
                if(factor.equals("ownership_holder_change")) {
                    String direction=rs.getString(4);
                    value="IN".equalsIgnoreCase(direction)?value:"DE".equalsIgnoreCase(direction)?-value:Double.NaN;
                } else if(factor.equals("event_earnings_forecast")) {
                    double upper=rs.getDouble(4);if(rs.wasNull())upper=Double.NaN;value=(value+upper)/2;
                }
                if(!Double.isFinite(value))value=Double.NaN;
                // Source order is the Python dataset order; same announcement retains its final record, including nulls.
                grouped.computeIfAbsent(symbol,ignored->new TreeMap<>()).put(announced,
                        new PointInTimeFactorJoin.Observation(announced,announced,value));
            });
            if(!before.equals(state(binding.table())))throw new IllegalStateException("Event source changed during read");
            var frozen=new LinkedHashMap<String,List<PointInTimeFactorJoin.Observation>>();
            grouped.forEach((symbol,rows)->frozen.put(symbol,List.copyOf(rows.values())));
            factors.put(factor,Collections.unmodifiableMap(frozen));counts.put(binding.table(),count[0]);
        }
        return new Snapshot(Collections.unmodifiableMap(factors),List.copyOf(states),Map.copyOf(counts),start);
    }
    private List<Map<String,Object>> state(String table) {
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,walEnabled,table_suspended FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1)throw new IllegalStateException("Missing event table: "+table);
        var row=rows.getFirst();
        if(Boolean.TRUE.equals(row.get("table_suspended")) || Boolean.TRUE.equals(row.get("walEnabled")) && !QuestDbWriteChecks.walSettled(jdbc,table))
            throw new IllegalStateException("Event WAL not settled: "+table);
        return rows;
    }
    private static LocalDate parseDate(String value) {
        if(value==null || value.isBlank())return null;
        try{return value.length()==8?LocalDate.parse(value,DateTimeFormatter.BASIC_ISO_DATE):LocalDate.parse(value.substring(0,10));}
        catch(RuntimeException invalid){return null;}
    }
}
