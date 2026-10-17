package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData;
import com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData.*;
import static com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData.*;
import com.zoutrankil.data.derived.port.MacroCoreMonthlySourceReadPort;


import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyMapper;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** D104 bounded six-source monthly join; source observations retain their stored units. */
public class MacroCoreMonthlySource {
    public static final int MAX_MONTHS=MacroCoreMonthlySourceData.MAX_MONTHS, MAX_SOURCE_ROWS=MacroCoreMonthlySourceData.MAX_SOURCE_ROWS;
    public static final List<String> SOURCE_TABLES=MacroCoreMonthlySourceData.SOURCE_TABLES;
    public static final List<String> REQUIRED_MONTHLY_FIELDS=MacroCoreMonthlySourceData.REQUIRED_MONTHLY_FIELDS;
    public static final Map<String,SourceSpec> SOURCE_SPECS=MacroCoreMonthlySourceData.SOURCE_SPECS;
    private final MacroCoreMonthlySourceReadPort reads;
    public MacroCoreMonthlySource(MacroCoreMonthlySourceReadPort reads){this.reads=Objects.requireNonNull(reads);}

    public Snapshot snapshot(){return reads.snapshot(deadline());}



    public Batch read(LocalDate from,LocalDate to){
        requireWindow(from,to);long until=deadline();var before=reads.snapshot(until);var window=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var spec:SOURCE_SPECS.values()){var rows=reads.readWindow(spec.table(),from,to,until);if(rows.size()>12)throw new IllegalStateException("D104 monthly source sentinel reached; complete window unknown");window.put(spec.table(),rows);}
        var context=new ArrayList<>(reads.readPrecedingSocialFinancingDescending(from,until));Collections.reverse(context);
        var derived=derive(window,context,from,to);int rawRows=context.size()+window.values().stream().mapToInt(List::size).sum();if(rawRows>MAX_SOURCE_ROWS)throw new IllegalStateException("D104 aggregate raw source budget exceeded");
        String raw=rawFingerprint(window,context);var after=reads.snapshot(until);if(!before.equals(after))throw new IllegalStateException("D104 six-source physical vector changed during bounded read");
        var counts=new LinkedHashMap<String,Integer>();window.forEach((table,rows)->counts.put(table,rows.size()));return new Batch(after,raw,rawRows,derived.rows(),derived.candidateMonths(),derived.withheldMonths(),context.size(),counts);
    }





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




}
