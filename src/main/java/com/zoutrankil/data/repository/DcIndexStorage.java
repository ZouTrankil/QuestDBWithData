package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/** Bounded full snapshot for D023 publication; list order is stable and physical duplicates are retained. */
public final class DcIndexStorage {
    public static final int MAX_ROWS=1_000_000,MAX_BYTES=256*1024*1024;
    public record Identity(long id,String directory){public Identity{if(id<0||directory==null||directory.isBlank())throw new IllegalArgumentException("dc_index table identity required");}}
    public record Snapshot(Identity identity,List<DcIndex> rows,String fingerprint,int bytes){public Snapshot{Objects.requireNonNull(identity);rows=List.copyOf(rows);Objects.requireNonNull(fingerprint);}}
    private final JdbcTemplate jdbc;private final String table;
    public DcIndexStorage(JdbcTemplate jdbc,String table){DatasetDefinition.identifier(table);this.table=table;this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());this.jdbc.setQueryTimeout(30);this.jdbc.setMaxRows(MAX_ROWS+1);}
    public Identity preflight(){QuestDbWriteChecks.preflight(jdbc,table,DcIndexDataset.definition(table));var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String dir))throw new IllegalStateException("Exact D023 table identity required");return new Identity(id.longValue(),dir);}
    public Snapshot snapshot()throws Exception{
        Identity before=preflight();var txn=jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?",table);
        if(txn.size()!=1||!(txn.getFirst().get("writerTxn") instanceof Number n))throw new IllegalStateException("dc_index WAL identity unavailable");long writerTxn=n.longValue();
        var fetched=jdbc.query("SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,name,leading,leading_code,pct_change,leading_pct,total_mv,turnover_rate,up_num,down_num FROM \""+table+"\" ORDER BY trade_date,ts_code LIMIT "+(MAX_ROWS+1),this::physical);
        if(fetched.size()>MAX_ROWS)throw new IllegalStateException("dc_index snapshot exceeds one-million-row bound");var rows=canonicalRows(fetched);byte[] canonical=canonical(rows);if(canonical.length>MAX_BYTES)throw new IllegalStateException("dc_index snapshot exceeds 256 MiB");
        Identity after=preflight();var end=jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?",table);
        if(!before.equals(after)||end.size()!=1||!(end.getFirst().get("writerTxn") instanceof Number e)||e.longValue()!=writerTxn)throw new IllegalStateException("dc_index physical target changed during snapshot");
        return new Snapshot(before,rows,java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)),canonical.length);
    }
    public static List<DcIndex> outside(List<DcIndex> rows,LocalDate from,LocalDate toExclusive){return rows.stream().filter(r->r.tradeDate().isBefore(from)||!r.tradeDate().isBefore(toExclusive)).toList();}
    public static List<DcIndex> sourceUnique(Collection<DcIndex> values){if(values==null||values.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Null dc_index source row");
        var sorted=values.stream().sorted(Comparator.comparing(DcIndex::tradeDate).thenComparing(DcIndex::tsCode)).toList();var seen=new HashSet<DcIndexKey>();for(var row:sorted)if(!seen.add(row.key()))throw new IllegalArgumentException("Duplicate dc_index source natural key");return sorted;}
    public static byte[] canonical(List<DcIndex> rows)throws Exception{
        var sorted=canonicalRows(rows);
        var out=new java.io.ByteArrayOutputStream();try(var data=new java.io.DataOutputStream(out)){for(var row:sorted){byte[] value=DcIndexWritePort.CODEC.canonicalBytes(row);data.writeInt(value.length);data.write(value);if(out.size()>MAX_BYTES)throw new IllegalStateException("dc_index snapshot byte bound exceeded");}}
        return out.toByteArray();
    }
    private static List<DcIndex> canonicalRows(Collection<DcIndex> rows){return rows.stream().sorted(Comparator.comparing(DcIndex::tradeDate).thenComparing(DcIndex::tsCode)
            .thenComparing(r->HexFormat.of().formatHex(DcIndexWritePort.CODEC.canonicalBytes(r)))).toList();}
    private DcIndex physical(ResultSet rs,int ix)throws SQLException{Object raw=rs.getObject("trade_date_micros");if(!(raw instanceof Number micros))throw new SQLException("dc_index trade_date required");
        try{return new DcIndex(rs.getString("ts_code"),TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(),TemporalValues.EpochUnit.MICROS).date(),rs.getString("name"),rs.getString("leading"),rs.getString("leading_code"),
                nullableDouble(rs,"pct_change"),nullableDouble(rs,"leading_pct"),nullableDouble(rs,"total_mv"),nullableDouble(rs,"turnover_rate"),nullableInt(rs,"up_num"),nullableInt(rs,"down_num"));}
        catch(RuntimeException e){throw new SQLException("Invalid dc_index physical row",e);}}
    private static Double nullableDouble(ResultSet rs,String field)throws SQLException{double v=rs.getDouble(field);return rs.wasNull()?null:v;}
    private static Integer nullableInt(ResultSet rs,String field)throws SQLException{int v=rs.getInt(field);return rs.wasNull()?null:v;}
    public static String physicalTargetId(JdbcTemplate jdbc,String table,Identity identity){return StaticTargetIdentity.identify(jdbc,table,identity.id(),identity.directory());}
}
