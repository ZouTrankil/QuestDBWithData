package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;
import java.lang.reflect.*;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;

/** Shared immutable row metadata; SQL identifiers come only from the generated record. */
public final class DailyMetricStorageMapping {
    private DailyMetricStorageMapping() {}
    private static final RecordComponent[] FIELDS=FactorDailyMetricRow.class.getRecordComponents();
    public static final List<String> COLUMNS=Arrays.stream(FIELDS).map(f->column(f.getName())).toList();
    public static Map<String,String> types() {
        var result=new LinkedHashMap<String,String>();
        for(int i=0;i<FIELDS.length;i++) {
            Class<?> type=FIELDS[i].getType();
            result.put(COLUMNS.get(i),type==Instant.class?"TIMESTAMP":type==String.class?"SYMBOL":
                    type==Double.class?"DOUBLE":type==Integer.class?"INT":"BOOLEAN");
        }
        return Collections.unmodifiableMap(result);
    }
    public static final Set<String> KEYS=Set.of("trade_date","universe_id","universe_version","factor_id",
            "definition_version","label_definition","horizon","evaluation_profile");
    private static final Constructor<FactorDailyMetricRow> CONSTRUCTOR=constructor();
    private static Constructor<FactorDailyMetricRow> constructor() {
        try { return FactorDailyMetricRow.class.getDeclaredConstructor(Arrays.stream(FIELDS).map(RecordComponent::getType).toArray(Class<?>[]::new)); }
        catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
    }
    private static String column(String name) {
        if(name.equals("pureModelRSquared"))return "pure_model_r_squared";
        if(name.equals("l2SourceLineageDigest"))return "l2_source_lineage_digest";
        return name.replaceAll("([a-z0-9])([A-Z])","$1_$2").replaceAll("([a-z])([0-9])","$1_$2").toLowerCase(Locale.ROOT);
    }
    public static Map<String,Object> values(FactorDailyMetricRow row) {
        var result=new LinkedHashMap<String,Object>();
        for(int i=0;i<FIELDS.length;i++)try {
            Object value=FIELDS[i].getAccessor().invoke(row);
            if(value instanceof Double number && !Double.isFinite(number))throw new IllegalArgumentException("Nonfinite metric value: "+COLUMNS.get(i));
            result.put(COLUMNS.get(i),value);
        }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
        return Collections.unmodifiableMap(result);
    }
    public static String projection() {
        var expressions=new ArrayList<String>();
        for(int i=0;i<FIELDS.length;i++) {
            String name=COLUMNS.get(i);
            expressions.add(FIELDS[i].getType()==Instant.class?"cast(\""+name+"\" AS LONG) AS \""+name+"\"":"\""+name+"\"");
        }
        return String.join(",",expressions);
    }
    public static FactorDailyMetricRow read(ResultSet rs) throws java.sql.SQLException {
        Object[] values=new Object[FIELDS.length];
        for(int i=0;i<FIELDS.length;i++) {
            String name=COLUMNS.get(i);Object value=rs.getObject(name);Class<?> type=FIELDS[i].getType();
            if(value==null)continue;
            if(type==Instant.class) {
                long micros=((Number)value).longValue();
                values[i]=Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000),Math.floorMod(micros,1_000_000)*1000);
            }else if(type==Double.class) {
                double number=((Number)value).doubleValue();
                if(Double.isInfinite(number))throw new java.sql.SQLException("Infinite stored metric: "+name);
                values[i]=Double.isNaN(number)?null:number;
            }else if(type==Integer.class)values[i]=((Number)value).intValue();
            else if(type==Boolean.class)values[i]=rs.getBoolean(name);
            else values[i]=rs.getString(name);
        }
        // QuestDB BOOLEAN has no null representation. Canonical unknown wins are
        // determined by the corresponding unavailable IC, never interpreted as a loss.
        if(values[COLUMNS.indexOf("rank_ic")]==null)values[COLUMNS.indexOf("win")]=null;
        if(values[COLUMNS.indexOf("pure_rank_ic")]==null)values[COLUMNS.indexOf("pure_win")]=null;
        try { return CONSTRUCTOR.newInstance(values); }
        catch(ReflectiveOperationException failure){throw new java.sql.SQLException("Invalid metric row",failure);}
    }
}
