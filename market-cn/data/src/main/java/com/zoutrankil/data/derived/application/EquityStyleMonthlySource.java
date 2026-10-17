package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.derived.domain.EquityStyleMonthlySourceData;
import com.zoutrankil.data.derived.domain.EquityStyleMonthlySourceData.*;
import static com.zoutrankil.data.derived.domain.EquityStyleMonthlySourceData.*;
import com.zoutrankil.data.derived.port.EquityStyleMonthlySourceReadPort;


import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** D103 bounded full-field source census and legacy last-nonnull monthly pivot. */
public class EquityStyleMonthlySource {
    public static final int MAX_MONTHS=EquityStyleMonthlySourceData.MAX_MONTHS, MAX_SOURCE_ROWS=EquityStyleMonthlySourceData.MAX_SOURCE_ROWS;
    public static final List<String> SOURCE_FIELDS=EquityStyleMonthlySourceData.SOURCE_FIELDS;
    public static final Map<String,String> FEATURE_CODES=EquityStyleMonthlySourceData.FEATURE_CODES;
    private final EquityStyleMonthlySourceReadPort reads;
    public EquityStyleMonthlySource(EquityStyleMonthlySourceReadPort reads){this.reads=Objects.requireNonNull(reads);}

    public String table(){return reads.table();}
    public Snapshot snapshot(){return reads.snapshot();}
    public Batch read(LocalDate from,LocalDate to) {
        requireWindow(from,to);Snapshot before=snapshot();
        var rows=reads.readWindow(from,to);
        var result=derive(rows,from,to);
        Snapshot after=snapshot();if(!before.equals(after))throw new IllegalStateException("D103 source changed during complete bounded read");
        return new Batch(after,rawFingerprint(rows),rows.size(),result);
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



}
