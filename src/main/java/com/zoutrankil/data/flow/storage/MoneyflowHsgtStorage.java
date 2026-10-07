package com.zoutrankil.data.flow.storage;

import com.zoutrankil.data.flow.domain.MoneyflowHsgtLimits;

import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.*;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtRows;

import com.zoutrankil.data.repository.StaticTargetIdentity;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.MoneyflowHsgt;
import com.zoutrankil.data.domain.MoneyflowHsgtDataset;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.flow.mapper.MoneyflowHsgtMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/** Bounded full-table and window snapshots used by D027's no-DEDUP journaled publication. */
public final class MoneyflowHsgtStorage implements com.zoutrankil.data.flow.port.MoneyflowHsgtTables.Table {
    public static final int MAX_ROWS = MoneyflowHsgtLimits.MAX_ROWS;
    public static final int MAX_BYTES = MoneyflowHsgtLimits.MAX_BYTES;


    private final JdbcTemplate jdbc;
    private final String table;
    public MoneyflowHsgtStorage(JdbcTemplate jdbc,String table) {
        DatasetDefinition.identifier(table); this.table=table;
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(120); this.jdbc.setMaxRows(MAX_ROWS+1);
    }
    public Identity preflight() {
        QuestDbWriteChecks.preflight(jdbc,table,MoneyflowHsgtDataset.admittedWriteDefinition(table));
        var objects=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        var wal=jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?",table);
        if(objects.size()!=1||wal.size()!=1||!(objects.getFirst().get("id") instanceof Number id)
                ||!(objects.getFirst().get("directoryName") instanceof String directory)
                ||!(wal.getFirst().get("writerTxn") instanceof Number txn))
            throw new IllegalStateException("Exact D027 DAY/WAL target identity required");
        return new Identity(id.longValue(),directory,txn.longValue());
    }
    public Snapshot snapshot() throws Exception { return snapshot("",List.of()); }
    public Snapshot outside(LocalDate from,LocalDate to)throws Exception {
        requireWindow(from,to); return snapshot("(trade_date<cast(? AS TIMESTAMP) OR trade_date>=cast(? AS TIMESTAMP))",
                List.of(micros(from),micros(to.plusDays(1))));
    }
    public Snapshot window(LocalDate from,LocalDate to)throws Exception {
        requireWindow(from,to); return snapshot("trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP)",
                List.of(micros(from),micros(to.plusDays(1))));
    }
    private Snapshot snapshot(String predicate,List<?> args)throws Exception {
        Identity before=preflight();
        String sql="SELECT cast(trade_date AS long) AS trade_micros,ggt_ss,ggt_sz,hgt,sgt,north_money,south_money FROM \""+table+"\""
                +(predicate.isBlank()?"":" WHERE "+predicate)+" ORDER BY trade_date,ggt_ss,ggt_sz,hgt,sgt,north_money,south_money LIMIT "+(MAX_ROWS+1);
        var rows=jdbc.query(sql,this::physical,args.toArray());
        if(rows.size()>MAX_ROWS)throw new IllegalStateException("D027 full-table snapshot exceeds 100000-row bound");
        rows=new ArrayList<>(MoneyflowHsgtRows.ordered(rows));
        var digest=MessageDigest.getInstance("SHA-256");int bytes=0;
        for(var row:rows){byte[] encoded=MoneyflowHsgtRows.canonicalBytes(row);digest.update(encoded);digest.update((byte)'\n');bytes=Math.addExact(bytes,encoded.length+1);if(bytes>MAX_BYTES)throw new IllegalStateException("D027 canonical snapshot exceeds 64 MiB");}
        Identity after=preflight();
        if(before.id()!=after.id()||!before.directory().equals(after.directory())||before.writerTxn()!=after.writerTxn())
            throw new IllegalStateException("D027 physical generation changed during snapshot");
        return new Snapshot(after,rows,HexFormat.of().formatHex(digest.digest()),bytes);
    }
    private MoneyflowHsgt physical(java.sql.ResultSet rs,int n)throws SQLException {
        Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number micros))throw new SQLException("D027 trade_date missing");
        var v=new LinkedHashMap<String,Object>();v.put("trade_date",TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(),TemporalValues.EpochUnit.MICROS).date());
        for(String f:List.of("ggt_ss","ggt_sz","hgt","sgt","north_money","south_money")){
            Object value=rs.getObject(f);if(value!=null&&(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())))throw new SQLException("D027 invalid physical field "+f);
            v.put(f,value==null?null:((Number)value).doubleValue());}
        try{return new MoneyflowHsgtMapper().fromValues(new com.zoutrankil.data.domain.DatasetValues(v));}
        catch(RuntimeException invalid){throw new SQLException("Invalid physical D027 row",invalid);}
    }
    public static String physicalTargetId(JdbcTemplate jdbc,String table,Identity identity){return StaticTargetIdentity.identify(jdbc,table,identity.id(),identity.directory());}



    private static long micros(LocalDate date){return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);}
    private static void requireWindow(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to))throw new IllegalArgumentException("D027 ordered date window required");}
}
