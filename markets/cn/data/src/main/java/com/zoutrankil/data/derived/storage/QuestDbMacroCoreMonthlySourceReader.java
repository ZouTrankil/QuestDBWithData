package com.zoutrankil.data.derived.storage;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData.*;
import static com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData.*;
import com.zoutrankil.data.derived.port.MacroCoreMonthlySourceReadPort;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.*;

/** Physical SQL, bounded statements and exact source decoders. */
public final class QuestDbMacroCoreMonthlySourceReader implements MacroCoreMonthlySourceReadPort {
    private final JdbcTemplate jdbc;
    public QuestDbMacroCoreMonthlySourceReader(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public Snapshot snapshot(long until){var result=new ArrayList<PhysicalSnapshot>();for(var spec:SOURCE_SPECS.values())result.add(snapshot(spec,until));return new Snapshot(result);}
    private PhysicalSnapshot snapshot(SourceSpec spec,long until){
        String schema=query("SELECT \"column\",\"type\",designated,upsertKey FROM table_columns('"+spec.table()+"') LIMIT "+(spec.fields().size()+1),spec.fields().size()+1,until,rs->{
            var actual=new LinkedHashMap<String,String>();var designated=new ArrayList<String>();var keys=new HashSet<String>();
            while(rs.next()){String name=rs.getString("column"),type=rs.getString("type");if(name==null||type==null||actual.putIfAbsent(name,type)!=null)throw new IllegalStateException("D104 duplicate or incomplete source column");if(bool(rs,"designated"))designated.add(name);if(bool(rs,"upsertKey"))keys.add(name);}
            var expected=new LinkedHashMap<String,String>();spec.fields().forEach(f->expected.put(f,spec.type(f)));
            if(!actual.equals(expected)||!List.copyOf(actual.keySet()).equals(spec.fields())||!designated.equals(List.of(spec.timestamp()))||!keys.equals(Set.of(spec.timestamp())))throw new IllegalStateException("D104 exact frozen source schema/key differs: "+spec.table());
            return hash(actual.toString()+designated+keys);
        });
        var before=metadata(spec,schema,until);
        if(before.physicalTxn()!=null&&before.walTxn()!=null&&before.metadataRowCount()!=null)return before;
        if(before.writerTxn()!=0||before.sequenceTxn()!=0||before.pendingRows()!=0||before.bufferedTxns()!=0||before.physicalTxn()!=null&&before.physicalTxn()!=0||before.walTxn()!=null&&before.walTxn()!=0||before.metadataRowCount()!=null&&before.metadataRowCount()!=0)throw new IllegalStateException("D104 nullable source frontier requires a new empty WAL table");
        long count=query("SELECT count() AS actual_rows FROM \""+spec.table()+"\" LIMIT 2",2,until,rs->{if(!rs.next())throw new IllegalStateException("D104 independent empty-source count absent");long value=counter(rs,"actual_rows");if(rs.next())throw new IllegalStateException("D104 empty-source count ambiguous");return value;});
        if(count!=0||!before.equals(metadata(spec,schema,until)))throw new IllegalStateException("D104 source null frontier is not stable independently counted empty");return before;
    }
    private PhysicalSnapshot metadata(SourceSpec spec,String schema,long until){
        return query("SELECT t.id,t.directoryName,t.table_txn,t.wal_txn,t.table_row_count,t.partitionBy,t.designatedTimestamp,t.walEnabled,t.dedup,t.matView,t.table_suspended,t.wal_pending_row_count,w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='"+spec.table()+"' LIMIT 2",2,until,rs->{
            if(!rs.next())throw new IllegalStateException("D104 source table/WAL absent: "+spec.table());
            long id=counter(rs,"id"),seq=counter(rs,"sequencerTxn"),writer=counter(rs,"writerTxn"),pending=counter(rs,"wal_pending_row_count"),buffered=counter(rs,"bufferedTxnSize");Long txn=nullableCounter(rs,"table_txn"),wal=nullableCounter(rs,"wal_txn"),rowCount=nullableCounter(rs,"table_row_count");String directory=rs.getString("directoryName");
            if(!"YEAR".equals(rs.getString("partitionBy"))||!spec.timestamp().equals(rs.getString("designatedTimestamp"))||!bool(rs,"walEnabled")||!bool(rs,"dedup")||bool(rs,"matView")||bool(rs,"table_suspended")||bool(rs,"suspended")||pending!=0||buffered!=0||writer!=seq||wal!=null&&wal!=writer)throw new IllegalStateException("D104 source YEAR/WAL/DEDUP or settled writer differs: "+spec.table());
            if(rs.next())throw new IllegalStateException("D104 source metadata ambiguous");return new PhysicalSnapshot(spec.table(),id,directory,txn,wal,seq,writer,pending,buffered,rowCount,schema);
        });
    }
    public String sourceSql(String table,LocalDate from,LocalDate to){requireWindow(from,to);var spec=requireSpec(table);return select(spec)+" WHERE "+spec.timestamp()+" >= '"+from+"' AND "+spec.timestamp()+" < '"+YearMonth.from(to).plusMonths(1).atDay(1)+"' ORDER BY "+spec.timestamp()+" LIMIT 13";}
    public String contextSql(LocalDate from){new MacroCoreMonthlyKey(YearMonth.from(Objects.requireNonNull(from)));if(from.getDayOfMonth()!=1)throw new IllegalArgumentException("D104 context anchor must be first-day month");return select(SOURCE_SPECS.get("sf_month"))+" WHERE month < '"+from+"' ORDER BY month DESC LIMIT 12";}
    private static SourceSpec requireSpec(String table){var s=SOURCE_SPECS.get(table);if(s==null)throw new IllegalArgumentException("D104 fixed six-source table required");return s;}
    private static String select(SourceSpec spec){return "SELECT "+String.join(",",spec.fields().stream().map(f->f.equals(spec.timestamp())?"cast(\""+f+"\" AS LONG) AS \""+f+"\"":"\""+f+"\"").toList())+" FROM \""+spec.table()+"\"";}
    private List<Map<String,Object>> readRows(String sql,SourceSpec spec,int cap,long until){return query(sql,cap,until,rs->{var result=new ArrayList<Map<String,Object>>();while(rs.next()){
        if(result.size()==cap)throw new IllegalStateException("D104 raw reader exceeded finite result cap");var row=new LinkedHashMap<String,Object>();
        for(String field:spec.fields()){
            if(field.equals(spec.timestamp())){long micros=rs.getLong(field);if(rs.wasNull())throw new IllegalStateException("D104 source period timestamp is NULL");var instant=TemporalValues.epoch(micros,TemporalValues.EpochUnit.MICROS,TemporalValues.Precision.MICROS);if(!instant.atOffset(ZoneOffset.UTC).toLocalTime().equals(LocalTime.MIDNIGHT))throw new IllegalStateException("D104 source period must be exact UTC midnight");row.put(field,instant.atOffset(ZoneOffset.UTC).toLocalDate());}
            else {Object value=rs.getObject(field);if(field.equals("quarter")){if(value!=null&&!(value instanceof String))throw new IllegalStateException("D104 GDP quarter must be STRING");}else if(value!=null&&(!(value instanceof Double n)||!Double.isFinite(n)))throw new IllegalStateException("D104 complete source metrics must be finite nullable DOUBLE");row.put(field,value);}
        }result.add(row);
    }return result;});}
    private <T>T query(String sql,int cap,long until,ResultSetExtractor<T> extractor){long remaining=until-System.nanoTime();if(remaining<=0)throw new IllegalStateException("D104 complete source read exceeded twenty seconds");T result=jdbc.query(connection->{var statement=connection.prepareStatement(sql);statement.setQueryTimeout((int)Math.max(1,(remaining+999_999_999L)/1_000_000_000L));statement.setMaxRows(cap);return statement;},extractor);if(System.nanoTime()>until)throw new IllegalStateException("D104 source deadline expired");return result;}
    private static Long nullableCounter(ResultSet rs,String field)throws SQLException{if(rs.getObject(field)==null)return null;return counter(rs,field);}
    private static long counter(ResultSet rs,String field)throws SQLException{Object value=rs.getObject(field);if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0)throw new IllegalStateException("Known nonnegative physical/WAL counter required");return ((Number)value).longValue();}
    private static boolean bool(ResultSet rs,String field)throws SQLException{Object value=rs.getObject(field);if(!(value instanceof Boolean flag))throw new IllegalStateException("Known physical/schema boolean required");return flag;}
    @Override public List<Map<String,Object>> readWindow(String table,LocalDate from,LocalDate to,long until){return readRows(sourceSql(table,from,to),requireSpec(table),13,until);}
    @Override public List<Map<String,Object>> readPrecedingSocialFinancingDescending(LocalDate from,long until){return readRows(contextSql(from),SOURCE_SPECS.get("sf_month"),12,until);}
    public static void requireWindow(LocalDate from,LocalDate to){
        if(from==null||to==null||from.getDayOfMonth()!=1||to.getDayOfMonth()!=1||to.isBefore(from)||ChronoUnit.MONTHS.between(YearMonth.from(from),YearMonth.from(to))>=12)throw new IllegalArgumentException("Explicit first-day inclusive D104 window of at most twelve months required");new MacroCoreMonthlyKey(YearMonth.from(from));new MacroCoreMonthlyKey(YearMonth.from(to));
    }
    static String hash(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
