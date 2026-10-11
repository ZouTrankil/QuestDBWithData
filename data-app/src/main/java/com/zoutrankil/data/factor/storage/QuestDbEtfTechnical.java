package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Existing provider RSI input, projected once for the requested ETF output window. */
public final class QuestDbEtfTechnical {
    public record Row(LocalDate date,String symbol,double rsi6) {}
    public record Snapshot(List<Row> rows,List<Map<String,Object>> sourceState) {}
    private final JdbcTemplate jdbc;
    public QuestDbEtfTechnical(JdbcTemplate template) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(60);
    }
    public Snapshot read(LocalDate from,LocalDate through,int maxRows) {
        if(from==null || through==null || from.isAfter(through) || ChronoUnit.DAYS.between(from,through)>30
                || maxRows<1 || maxRows>1_000_000)throw new IllegalArgumentException("Bounded ETF technical interval required");
        var before=state();var rows=new ArrayList<Row>();var keys=new HashSet<List<Object>>();
        jdbc.query("SELECT trade_date,ts_code,rsi_bfq_6 FROM etf_factor WHERE trade_date>='"+from
                +"' AND trade_date<'"+through.plusDays(1)+"' ORDER BY trade_date,ts_code LIMIT "+(maxRows+1),(RowCallbackHandler)rs->{
            if(rows.size()>=maxRows)throw new IllegalStateException("ETF technical source exceeds row budget");
            var stamp=rs.getTimestamp(1);var symbol=rs.getString(2);
            if(stamp==null || symbol==null || symbol.isBlank())throw new IllegalStateException("Missing ETF technical identity");
            var date=stamp.toLocalDateTime().toLocalDate();
            if(!keys.add(List.of(date,symbol)))throw new IllegalStateException("Duplicate ETF technical date/symbol");
            double value=rs.getDouble(3);if(rs.wasNull())value=Double.NaN;
            rows.add(new Row(date,symbol,value));
        });
        if(!before.equals(state()))throw new IllegalStateException("ETF technical source changed during read");
        return new Snapshot(List.copyOf(rows),before);
    }
    public void verifyUnchanged(Snapshot snapshot) {
        if(!snapshot.sourceState().equals(state()))throw new IllegalStateException("ETF technical source changed after read");
    }
    private List<Map<String,Object>> state() {
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name='etf_factor'");
        if(rows.size()!=1 || !QuestDbWriteChecks.walSettled(jdbc,"etf_factor"))
            throw new IllegalStateException("ETF technical source missing or WAL unsettled");
        return rows;
    }
}
