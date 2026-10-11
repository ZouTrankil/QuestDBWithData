package com.zoutrankil.data.factor.storage;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded single-security input snapshot for the native daily-feature producer. */
@Repository
public class QuestDbFactorInputs {
    private static final int MAX_ROWS=5000;
    private static final List<String> BASIC=List.of("turnover_rate","total_mv","circ_mv","pb","pe_ttm","ps_ttm","dv_ttm");
    private static final List<String> MARKET=List.of("close","open","high","low","amount","vol","up_limit","down_limit","is_st","is_suspended");
    private final JdbcTemplate jdbc;

    public QuestDbFactorInputs(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Map<String,Object> read(String symbol,LocalDate from,LocalDate through) {
        if(symbol==null || !symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)") || from==null || through==null
                || from.isAfter(through) || ChronoUnit.DAYS.between(from,through)>366)
            throw new IllegalArgumentException("One security and at most 367 requested calendar days required");
        LocalDate warmup=from.minusDays(460);
        var before=state();
        var market=readRows("v_backtest_daily",MARKET,symbol,warmup,through);
        var basic=readRows("daily_basic",BASIC,symbol,warmup,through);
        if(market.isEmpty()) throw new IllegalStateException("No backtest input rows");
        var basicByDate=new LinkedHashMap<LocalDate,Map<String,Object>>();
        for(var row:basic) if(basicByDate.putIfAbsent(date(row),row)!=null)
            throw new IllegalStateException("Duplicate daily_basic date for " + symbol);
        boolean volumePresent=market.stream().anyMatch(row->row.get("vol")!=null);
        var columns=new LinkedHashMap<String,List<Double>>();
        for(String name:MARKET) columns.put(name,new ArrayList<>());
        for(String name:BASIC) columns.put(name,new ArrayList<>());
        columns.put("effective_volume",new ArrayList<>());
        var dates=new ArrayList<LocalDate>();LocalDate previous=null;
        int missingBasic=0;
        for(var row:market) {
            LocalDate date=date(row);
            if(previous!=null && !date.isAfter(previous)) throw new IllegalStateException("Duplicate or unordered backtest date");
            previous=date;dates.add(date);
            for(String name:MARKET) columns.get(name).add(number(row.get(name)));
            var dailyBasic=basicByDate.get(date);
            if(dailyBasic==null) missingBasic++;
            for(String name:BASIC) columns.get(name).add(dailyBasic==null?null:number(dailyBasic.get(name)));
            columns.get("effective_volume").add(number(row.get(volumePresent?"vol":"amount")));
        }
        var after=state();
        if(!before.equals(after)) throw new IllegalStateException("Factor sources changed during read; retry snapshot");
        var result=new LinkedHashMap<String,Object>();
        result.put("symbol",symbol);result.put("dates",dates);result.put("columns",columns);
        result.put("sourceState",before);result.put("requestedFrom",from);result.put("requestedThrough",through);
        result.put("warmupFrom",warmup);result.put("missingDailyBasicRows",missingBasic);
        result.put("sourceCompletenessCertified",false);
        return result;
    }

    private List<Map<String,Object>> readRows(String table,List<String> fields,String symbol,LocalDate from,LocalDate through) {
        var rows=new ArrayList<Map<String,Object>>();
        // Date literals come only from LocalDate. They permit QuestDB partition
        // pruning before execution; each query covers at most one calendar month.
        for(LocalDate start=from;!start.isAfter(through);) {
            LocalDate end=start.withDayOfMonth(1).plusMonths(1);
            if(end.isAfter(through.plusDays(1)))end=through.plusDays(1);
            rows.addAll(jdbc.queryForList("SELECT trade_date,"+String.join(",",fields)+" FROM "+table
                    +" WHERE ts_code=? AND trade_date>='"+start+"' AND trade_date<'"+end
                    +"' ORDER BY trade_date LIMIT "+(MAX_ROWS+1-rows.size()),symbol));
            if(rows.size()>MAX_ROWS) throw new IllegalStateException("Factor input exceeds bounded row limit");
            start=end;
        }
        return rows;
    }

    private List<Map<String,Object>> state() {
        var state=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended,wal_pending_row_count "
                +"FROM tables() WHERE table_name IN ('v_backtest_daily','daily_basic') ORDER BY table_name");
        if(state.size()!=2) throw new IllegalStateException("Factor input table missing");
        for(var row:state) {
            if(!(row.get("table_txn") instanceof Number tableTxn) || !(row.get("wal_txn") instanceof Number walTxn)
                    || !(row.get("wal_pending_row_count") instanceof Number pending)
                    || !Boolean.FALSE.equals(row.get("table_suspended"))
                    || tableTxn.longValue()!=walTxn.longValue() || pending.longValue()!=0)
                throw new IllegalStateException("Factor source WAL is not settled: " + row.get("table_name"));
        }
        return BacktestFactorSource.stamp(jdbc,state);
    }

    private static LocalDate date(Map<String,Object> row) {
        Object value=row.get("trade_date");
        if(value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
        if(value==null) throw new IllegalStateException("Null trade date");
        return LocalDate.parse(value.toString().substring(0,10));
    }
    private static Double number(Object value) {
        if(value==null) return null;
        if(!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new IllegalStateException("Nonfinite or nonnumeric factor source value");
        return number.doubleValue();
    }
}
