package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.PointInTimeFactorJoin;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Next-session PIT margin values, using a baseline plus changes instead of a full historical panel. */
public final class QuestDbMarginFactors {
    private final JdbcTemplate jdbc;
    public QuestDbMarginFactors(JdbcTemplate template) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(90);
    }
    public QuestDbEventFactors.Snapshot read(LocalDate from,LocalDate through,List<String> factors,int maxRows) {
        if(through.isBefore(from) || through.toEpochDay()-from.toEpochDay()>30 || maxRows<1 || maxRows>1_000_000)
            throw new IllegalArgumentException("Bounded margin factor window required");
        if(factors.isEmpty() || new HashSet<>(factors).size()!=factors.size()
                || !Set.of("ownership_margin_balance","ownership_short_balance").containsAll(factors))
            throw new IllegalArgumentException("Explicit margin factor selection required");
        LocalDate start=from.minusDays(460),calendarEnd=through.plusDays(32);var before=state();
        var sessions=new TreeSet<LocalDate>();var dates=new HashSet<LocalDate>();
        jdbc.query("SELECT cal_date,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>='"+start
                +"' AND cal_date<'"+calendarEnd.plusDays(1)+"' ORDER BY cal_date",(RowCallbackHandler)rs -> {
            var timestamp=rs.getTimestamp(1);int flag=rs.getInt(2);
            if(timestamp==null || rs.wasNull() || flag!=0 && flag!=1)throw new IllegalStateException("Invalid margin calendar row");
            LocalDate date=timestamp.toLocalDateTime().toLocalDate();
            if(!dates.add(date))throw new IllegalStateException("Duplicate margin calendar date");
            if(flag==1)sessions.add(date);
        });
        if(dates.size()!=calendarEnd.toEpochDay()-start.toEpochDay()+1)
            throw new IllegalStateException("Margin calendar natural-day grid is incomplete");
        LocalDate lastSession=sessions.floor(through),sourceEnd=lastSession==null?null:sessions.lower(lastSession);
        LocalDate firstSource=sessions.lower(from);
        if(sourceEnd==null || firstSource==null)throw new IllegalStateException("Margin publication calendar unavailable");
        var values=new LinkedHashMap<String,Map<String,List<PointInTimeFactorJoin.Observation>>>();
        for(String factor:factors)values.put(factor,new LinkedHashMap<>());
        long[] count={0};var seen=new HashSet<String>();
        RowCallbackHandler consume=rs -> {
            if(++count[0]>maxRows)throw new IllegalStateException("Margin factor read exceeds row budget");
            String symbol=rs.getString(1);var timestamp=rs.getTimestamp(2);
            if(symbol==null || symbol.isBlank() || timestamp==null)throw new IllegalStateException("Missing margin identity");
            LocalDate observed=timestamp.toLocalDateTime().toLocalDate(),available=sessions.higher(observed);
            if(available==null)throw new IllegalStateException("Margin calendar has no next session");
            if(!seen.add(symbol+"/"+observed))throw new IllegalStateException("Duplicate margin source key");
            double balance=rs.getDouble(3);if(rs.wasNull())balance=Double.NaN;
            double shortBalance=rs.getDouble(4);if(rs.wasNull())shortBalance=Double.NaN;
            for(String factor:factors)values.get(factor).computeIfAbsent(symbol,ignored->new ArrayList<>()).add(
                    new PointInTimeFactorJoin.Observation(observed,available,
                            factor.equals("ownership_margin_balance")?balance:shortBalance));
        };
        String base="SELECT ts_code,trade_date,rzye,rqye FROM margin_detail WHERE trade_date>='"+start
                +"' AND trade_date<'"+sourceEnd.plusDays(1)+"'";
        jdbc.query(base+" AND trade_date<'"+firstSource+"' LATEST ON trade_date PARTITION BY ts_code",consume);
        jdbc.query(base+" AND trade_date>='"+firstSource+"' ORDER BY trade_date,ts_code LIMIT "+(maxRows+1),consume);
        if(!before.equals(state()))throw new IllegalStateException("Margin sources changed during read");
        var frozen=new LinkedHashMap<String,Map<String,List<PointInTimeFactorJoin.Observation>>>();
        values.forEach((factor,stocks)->{
            var copy=new LinkedHashMap<String,List<PointInTimeFactorJoin.Observation>>();
            stocks.forEach((symbol,rows)->copy.put(symbol,List.copyOf(rows)));frozen.put(factor,Map.copyOf(copy));
        });
        return new QuestDbEventFactors.Snapshot(Map.copyOf(frozen),List.copyOf(before),Map.of("margin_detail",count[0]),start);
    }
    private List<Map<String,Object>> state() {
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,walEnabled,table_suspended FROM tables()"
                +" WHERE table_name IN ('margin_detail','exchange_calendar') ORDER BY table_name");
        if(rows.size()!=2)throw new IllegalStateException("Missing margin/calendar source");
        for(var row:rows)if(Boolean.TRUE.equals(row.get("table_suspended"))
                || Boolean.TRUE.equals(row.get("walEnabled")) && !QuestDbWriteChecks.walSettled(jdbc,(String)row.get("table_name")))
            throw new IllegalStateException("Margin source WAL not settled");
        return rows;
    }
}
