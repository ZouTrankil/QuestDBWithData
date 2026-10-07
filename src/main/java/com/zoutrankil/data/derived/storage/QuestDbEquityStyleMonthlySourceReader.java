package com.zoutrankil.data.derived.storage;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.derived.domain.EquityStyleMonthlySourceData.*;
import static com.zoutrankil.data.derived.domain.EquityStyleMonthlySourceData.*;
import com.zoutrankil.data.derived.port.EquityStyleMonthlySourceReadPort;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.*;

/** Physical SQL, bounded statements and exact source decoders. */
public final class QuestDbEquityStyleMonthlySourceReader implements EquityStyleMonthlySourceReadPort {
    private final JdbcTemplate jdbc;
    private final String table;
    public QuestDbEquityStyleMonthlySourceReader(JdbcTemplate jdbc,String table) {
        DatasetDefinition.identifier(table);this.table=table;
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20);
    }
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
    @Override public List<Map<String,Object>> readWindow(LocalDate from,LocalDate to) {
        return query(sourceSql(from,to),MAX_SOURCE_ROWS+1,rs->{
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
    }
    public String sourceSql(LocalDate from,LocalDate to){
        requireWindow(from,to);
        var columns=SOURCE_FIELDS.stream().map(name->name.equals("trade_date")||name.equals("update_time")?"cast(\""+name+"\" AS LONG) AS \""+name+"\"":"\""+name+"\"").toList();
        String codes=String.join(",",FEATURE_CODES.values().stream().map(code->"'"+code+"'").toList());
        return "SELECT "+String.join(",",columns)+" FROM \""+table+"\" WHERE ts_code IN ("+codes+") AND trade_date >= '"+from+
                "' AND trade_date < '"+YearMonth.from(to).plusMonths(1).atDay(1)+"' ORDER BY trade_date,ts_code LIMIT "+(MAX_SOURCE_ROWS+1);
    }
    private <T>T query(String sql,int cap,ResultSetExtractor<T> extractor){return jdbc.query(connection->{var statement=connection.prepareStatement(sql);statement.setQueryTimeout(20);statement.setMaxRows(cap);return statement;},extractor);}
    private static long counter(ResultSet rs,String field)throws SQLException{Object value=rs.getObject(field);if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0)throw new IllegalStateException("Exact nonnegative physical/WAL LONG required");return ((Number)value).longValue();}
    private static boolean bool(ResultSet rs,String field)throws SQLException{Object value=rs.getObject(field);if(!(value instanceof Boolean flag))throw new IllegalStateException("Known physical/schema boolean required");return flag;}
    public static void requireWindow(LocalDate from,LocalDate to){
        if(from==null||to==null||from.getDayOfMonth()!=1||to.getDayOfMonth()!=1||to.isBefore(from)
                ||ChronoUnit.MONTHS.between(YearMonth.from(from),YearMonth.from(to))>=MAX_MONTHS)
            throw new IllegalArgumentException("Explicit first-day inclusive window of at most twelve months required");
    }
    static String hash(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
