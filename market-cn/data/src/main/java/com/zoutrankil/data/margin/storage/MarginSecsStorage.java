package com.zoutrankil.data.margin.storage;

import com.zoutrankil.data.margin.domain.MarginSecsLimits;

import com.zoutrankil.data.margin.domain.MarginSecsState.*;
import com.zoutrankil.data.margin.domain.MarginSecsRows;

import com.zoutrankil.data.margin.mapper.MarginSecsMapper;
import com.zoutrankil.data.repository.StaticTargetIdentity;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.MarginSecs;
import com.zoutrankil.data.domain.MarginSecsDataset;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded physical snapshot and natural-key comparison for the D030 YEAR/WAL/DEDUP table. */
public final class MarginSecsStorage {
    public static final int MAX_ROWS = MarginSecsLimits.MAX_ROWS;
    public static final int MAX_BYTES = MarginSecsLimits.MAX_BYTES;
    private static final int MAX_DATE_ROWS = 6000;
    private final JdbcTemplate jdbc;
    private final String table;
    private final com.zoutrankil.data.margin.mapper.MarginSecsMapper mapper = new com.zoutrankil.data.margin.mapper.MarginSecsMapper();

    public MarginSecsStorage(JdbcTemplate jdbc, String table) {
        MarginSecsDataset.requireIsolatedTable(table);
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(30); this.jdbc.setMaxRows(MAX_ROWS + 1); this.table = table;
    }

    public Snapshot snapshot() {
        var rows = jdbc.query(select() + " ORDER BY trade_date,ts_code LIMIT " + (MAX_ROWS + 1), this::physical);
        if (rows.size() > MAX_ROWS) throw new IllegalStateException("D030 full target exceeds the one-million-row snapshot bound");
        var sorted = rows.stream().sorted(Comparator.comparing(MarginSecs::tradeDate).thenComparing(MarginSecs::tsCode)).toList();
        for (int i=1;i<sorted.size();i++) if (sorted.get(i-1).key().equals(sorted.get(i).key()))
            throw new IllegalStateException("D030 physical target contains duplicate natural keys");
        long bytes=0;for(var row:sorted){bytes=Math.addExact(bytes,MarginSecsRows.canonicalBytes(row).length+1L);if(bytes>MAX_BYTES)
            throw new IllegalStateException("D030 target snapshot exceeds the 256 MiB canonical byte bound");}
        return new Snapshot(List.copyOf(sorted), MarginSecsRows.fingerprint(sorted));
    }

    public List<MarginSecs> readDate(LocalDate date) {
        Objects.requireNonNull(date);
        var rows=jdbc.query(select()+" WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT "+(MAX_DATE_ROWS+1),this::physical,micros(date));
        if(rows.size()>MAX_DATE_ROWS)throw new IllegalStateException("D030 physical day exceeds its source response bound");
        for(int i=1;i<rows.size();i++)if(rows.get(i-1).key().equals(rows.get(i).key()))throw new IllegalStateException("D030 duplicate physical natural key");
        return List.copyOf(rows);
    }

    public List<MarginSecs> readRange(LocalDate from,LocalDate to,int maxRows) {
        if(from==null||to==null||from.isAfter(to)||maxRows<1||maxRows>MAX_ROWS)throw new IllegalArgumentException("D030 bounded range required");
        var rows=jdbc.query(select()+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "+(maxRows+1),
                this::physical,micros(from),micros(to.plusDays(1)));
        if(rows.size()>maxRows)throw new IllegalStateException("D030 physical read range exceeds its row bound");
        for(int i=1;i<rows.size();i++)if(rows.get(i-1).key().equals(rows.get(i).key()))throw new IllegalStateException("D030 duplicate physical natural key");
        return List.copyOf(rows);
    }

    public TargetRange targetRange() {
        return jdbc.query("SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros,count(*) AS row_count FROM \""+table+"\"",rs->{
            if(!rs.next())throw new SQLException("D030 target range unavailable");Object min=rs.getObject("min_micros"),max=rs.getObject("max_micros"),count=rs.getObject("row_count");
            if(!(count instanceof Number n)||n.longValue()>MAX_ROWS)throw new SQLException("D030 target row count unavailable/over bound");
            if(min==null&&max==null){if(n.longValue()!=0)throw new SQLException("D030 empty target range/count mismatch");return new TargetRange(null,null,0);}
            if(!(min instanceof Number a)||!(max instanceof Number b)||n.longValue()<1)throw new SQLException("D030 invalid target range");
            return new TargetRange(date(a.longValue()),date(b.longValue()),n.longValue());
        });
    }



    public static String physicalTargetId(JdbcTemplate jdbc,String table) {
        var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact D030 physical target identity required");
        return StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory);
    }



    private String select(){return "SELECT cast(trade_date AS long) AS trade_micros,ts_code,name,exchange FROM \""+table+"\"";}
    private MarginSecs physical(ResultSet rs,int n)throws SQLException {
        Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number micros))throw new SQLException("D030 trade_date required");
        var values=new LinkedHashMap<String,Object>();values.put("trade_date",date(micros.longValue()));values.put("ts_code",rs.getString("ts_code"));
        values.put("name",rs.getString("name"));values.put("exchange",rs.getString("exchange"));
        try{return mapper.fromValues(new com.zoutrankil.data.domain.DatasetValues(values));}
        catch(RuntimeException invalid){throw new SQLException("Invalid D030 physical row",invalid);}
    }

    private static long micros(LocalDate day){return new TemporalValues.CalendarTimestamp(day).storageEpoch(TemporalValues.EpochUnit.MICROS);}
    private static LocalDate date(long value)throws SQLException{try{return TemporalValues.CalendarTimestamp.fromStorageEpoch(value,TemporalValues.EpochUnit.MICROS).date();}catch(RuntimeException invalid){throw new SQLException("D030 invalid calendar timestamp",invalid);}}
}
