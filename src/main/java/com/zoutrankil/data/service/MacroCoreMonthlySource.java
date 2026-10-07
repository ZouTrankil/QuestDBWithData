package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MacroCoreMonthlyMapper;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** D104 bounded six-source monthly join; source observations retain their stored units. */
public class MacroCoreMonthlySource {
    public static final int MAX_MONTHS=12, MAX_SOURCE_ROWS=84;
    public static final List<String> SOURCE_TABLES=List.of("cn_cpi","cn_ppi","cn_pmi","cn_m","cn_gdp","sf_month");
    public static final List<String> REQUIRED_MONTHLY_FIELDS=List.of("cpi_yoy","ppi_yoy","pmi_mfg","m2_yoy","social_financing_stock","new_rmb_loan");
    public record SourceSpec(String table,String timestamp,List<String> fields) {
        public SourceSpec { fields=List.copyOf(fields); }
        public String type(String column){return column.equals(timestamp)?"TIMESTAMP":column.equals("quarter")?"STRING":"DOUBLE";}
    }
    public static final Map<String,SourceSpec> SOURCE_SPECS=specs();
    private static Map<String,SourceSpec> specs(){var specs=new LinkedHashMap<String,SourceSpec>();
        specs.put("cn_cpi", new SourceSpec("cn_cpi", "month", List.of("month", "nt_val", "nt_yoy", "nt_mom", "nt_accu", "town_val", "town_yoy", "town_mom", "town_accu", "cnt_val", "cnt_yoy", "cnt_mom", "cnt_accu")));
        specs.put("cn_ppi", new SourceSpec("cn_ppi", "month", List.of("month", "ppi_yoy", "ppi_mp_yoy", "ppi_mp_qm_yoy", "ppi_mp_rm_yoy", "ppi_mp_p_yoy", "ppi_cg_yoy", "ppi_cg_f_yoy", "ppi_cg_c_yoy", "ppi_cg_adu_yoy", "ppi_cg_dcg_yoy", "ppi_mom", "ppi_mp_mom", "ppi_mp_qm_mom", "ppi_mp_rm_mom", "ppi_mp_p_mom", "ppi_cg_mom", "ppi_cg_f_mom", "ppi_cg_c_mom", "ppi_cg_adu_mom", "ppi_cg_dcg_mom", "ppi_accu", "ppi_mp_accu", "ppi_mp_qm_accu", "ppi_mp_rm_accu", "ppi_mp_p_accu", "ppi_cg_accu", "ppi_cg_f_accu", "ppi_cg_c_accu", "ppi_cg_adu_accu", "ppi_cg_dcg_accu")));
        specs.put("cn_pmi", new SourceSpec("cn_pmi", "month", List.of("month", "pmi010000", "pmi010100", "pmi010200", "pmi010300", "pmi010400", "pmi010401", "pmi010402", "pmi010403", "pmi010500", "pmi010501", "pmi010502", "pmi010503", "pmi010600", "pmi010601", "pmi010602", "pmi010603", "pmi010700", "pmi010701", "pmi010702", "pmi010703", "pmi010800", "pmi010801", "pmi010802", "pmi010803", "pmi010900", "pmi011000", "pmi011100", "pmi011200", "pmi011300", "pmi011400", "pmi011500", "pmi011600", "pmi011700", "pmi011800", "pmi011900", "pmi012000", "pmi020100", "pmi020101", "pmi020102", "pmi020200", "pmi020201", "pmi020202", "pmi020300", "pmi020301", "pmi020302", "pmi020400", "pmi020401", "pmi020402", "pmi020500", "pmi020501", "pmi020502", "pmi020600", "pmi020601", "pmi020602", "pmi020700", "pmi020800", "pmi020900", "pmi021000", "pmi030000")));
        specs.put("cn_m", new SourceSpec("cn_m", "month", List.of("month", "m0", "m0_yoy", "m0_mom", "m1", "m1_yoy", "m1_mom", "m2", "m2_yoy", "m2_mom")));
        specs.put("cn_gdp", new SourceSpec("cn_gdp", "report_date", List.of("quarter", "report_date", "gdp", "gdp_yoy", "pi", "pi_yoy", "si", "si_yoy", "ti", "ti_yoy")));
        specs.put("sf_month", new SourceSpec("sf_month", "month", List.of("month", "inc_month", "inc_cumval", "stk_endval")));
        return Collections.unmodifiableMap(specs);
    }
    public record PhysicalSnapshot(String table,long tableId,String directory,Long physicalTxn,Long walTxn,long sequenceTxn,long writerTxn,long pendingRows,long bufferedTxns,Long metadataRowCount,String schemaHash) {
        public PhysicalSnapshot {
            if(!SOURCE_TABLES.contains(table)||tableId<0||directory==null||directory.isBlank()||physicalTxn!=null&&physicalTxn<0||walTxn!=null&&walTxn<0||sequenceTxn<0||writerTxn<0||pendingRows<0||bufferedTxns<0||metadataRowCount!=null&&metadataRowCount<0||schemaHash==null||!schemaHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("D104 complete source physical identity required");
        }
    }
    public record Snapshot(List<PhysicalSnapshot> sources) {
        public Snapshot {sources=List.copyOf(sources);if(!sources.stream().map(PhysicalSnapshot::table).toList().equals(SOURCE_TABLES))throw new IllegalArgumentException("D104 fixed six-source vector required");}
        public String version(){return hash(sources.toString());}
        public String identity(){var text=new StringBuilder();for(var s:sources)text.append(s.table()).append(':').append(s.tableId()).append(':').append(s.directory()).append(':').append(s.schemaHash()).append('\n');return hash(text.toString());}
    }
    public record Derived(List<MacroCoreMonthly> rows,List<YearMonth> candidateMonths,List<YearMonth> withheldMonths) {
        public Derived {rows=List.copyOf(rows);candidateMonths=List.copyOf(candidateMonths);withheldMonths=List.copyOf(withheldMonths);}
    }
    public record Batch(Snapshot snapshot,String rawFingerprint,int rawRows,List<MacroCoreMonthly> rows,
            List<YearMonth> candidateMonths,List<YearMonth> withheldMonths,int sfContextRows,Map<String,Integer> windowRowsBySource) {
        public Batch { Objects.requireNonNull(snapshot);rows=List.copyOf(rows);candidateMonths=List.copyOf(candidateMonths);withheldMonths=List.copyOf(withheldMonths);windowRowsBySource=Collections.unmodifiableMap(new LinkedHashMap<>(windowRowsBySource));
            if(rawFingerprint==null||!rawFingerprint.matches("[0-9a-f]{64}")||rawRows<0||rawRows>MAX_SOURCE_ROWS||rows.size()>MAX_MONTHS||sfContextRows<0||sfContextRows>12||candidateMonths.size()>12||!windowRowsBySource.keySet().equals(new LinkedHashSet<>(SOURCE_TABLES)))throw new IllegalArgumentException("D104 finite complete source batch required");
        }
        public String fingerprint(){return hash(snapshot.version()+"\n"+rawFingerprint);}
    }
    private final JdbcTemplate jdbc;
    public MacroCoreMonthlySource(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    protected MacroCoreMonthlySource(){this.jdbc=null;}
    public Snapshot snapshot(){return snapshot(deadline());}
    private Snapshot snapshot(long until){var result=new ArrayList<PhysicalSnapshot>();for(var spec:SOURCE_SPECS.values())result.add(snapshot(spec,until));return new Snapshot(result);}
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
    public Batch read(LocalDate from,LocalDate to){
        requireWindow(from,to);long until=deadline();var before=snapshot(until);var window=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var spec:SOURCE_SPECS.values()){var rows=readRows(sourceSql(spec.table(),from,to),spec,13,until);if(rows.size()>12)throw new IllegalStateException("D104 monthly source sentinel reached; complete window unknown");window.put(spec.table(),rows);}
        var context=readRows(contextSql(from),SOURCE_SPECS.get("sf_month"),12,until);Collections.reverse(context);
        var derived=derive(window,context,from,to);int rawRows=context.size()+window.values().stream().mapToInt(List::size).sum();if(rawRows>MAX_SOURCE_ROWS)throw new IllegalStateException("D104 aggregate raw source budget exceeded");
        String raw=rawFingerprint(window,context);var after=snapshot(until);if(!before.equals(after))throw new IllegalStateException("D104 six-source physical vector changed during bounded read");
        var counts=new LinkedHashMap<String,Integer>();window.forEach((table,rows)->counts.put(table,rows.size()));return new Batch(after,raw,rawRows,derived.rows(),derived.candidateMonths(),derived.withheldMonths(),context.size(),counts);
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
    public static Derived derive(Map<String,List<Map<String,Object>>> window,List<Map<String,Object>> context,LocalDate from,LocalDate to){
        requireWindow(from,to);if(!List.copyOf(window.keySet()).equals(SOURCE_TABLES))throw new IllegalArgumentException("D104 exact ordered six-source input required");if(context.size()>12)throw new IllegalArgumentException("D104 SF context exceeds twelve observations");
        var months=new TreeMap<YearMonth,Map<String,Double>>();
        for(var spec:SOURCE_SPECS.values()){
            var rows=window.get(spec.table());if(rows==null||rows.size()>12)throw new IllegalArgumentException("D104 source window exceeds twelve observations");validate(rows,spec,from,to,false);
            for(var row:rows){var month=YearMonth.from((LocalDate)row.get(spec.timestamp()));var values=months.computeIfAbsent(month,m->new HashMap<>());
                switch(spec.table()){
                    case "cn_cpi" -> values.put("cpi_yoy",(Double)row.get("nt_yoy"));
                    case "cn_ppi" -> values.put("ppi_yoy",(Double)row.get("ppi_yoy"));
                    case "cn_pmi" -> values.put("pmi_mfg",(Double)row.get("pmi010000"));
                    case "cn_m" -> values.put("m2_yoy",(Double)row.get("m2_yoy"));
                    case "cn_gdp" -> values.put("gdp_yoy",(Double)row.get("gdp_yoy"));
                    case "sf_month" -> {values.put("new_rmb_loan",(Double)row.get("inc_month"));values.put("social_financing_stock",(Double)row.get("stk_endval"));}
                    default -> throw new IllegalStateException("Unknown source");
                }
            }
        }
        validate(context,SOURCE_SPECS.get("sf_month"),from,to,true);var sf=new ArrayList<Map<String,Object>>(context);sf.addAll(window.get("sf_month"));
        for(int i=context.size();i<sf.size();i++){
            Double current=(Double)sf.get(i).get("stk_endval"),previous=i>=12?(Double)sf.get(i-12).get("stk_endval"):null;Double yoy=null;
            if(current!=null&&previous!=null){if(previous==0.0)throw new IllegalStateException("D104 social financing YoY zero denominator");double value=current/previous-1.0;if(!Double.isFinite(value))throw new IllegalStateException("D104 social financing YoY overflows binary64");yoy=value;}
            months.get(YearMonth.from((LocalDate)sf.get(i).get("month"))).put("social_financing_yoy",yoy);
        }
        var result=new ArrayList<MacroCoreMonthly>();var candidates=new ArrayList<>(months.keySet());var withheld=new ArrayList<YearMonth>();var mapper=new MacroCoreMonthlyMapper();
        for(var entry:months.entrySet()){
            if(REQUIRED_MONTHLY_FIELDS.stream().anyMatch(f->entry.getValue().get(f)==null)){withheld.add(entry.getKey());continue;}
            var values=new LinkedHashMap<String,Object>();values.put("month",entry.getKey().atDay(1));for(String field:MacroCoreMonthlyDataset.STORAGE_COLUMNS.subList(1,9))values.put(field,entry.getValue().get(field));result.add(mapper.fromValues(values));
        }
        return new Derived(result,candidates,withheld);
    }
    private static void validate(List<Map<String,Object>> rows,SourceSpec spec,LocalDate from,LocalDate to,boolean context){
        LocalDate previous=null;for(var row:rows){
            if(!List.copyOf(row.keySet()).equals(spec.fields()))throw new IllegalArgumentException("D104 complete ordered physical source fields required: "+spec.table());
            if(!(row.get(spec.timestamp()) instanceof LocalDate date))throw new IllegalArgumentException("D104 source period must be LocalDate exact UTC carrier");
            new MacroCoreMonthlyKey(YearMonth.from(date));if(context?!date.isBefore(from):date.isBefore(from)||!date.isBefore(YearMonth.from(to).plusMonths(1).atDay(1)))throw new IllegalArgumentException("D104 observation lies outside declared context/window");
            if(previous!=null&&!date.isAfter(previous))throw new IllegalStateException("D104 duplicate or unordered physical source observation");previous=date;
            if(spec.table().equals("cn_gdp")){if(date.getMonthValue()%3!=0||date.getDayOfMonth()!=date.lengthOfMonth()||!(row.get("quarter") instanceof String quarter)||!quarter.equals(date.getYear()+"Q"+(date.getMonthValue()/3)))throw new IllegalArgumentException("D104 GDP quarter/report_date identity differs");}
            else if(date.getDayOfMonth()!=1)throw new IllegalArgumentException("D104 monthly source must use its exact first-day carrier");
            for(String field:spec.fields())if(spec.type(field).equals("DOUBLE")&&row.get(field)!=null&&(!(row.get(field) instanceof Double n)||!Double.isFinite(n)))throw new IllegalArgumentException("D104 source metrics must be nullable finite DOUBLE");
        }
    }
    public static String rawFingerprint(Map<String,List<Map<String,Object>>> window,List<Map<String,Object>> context){
        var text=new StringBuilder();for(var spec:SOURCE_SPECS.values()){text.append(spec.table()).append(":window:").append(window.get(spec.table()).size()).append('\n');appendRaw(text,spec,window.get(spec.table()));}
        text.append("sf_month:preceding-observations:").append(context.size()).append('\n');appendRaw(text,SOURCE_SPECS.get("sf_month"),context);return hash(text.toString());
    }
    private static void appendRaw(StringBuilder text,SourceSpec spec,List<Map<String,Object>> rows){for(var row:rows)for(String field:spec.fields()){
        Object value=row.get(field);text.append(field).append(':');if(value==null)text.append("null");else if(value instanceof Double n)text.append(Long.toUnsignedString(Double.doubleToRawLongBits(n)));else {String s=value.toString();text.append(value.getClass().getSimpleName()).append(':').append(s.length()).append(':').append(s);}text.append('\n');
    }}
    public static void requireWindow(LocalDate from,LocalDate to){
        if(from==null||to==null||from.getDayOfMonth()!=1||to.getDayOfMonth()!=1||to.isBefore(from)||ChronoUnit.MONTHS.between(YearMonth.from(from),YearMonth.from(to))>=12)throw new IllegalArgumentException("Explicit first-day inclusive D104 window of at most twelve months required");new MacroCoreMonthlyKey(YearMonth.from(from));new MacroCoreMonthlyKey(YearMonth.from(to));
    }
    public static void requireClosedWindow(LocalDate from,LocalDate to,LocalDate logicalDate){requireWindow(from,to);if(logicalDate==null||!YearMonth.from(to).isBefore(YearMonth.from(logicalDate)))throw new IllegalArgumentException("D104 requires completed observation months preceding logical month");}
    public static void requireIncrementalCoverage(List<MacroCoreMonthly> rows,LocalDate from,LocalDate to){
        requireWindow(from,to);long count=ChronoUnit.MONTHS.between(YearMonth.from(from),YearMonth.from(to))+1;if(rows.size()!=count)throw new IllegalStateException("D104 incremental prefix has withheld/missing monthly observations");var month=YearMonth.from(from);var mapper=new MacroCoreMonthlyMapper();
        for(var row:rows){if(!row.month().equals(month))throw new IllegalStateException("D104 incremental source months are not contiguous");var values=mapper.values(row).asMap();if(REQUIRED_MONTHLY_FIELDS.stream().anyMatch(f->values.get(f)==null))throw new IllegalStateException("D104 incremental prefix requires all six monthly components");month=month.plusMonths(1);}
    }
    static String hash(String text){try{return FileEvidenceStore.sha256(text.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private static long deadline(){return System.nanoTime()+Duration.ofSeconds(20).toNanos();}
    private <T>T query(String sql,int cap,long until,ResultSetExtractor<T> extractor){long remaining=until-System.nanoTime();if(remaining<=0)throw new IllegalStateException("D104 complete source read exceeded twenty seconds");T result=jdbc.query(connection->{var statement=connection.prepareStatement(sql);statement.setQueryTimeout((int)Math.max(1,(remaining+999_999_999L)/1_000_000_000L));statement.setMaxRows(cap);return statement;},extractor);if(System.nanoTime()>until)throw new IllegalStateException("D104 source deadline expired");return result;}
    private static Long nullableCounter(ResultSet rs,String field)throws SQLException{if(rs.getObject(field)==null)return null;return counter(rs,field);}
    private static long counter(ResultSet rs,String field)throws SQLException{Object value=rs.getObject(field);if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0)throw new IllegalStateException("Known nonnegative physical/WAL counter required");return ((Number)value).longValue();}
    private static boolean bool(ResultSet rs,String field)throws SQLException{Object value=rs.getObject(field);if(!(value instanceof Boolean flag))throw new IllegalStateException("Known physical/schema boolean required");return flag;}
}
