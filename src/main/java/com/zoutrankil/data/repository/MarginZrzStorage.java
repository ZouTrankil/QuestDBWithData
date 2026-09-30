package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MarginZrz;
import com.zoutrankil.data.domain.MarginZrzDataset;
import com.zoutrankil.data.domain.MarginZrzKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.MarginZrzMapper;
import com.zoutrankil.data.service.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/** Bounded complete snapshots for the D031 YEAR/WAL/non-DEDUP publisher. */
public final class MarginZrzStorage {
    public static final int MAX_ROWS=100_000,MAX_BYTES=64*1024*1024;
    public record Identity(long id,String directory,long writerTxn){public Identity{Objects.requireNonNull(directory);}}
    public record Snapshot(Identity identity,List<MarginZrz> rows,String fingerprint,int bytes){public Snapshot{Objects.requireNonNull(identity);rows=List.copyOf(rows);if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||bytes<0)throw new IllegalArgumentException("Bounded D031 snapshot proof required");}}
    private final JdbcTemplate jdbc;private final String table;
    public MarginZrzStorage(JdbcTemplate source,String table){DatasetDefinition.identifier(table);this.table=table;jdbc=new JdbcTemplate(Objects.requireNonNull(source).getDataSource());jdbc.setQueryTimeout(120);jdbc.setMaxRows(MAX_ROWS+1);}
    public Identity preflight(){QuestDbWriteChecks.preflight(jdbc,table,MarginZrzDataset.isolatedWriteDefinition(table));var obj=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);var wal=jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?",table);
        if(obj.size()!=1||wal.size()!=1||!(obj.getFirst().get("id") instanceof Number id)||!(obj.getFirst().get("directoryName") instanceof String dir)||!(wal.getFirst().get("writerTxn") instanceof Number txn))throw new IllegalStateException("Exact D031 YEAR/WAL target required");return new Identity(id.longValue(),dir,txn.longValue());}
    public Snapshot snapshot()throws Exception{return snapshot("",List.of());}
    public Snapshot outside(LocalDate from,LocalDate to)throws Exception{requireWindow(from,to);return snapshot("(trade_date<cast(? AS TIMESTAMP) OR trade_date>=cast(? AS TIMESTAMP))",List.of(micros(from),micros(to.plusDays(1))));}
    public Snapshot window(LocalDate from,LocalDate to)throws Exception{requireWindow(from,to);return snapshot("trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP)",List.of(micros(from),micros(to.plusDays(1))));}
    private Snapshot snapshot(String predicate,List<?> args)throws Exception{Identity before=preflight();String sql=select()+(predicate.isBlank()?"":" WHERE "+predicate)+" LIMIT "+(MAX_ROWS+1);var rows=jdbc.query(sql,this::physical,args.toArray());
        if(rows.size()>MAX_ROWS)throw new IllegalStateException("D031 full-table snapshot exceeds 100000-row bound");rows=new ArrayList<>(ordered(rows));var keys=new HashSet<MarginZrzKey>();var digest=MessageDigest.getInstance("SHA-256");int bytes=0;
        for(var row:rows){if(!keys.add(row.key()))throw new IllegalStateException("D031 physical target contains duplicate trade_date keys");byte[] b=MarginZrzWritePort.CODEC.canonicalBytes(row);digest.update(b);digest.update((byte)'\n');bytes=Math.addExact(bytes,b.length+1);if(bytes>MAX_BYTES)throw new IllegalStateException("D031 canonical snapshot exceeds 64 MiB");}
        Identity after=preflight();if(before.id()!=after.id()||!before.directory().equals(after.directory())||before.writerTxn()!=after.writerTxn())throw new IllegalStateException("D031 physical generation changed during snapshot");return new Snapshot(after,rows,HexFormat.of().formatHex(digest.digest()),bytes);}
    private MarginZrz physical(java.sql.ResultSet rs,int n)throws SQLException{Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number micros))throw new SQLException("D031 trade_date required");var v=new LinkedHashMap<String,Object>();v.put("trade_date",TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(),TemporalValues.EpochUnit.MICROS).date());
        for(String field:List.of("ob","auc_amount","repo_amount","repay_amount","cb")){Object value=rs.getObject(field);if(value==null)v.put(field,null);else if(value instanceof Number number&&Double.isFinite(number.doubleValue()))v.put(field,number.doubleValue());else throw new SQLException("D031 invalid physical value "+field);}
        try{return new MarginZrzMapper().fromValues(new DatasetValues(v));}catch(RuntimeException invalid){throw new SQLException("Invalid D031 physical row",invalid);}}
    private String select(){return "SELECT cast(trade_date AS long) AS trade_micros,ob,auc_amount,repo_amount,repay_amount,cb FROM \""+table+"\"";}
    public static String physicalTargetId(JdbcTemplate jdbc,String table,Identity id){return StaticTargetIdentity.identify(jdbc,table,id.id(),id.directory());}
    public static boolean sameContent(Snapshot a,Snapshot b){return a!=null&&b!=null&&a.rows().size()==b.rows().size()&&a.fingerprint().equals(b.fingerprint());}
    public static List<MarginZrz> ordered(List<MarginZrz> rows){return rows.stream().sorted(Comparator.comparing(MarginZrz::tradeDate)).toList();}
    public static boolean sameRows(List<MarginZrz>a,List<MarginZrz>b){if(a.size()!=b.size())return false;var x=ordered(a);var y=ordered(b);for(int i=0;i<x.size();i++)if(!Arrays.equals(MarginZrzWritePort.CODEC.canonicalBytes(x.get(i)),MarginZrzWritePort.CODEC.canonicalBytes(y.get(i))))return false;return true;}
    private static long micros(LocalDate date){return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);}
    private static void requireWindow(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to))throw new IllegalArgumentException("D031 ordered date window required");}
}
