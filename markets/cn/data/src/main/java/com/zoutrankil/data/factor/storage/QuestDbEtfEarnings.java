package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.EtfEarningsOutlook;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Retains latest valid pre-window announcements plus in-window events for dated ETF aggregation. */
public final class QuestDbEtfEarnings {
    public record Snapshot(LocalDate from,LocalDate through,List<EtfEarningsOutlook.Event> forecasts,
            List<EtfEarningsOutlook.Event> express,long sourceRows,List<Map<String,Object>> sourceState) {
        public Map<String,EtfEarningsOutlook.Event> latest(LocalDate date) {
            if(date.isBefore(from) || date.isAfter(through))throw new IllegalArgumentException("Earnings date outside snapshot");
            return EtfEarningsOutlook.latest(forecasts,express,date);
        }
    }
    private final JdbcTemplate jdbc;
    public QuestDbEtfEarnings(JdbcTemplate template) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(90);
    }
    public Snapshot read(LocalDate from,LocalDate through,int maxRows) {
        if(from==null || through==null || from.isAfter(through) || ChronoUnit.DAYS.between(from,through)>30
                || maxRows<1 || maxRows>1_000_000)throw new IllegalArgumentException("Bounded earnings output window required");
        var before=state();long[] count={0};
        var forecast=readEvents("forecast",from,through,maxRows,count);
        var express=readEvents("express",from,through,maxRows,count);
        if(!before.equals(state()))throw new IllegalStateException("ETF earnings source changed during read");
        return new Snapshot(from,through,forecast,express,count[0],before);
    }
    private List<EtfEarningsOutlook.Event> readEvents(String table,LocalDate from,LocalDate through,int maxRows,long[] count) {
        boolean forecast=table.equals("forecast");
        var baseline=new LinkedHashMap<String,EtfEarningsOutlook.Event>();var events=new ArrayList<EtfEarningsOutlook.Event>();
        jdbc.query("SELECT ts_code,ann_date,"+(forecast?"p_change_min,p_change_max":"yoy_dedu_np,yoy_net_profit,n_income")
                +" FROM "+table+" WHERE ann_date<'"+through.plusDays(1)+"' ORDER BY ann_date,ts_code,end_date LIMIT "+(maxRows-count[0]+1),
                (RowCallbackHandler)rs->{
                    if(++count[0]>maxRows)throw new IllegalStateException("ETF earnings source exceeds row budget");
                    var timestamp=rs.getTimestamp(2);if(timestamp==null)return;
                    double value=forecast?EtfEarningsOutlook.forecast(number(rs,3),number(rs,4))
                            :EtfEarningsOutlook.express(number(rs,3),number(rs,4),number(rs,5));
                    if(Double.isNaN(value))return;
                    String symbol=rs.getString(1);if(symbol==null || symbol.isBlank())throw new IllegalStateException("Missing earnings symbol");
                    var event=new EtfEarningsOutlook.Event(symbol,timestamp.toLocalDateTime().toLocalDate(),value);
                    if(event.announced().isBefore(from))baseline.put(symbol,event);else events.add(event);
                });
        var retained=new ArrayList<>(baseline.values());retained.addAll(events);return List.copyOf(retained);
    }
    private static double number(java.sql.ResultSet rs,int column)throws java.sql.SQLException {
        double value=rs.getDouble(column);return rs.wasNull()?Double.NaN:value;
    }
    public void verifyUnchanged(Snapshot snapshot) {
        if(!snapshot.sourceState().equals(state()))throw new IllegalStateException("ETF earnings changed after snapshot");
    }
    private List<Map<String,Object>> state() {
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn FROM tables() WHERE table_name IN ('forecast','express') ORDER BY table_name");
        if(rows.size()!=2)throw new IllegalStateException("ETF earnings sources missing");
        for(String table:List.of("forecast","express"))if(!QuestDbWriteChecks.walSettled(jdbc,table))
            throw new IllegalStateException("ETF earnings source WAL unsettled");
        return rows;
    }
}
