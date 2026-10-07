package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.EquityStyleMonthlyMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.*;

/** D103 bounded full-field source census and legacy last-nonnull monthly pivot. */
public class EquityStyleMonthlySource {
    public static final int MAX_MONTHS=12, MAX_SOURCE_ROWS=6200;
    public static final List<String> SOURCE_FIELDS=IndexMonthlyDataset.columns().stream().map(DatasetDefinition.Column::storageName).toList();
    public static final Map<String,String> FEATURE_CODES;
    static {
        var m=new LinkedHashMap<String,String>();
        m.put("hs300_ret_1m","000300.SH");m.put("zz500_ret_1m","000905.SH");m.put("all_a_ret_1m","000985.SH");m.put("cs1000_ret_1m","000852.SH");
        // Preserve the existing owner bindings, including the legacy growth/value labels.
        m.put("value_ret_1m","000920.SH");m.put("growth_ret_1m","000921.SH");
        String[] sectors={"energy","materials","industrials","consumer_discretionary","consumer_staples","healthcare","financials","it","telecom","utilities"};
        for(int i=0;i<sectors.length;i++)m.put(sectors[i]+"_ret_1m",String.format(Locale.ROOT,"%06d.SH",986+i));
        FEATURE_CODES=Collections.unmodifiableMap(m);
    }
    public record Snapshot(String table,long tableId,String directory,long physicalTxn,long sequenceTxn,String schemaHash) {
        public Snapshot {
            DatasetDefinition.identifier(table);
            if(tableId<1||directory==null||directory.isBlank()||physicalTxn<0||sequenceTxn<0||schemaHash==null||!schemaHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Complete settled source physical identity required");
        }
        public String version(){return hash(table+"\n"+tableId+"\n"+directory+"\n"+physicalTxn+"\n"+sequenceTxn+"\n"+schemaHash);}
    }
    public record Batch(Snapshot snapshot,String rawFingerprint,int rawRows,List<EquityStyleMonthly> rows) {
        public Batch {
            Objects.requireNonNull(snapshot);rows=List.copyOf(rows);
            if(rawFingerprint==null||!rawFingerprint.matches("[0-9a-f]{64}")||rawRows<0||rawRows>MAX_SOURCE_ROWS||rows.size()>MAX_MONTHS)
                throw new IllegalArgumentException("Finite complete source census required");
        }
        public String fingerprint(){return hash(snapshot.version()+"\n"+rawFingerprint);}
    }
    private final JdbcTemplate jdbc;
    private final String table;
    public EquityStyleMonthlySource(JdbcTemplate jdbc,String table) {
        DatasetDefinition.identifier(table);this.table=table;
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20);
    }
    protected EquityStyleMonthlySource(String table){DatasetDefinition.identifier(table);this.table=table;this.jdbc=null;}
    public String table(){return table;}
    public Snapshot snapshot() {
        String schema=query("SELECT \"column\",\"type\",designated,upsertKey FROM table_columns('"+table+"') LIMIT 15",15,rs->{
            var actual=new LinkedHashMap<String,String>();var designated=new ArrayList<String>();var keys=new HashSet<String>();
            while(rs.next()){
                String name=rs.getString("column"),type=rs.getString("type");
                if(name==null||type==null||actual.putIfAbsent(name,type)!=null)throw new IllegalStateException("Duplicate/incomplete source column");
                if(bool(rs,"designated"))designated.add(name);if(bool(rs,"upsertKey"))keys.add(name);
            }
            var expected=new LinkedHashMap<String,String>();IndexMonthlyDataset.columns().forEach(c->expected.put(c.storageName(),c.storageType().name()));
            if(!actual.equals(expected)||!List.copyOf(actual.keySet()).equals(SOURCE_FIELDS)||!designated.equals(List.of("trade_date"))||!keys.isEmpty())
                throw new IllegalStateException("D103 requires the exact D022 fourteen-field non-deduplicating source schema");
            return hash(actual.toString()+designated+keys);
        });
        return query("SELECT t.id,t.directoryName,t.table_txn,t.partitionBy,t.designatedTimestamp,t.walEnabled,t.dedup,t.matView,"+
                "t.table_suspended,t.wal_pending_row_count,w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended "+
                "FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='"+table+"' LIMIT 2",2,rs->{
            if(!rs.next())throw new IllegalStateException("D103 source table/WAL is missing");
            long id=counter(rs,"id"),txn=counter(rs,"table_txn"),seq=counter(rs,"sequencerTxn"),writer=counter(rs,"writerTxn");
            String directory=rs.getString("directoryName");
            if(!"YEAR".equals(rs.getString("partitionBy"))||!"trade_date".equals(rs.getString("designatedTimestamp"))||!bool(rs,"walEnabled")
                    ||bool(rs,"dedup")||bool(rs,"matView")||bool(rs,"table_suspended")||bool(rs,"suspended")
                    ||counter(rs,"wal_pending_row_count")!=0||counter(rs,"bufferedTxnSize")!=0||writer!=seq)
                throw new IllegalStateException("D103 source YEAR/WAL/DEDUP=false or settled writer contract differs");
            if(rs.next())throw new IllegalStateException("Ambiguous D103 source physical identity");
            return new Snapshot(table,id,directory,txn,seq,schema);
        });
    }
    public Batch read(LocalDate from,LocalDate to) {
        requireWindow(from,to);Snapshot before=snapshot();
        var rows=query(sourceSql(from,to),MAX_SOURCE_ROWS+1,rs->{
            var out=new ArrayList<Map<String,Object>>();
            while(rs.next()){
                if(out.size()==MAX_SOURCE_ROWS)throw new IllegalStateException("D103 source row-cap sentinel reached; completeness is unknown");
                var row=new LinkedHashMap<String,Object>();
                for(var column:IndexMonthlyDataset.columns()){
                    String name=column.storageName();Object value;
                    switch(column.storageType()){
                        case SYMBOL -> value=rs.getString(name);
                        case DOUBLE -> {value=rs.getObject(name,Double.class);if(value!=null&&!Double.isFinite((Double)value))throw new IllegalStateException("Nonfinite source DOUBLE");}
                        case TIMESTAMP -> {
                            Long epoch=rs.getObject(name,Long.class);
                            Instant instant=epoch==null?null:TemporalValues.epoch(epoch,TemporalValues.EpochUnit.MICROS,TemporalValues.Precision.MICROS);
                            value=name.equals("trade_date")&&instant!=null?TemporalValues.CalendarTimestamp.fromStorage(instant).date():instant;
                        }
                        default -> throw new IllegalStateException("Unexpected D022 source type");
                    }
                    row.put(name,value);
                }
                out.add(Collections.unmodifiableMap(row));
            }
            return out;
        });
        var result=derive(rows,from,to);
        Snapshot after=snapshot();if(!before.equals(after))throw new IllegalStateException("D103 source changed during complete bounded read");
        return new Batch(after,rawFingerprint(rows),rows.size(),result);
    }
    public String sourceSql(LocalDate from,LocalDate to){
        requireWindow(from,to);
        var columns=SOURCE_FIELDS.stream().map(name->name.equals("trade_date")||name.equals("update_time")?"cast(\""+name+"\" AS LONG) AS \""+name+"\"":"\""+name+"\"").toList();
        String codes=String.join(",",FEATURE_CODES.values().stream().map(code->"'"+code+"'").toList());
        return "SELECT "+String.join(",",columns)+" FROM \""+table+"\" WHERE ts_code IN ("+codes+") AND trade_date >= '"+from+
                "' AND trade_date < '"+YearMonth.from(to).plusMonths(1).atDay(1)+"' ORDER BY trade_date,ts_code LIMIT "+(MAX_SOURCE_ROWS+1);
    }
    public static List<EquityStyleMonthly> derive(List<Map<String,Object>> raw,LocalDate from,LocalDate to){
        requireWindow(from,to);if(raw.size()>MAX_SOURCE_ROWS)throw new IllegalArgumentException("D103 raw source budget exceeded");
        var months=new TreeMap<YearMonth,Map<String,Double>>();var observed=new HashSet<String>();var keys=new HashSet<List<Object>>();
        LocalDate previousDate=null;String previousCode=null;
        for(var row:raw){
            if(!List.copyOf(row.keySet()).equals(SOURCE_FIELDS))throw new IllegalArgumentException("Complete ordered fourteen-field source row required");
            if(!(row.get("trade_date") instanceof LocalDate date)||!(row.get("ts_code") instanceof String code)||!FEATURE_CODES.containsValue(code)
                    ||date.isBefore(from)||!date.isBefore(YearMonth.from(to).plusMonths(1).atDay(1)))throw new IllegalArgumentException("Source code/date lies outside frozen monthly window");
            if(!keys.add(List.of(code,date)))throw new IllegalStateException("Duplicate (ts_code,trade_date) has no deterministic last ordering");
            if(previousDate!=null&&(date.isBefore(previousDate)||date.equals(previousDate)&&code.compareTo(previousCode)<=0))throw new IllegalStateException("Source rows are not strictly date/code ordered");
            previousDate=date;previousCode=code;
            for(String field:SOURCE_FIELDS){Object value=row.get(field);
                if(Set.of("close","open","high","low","pre_close","change","pct_chg","vol","amount").contains(field)
                        &&value!=null&&(!(value instanceof Double n)||!Double.isFinite(n)))throw new IllegalArgumentException("Source metrics must be nullable finite DOUBLE");
            }
            for(String field:List.of("layer","bucket"))if(row.get(field)!=null&&!(row.get(field) instanceof String))throw new IllegalArgumentException("Source SYMBOL must preserve its declared carrier");
            Object updated=row.get("update_time");
            if(updated!=null&&(!(updated instanceof Instant instant)||instant.getNano()%1000!=0))throw new IllegalArgumentException("Source update_time must preserve TIMESTAMP microseconds");
            Double value=(Double)row.get("pct_chg");if(value==null)continue;
            observed.add(code);months.computeIfAbsent(YearMonth.from(date),unused->new HashMap<>()).put(code,value);
        }
        if(!raw.isEmpty()&&!observed.containsAll(FEATURE_CODES.values()))throw new IllegalStateException("Required index code is missing or all-null in complete bounded window");
        var result=new ArrayList<EquityStyleMonthly>();var mapper=new EquityStyleMonthlyMapper();
        for(var entry:months.entrySet()){
            var values=new LinkedHashMap<String,Object>();values.put("month",entry.getKey().atDay(1));
            FEATURE_CODES.forEach((field,code)->values.put(field,entry.getValue().get(code)));
            values.put("small_large_ret_1m",difference((Double)values.get("cs1000_ret_1m"),(Double)values.get("hs300_ret_1m")));
            values.put("mid_large_ret_1m",difference((Double)values.get("zz500_ret_1m"),(Double)values.get("hs300_ret_1m")));
            values.put("growth_value_ret_1m",difference((Double)values.get("growth_ret_1m"),(Double)values.get("value_ret_1m")));
            for(String field:FEATURE_CODES.keySet())if(!Set.of("hs300_ret_1m","zz500_ret_1m","all_a_ret_1m","cs1000_ret_1m","growth_ret_1m","value_ret_1m").contains(field))
                values.put(field.replace("_ret_1m","_vs_all_a_1m"),difference((Double)values.get(field),(Double)values.get("all_a_ret_1m")));
            result.add(mapper.fromValues(values));
        }
        return List.copyOf(result);
    }
    private static Double difference(Double left,Double right){
        if(left==null||right==null)return null;double value=left-right;if(!Double.isFinite(value))throw new IllegalStateException("Derived difference overflows binary64");return value;
    }
    public static void requireWindow(LocalDate from,LocalDate to){
        if(from==null||to==null||from.getDayOfMonth()!=1||to.getDayOfMonth()!=1||to.isBefore(from)
                ||ChronoUnit.MONTHS.between(YearMonth.from(from),YearMonth.from(to))>=MAX_MONTHS)
            throw new IllegalArgumentException("Explicit first-day inclusive window of at most twelve months required");
    }
    public static void requireClosedWindow(LocalDate from,LocalDate to,LocalDate logicalDate){
        requireWindow(from,to);
        if(logicalDate==null||!YearMonth.from(to).isBefore(YearMonth.from(logicalDate)))throw new IllegalArgumentException("D103 accepts only months preceding the frozen logical month");
    }
    /** Incremental checkpoint eligibility is stronger than the nullable historical pivot. */
    public static void requireIncrementalCoverage(List<EquityStyleMonthly> rows,LocalDate from,LocalDate to){
        requireWindow(from,to);long expected=ChronoUnit.MONTHS.between(YearMonth.from(from),YearMonth.from(to))+1;
        if(rows.size()!=expected)throw new IllegalStateException("D103 incremental prefix has a missing source month");
        var mapper=new EquityStyleMonthlyMapper();YearMonth month=YearMonth.from(from);
        for(var row:rows){
            if(!row.month().equals(month))throw new IllegalStateException("D103 incremental source months are not contiguous");
            var values=mapper.values(row).asMap();
            if(FEATURE_CODES.keySet().stream().anyMatch(field->values.get(field)==null))throw new IllegalStateException("D103 incremental month requires all sixteen nonnull source returns");
            month=month.plusMonths(1);
        }
    }
    public static String rawFingerprint(List<Map<String,Object>> rows){
        var lines=new StringBuilder();for(var row:rows)for(String field:SOURCE_FIELDS){Object value=row.get(field);
            lines.append(field).append(':');if(value==null)lines.append("null");else if(value instanceof Double n)lines.append(Long.toUnsignedString(Double.doubleToRawLongBits(n)));
            else {String text=value.toString();lines.append(value.getClass().getSimpleName()).append(':').append(text.length()).append(':').append(text);}lines.append('\n');}
        return hash(lines.toString());
    }
    static String hash(String text){try{return FileEvidenceStore.sha256(text.getBytes(StandardCharsets.UTF_8));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private <T>T query(String sql,int cap,ResultSetExtractor<T> extractor){return jdbc.query(connection->{var statement=connection.prepareStatement(sql);statement.setQueryTimeout(20);statement.setMaxRows(cap);return statement;},extractor);}
    private static long counter(ResultSet rs,String field)throws SQLException{Object value=rs.getObject(field);if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0)throw new IllegalStateException("Exact nonnegative physical/WAL LONG required");return ((Number)value).longValue();}
    private static boolean bool(ResultSet rs,String field)throws SQLException{Object value=rs.getObject(field);if(!(value instanceof Boolean flag))throw new IllegalStateException("Known physical/schema boolean required");return flag;}
}
