package com.zoutrankil.data.financial.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.financial.application.FinancialGrowthEvidence;
import com.zoutrankil.data.financial.domain.FinancialGrowthPatch;
import com.zoutrankil.data.financial.domain.FinancialGrowthPatch.Key;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import java.sql.ResultSet;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Growth-only SQL updates preserve every other physical column and existing non-null growth values. */
public final class QuestDbFinancialGrowthRepair implements VerifiedBatchExecutor.Port<FinancialGrowthPatch,Key> {
    public static final List<String> GROWTH=List.of("q_gr_yoy","q_profit_yoy");
    public static final VerifiedBatchExecutor.Codec<FinancialGrowthPatch,Key> CODEC=new VerifiedBatchExecutor.Codec<>() {
        public Key key(FinancialGrowthPatch row){return row.key();}
        public byte[] canonicalBytes(FinancialGrowthPatch row) {
            try{return JobDefinitionJson.canonicalMapper().writeValueAsBytes(row.after());}
            catch(Exception failure){throw new IllegalArgumentException("Cannot encode financial row",failure);}
        }
    };
    public record Plan(List<FinancialGrowthPatch> patches,List<Map<String,Object>> before,
            List<Map<String,Object>> state,Map<String,Integer> skipped,String targetId) {}
    private final JdbcTemplate jdbc;
    private final String frozenTarget;
    private List<Map<String,Object>> columns;
    private boolean senderStopped;
    private boolean writeAttempted;
    public QuestDbFinancialGrowthRepair(JdbcTemplate source,String target) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(90);jdbc.setMaxRows(20001);
        frozenTarget=target;
    }
    public String targetId() {
        var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name='fina_indicator'");
        if(rows.size()!=1)throw new IllegalStateException("Financial target missing");
        return StaticTargetIdentity.identify(jdbc,"fina_indicator",((Number)rows.getFirst().get("id")).longValue(),(String)rows.getFirst().get("directoryName"));
    }
    public List<Map<String,Object>> state() {
        var memory=jdbc.queryForList("SELECT id,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name='fina_indicator'");
        var disk=jdbc.queryForList("SELECT writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name='fina_indicator'");
        if(memory.size()!=1 || disk.size()!=1)throw new IllegalStateException("Financial WAL identity missing");
        var result=new LinkedHashMap<>(memory.getFirst());result.putAll(disk.getFirst());return List.of(result);
    }
    @Override public void preflight() {
        if(!frozenTarget.equals(targetId()))throw new IllegalStateException("Financial physical target changed");
        var schema=jdbc.queryForList("SELECT \"column\",type,designated,upsertKey FROM table_columns('fina_indicator')");
        if(columns==null)columns=List.copyOf(schema);
        if(!columns.equals(schema))throw new IllegalStateException("Financial schema changed");
        for(String name:GROWTH)if(schema.stream().noneMatch(c->name.equals(c.get("column")) && "DOUBLE".equals(c.get("type"))))
            throw new IllegalStateException("DOUBLE growth field required: "+name);
        var keys=new HashSet<String>();schema.stream().filter(c->Boolean.TRUE.equals(c.get("upsertKey"))).forEach(c->keys.add((String)c.get("column")));
        if(!keys.equals(Set.of("ts_code","ann_date","end_date")) || schema.stream().noneMatch(c->"ann_date".equals(c.get("column"))
                && "TIMESTAMP".equals(c.get("type")) && Boolean.TRUE.equals(c.get("designated"))))throw new IllegalStateException("Financial full key or timestamp differs");
    }
    @Override public boolean walSettled(){return QuestDbWriteChecks.walSettled(jdbc,"fina_indicator");}
    @Override public boolean uncertainSenderStopped(){return senderStopped;}
    public boolean writeAttempted(){return writeAttempted;}
    public List<Map<String,Object>> readPeriod(String period) {
        FinancialGrowthEvidence.date(period);preflight();
        var bounds=jdbc.query("SELECT min(ann_date),max(ann_date) FROM fina_indicator WHERE end_date=?",rs->{
            if(!rs.next() || rs.getTimestamp(1)==null)return List.<String>of();
            return List.of(rs.getTimestamp(1).toLocalDateTime().toString(),rs.getTimestamp(2).toLocalDateTime().toString());
        },period);
        if(bounds.isEmpty())return List.of();
        var rows=jdbc.query("SELECT * FROM fina_indicator WHERE ann_date>=cast(? AS TIMESTAMP) AND ann_date<=cast(? AS TIMESTAMP)"
                +" AND end_date=? ORDER BY ann_date,ts_code LIMIT 20001",(rs,n)->physical(rs),bounds.get(0),bounds.get(1),period);
        if(rows.size()>20000)throw new IllegalStateException("Financial quarter row budget exceeded");
        return List.copyOf(rows);
    }
    public Plan plan(FinancialGrowthEvidence source) {
        preflight();if(!walSettled())throw new IllegalStateException("Financial WAL unsettled");
        var beforeState=state();var rows=readPeriod(source.period());var patches=new ArrayList<FinancialGrowthPatch>();
        var skipped=new TreeMap<String,Integer>();var seen=new HashSet<Key>();
        var fieldNames=new HashSet<String>();columns.forEach(c->fieldNames.add((String)c.get("column")));
        var comparison=FinancialGrowthEvidence.FIELDS.stream().filter(fieldNames::contains)
                .filter(f->!GROWTH.contains(f) && !f.equals("update_flag")).toList();
        if(comparison.size()!=108)throw new IllegalStateException("Expected 108 shared baseline fields");
        for(var before:rows) {
            var key=key(before);if(!seen.add(key))throw new IllegalStateException("Duplicate physical financial key");
            var expected=source.rows().get(key);
            String reason=null;
            if(expected==null)reason="source_missing";
            else for(String field:comparison)if(!equal(before.get(field),expected.get(field))) {reason="other_fields_differ";break;}
            if(reason!=null) {skipped.merge(reason,1,Integer::sum);continue;}
            var after=new LinkedHashMap<>(before);boolean changed=false,conflict=false;
            for(String field:GROWTH) {
                var value=expected.get(field);Object old=before.get(field);
                if(old==null && !value.isNull()) {after.put(field,value.doubleValue());changed=true;}
                else if(old!=null && !value.isNull() && !equal(old,value))conflict=true;
            }
            if(conflict)skipped.merge("existing_growth_differs",1,Integer::sum);
            else if(changed)patches.add(new FinancialGrowthPatch(key,before,after));
            else skipped.merge("no_missing_value_available",1,Integer::sum);
        }
        if(!beforeState.equals(state()) || !walSettled())throw new IllegalStateException("Financial source changed during planning");
        return new Plan(List.copyOf(patches),rows,beforeState,Map.copyOf(skipped),frozenTarget);
    }
    @Override public void send(List<FinancialGrowthPatch> patches) throws Exception {
        if(patches.isEmpty() || patches.size()>64)throw new IllegalArgumentException("At most 64 financial patches per batch");
        preflight();senderStopped=false;
        var current=readback(patches.stream().map(FinancialGrowthPatch::key).toList());
        var found=new HashMap<Key,Map<String,Object>>();
        for(var row:current)if(found.put(row.key(),row.after())!=null)throw new IllegalStateException("Duplicate financial key");
        for(var patch:patches)if(!patch.before().equals(found.get(patch.key())) && !patch.after().equals(found.get(patch.key())))
            throw new IllegalStateException("Financial baseline changed before update");
        var groups=new LinkedHashMap<List<String>,List<FinancialGrowthPatch>>();
        for(var patch:patches)groups.computeIfAbsent(List.of(patch.key().announcement().toString(),patch.key().period()),ignored->new ArrayList<>()).add(patch);
        for(var group:groups.values()) {
            var update=updateStatement(group);
            writeAttempted=true;
            jdbc.update(update.sql(),update.parameters().toArray());
        }
        senderStopped=true;
    }
    public record Update(String sql,List<Object> parameters) {}
    /** One exact announcement/report group; all values remain bound parameters. */
    public static Update updateStatement(List<FinancialGrowthPatch> group) {
            if(group.isEmpty() || group.size()>64 || group.stream().map(FinancialGrowthPatch::key).distinct().count()!=group.size()
                    || group.stream().anyMatch(p->!p.key().announcement().equals(group.getFirst().key().announcement())
                    || !p.key().period().equals(group.getFirst().key().period())))throw new IllegalArgumentException("One unique bounded date/report group required");
            var params=new ArrayList<Object>();var assignments=new ArrayList<String>();
            for(String field:GROWTH) {
                var cases=new StringBuilder("CASE ");
                for(var patch:group) {cases.append("WHEN ts_code = ? THEN cast(? AS DOUBLE) ");params.add(patch.key().symbol());params.add(patch.after().get(field));}
                cases.append("ELSE ").append(field).append(" END");
                assignments.add(field+" = CASE WHEN "+field+" IS NULL THEN "+cases+" ELSE "+field+" END");
            }
            params.add(group.getFirst().key().announcement().toString());params.add(group.getFirst().key().period());
            group.forEach(p->params.add(p.key().symbol()));
            return new Update("UPDATE fina_indicator SET "+String.join(",",assignments)
                    +" WHERE ann_date=cast(? AS TIMESTAMP) AND end_date=? AND ts_code IN ("
                    +String.join(",",Collections.nCopies(group.size(),"?"))+")",Collections.unmodifiableList(params));
    }
    @Override public List<FinancialGrowthPatch> readback(List<Key> keys) {
        if(keys.isEmpty() || keys.size()>250 || new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("Bounded unique financial keys required");
        preflight();var clauses=new ArrayList<String>();var params=new ArrayList<Object>();
        var first=keys.stream().map(Key::announcement).min(Comparator.naturalOrder()).orElseThrow();
        var last=keys.stream().map(Key::announcement).max(Comparator.naturalOrder()).orElseThrow();
        params.add(first.toString());params.add(last.plusDays(1).toString());
        var codes=keys.stream().map(Key::symbol).distinct().toList();params.addAll(codes);
        for(var key:keys) {clauses.add("(ts_code=? AND ann_date=cast(? AS TIMESTAMP) AND end_date=?)");params.add(key.symbol());params.add(key.announcement().toString());params.add(key.period());}
        return jdbc.query("SELECT * FROM fina_indicator WHERE ann_date>=cast(? AS TIMESTAMP) AND ann_date<cast(? AS TIMESTAMP) AND ts_code IN ("
                +String.join(",",Collections.nCopies(codes.size(),"?"))+") AND ("+String.join(" OR ",clauses)+") LIMIT "+(keys.size()+1),
                (rs,n)->{var row=physical(rs);return new FinancialGrowthPatch(key(row),row,row);},params.toArray());
    }
    public static Key key(Map<String,Object> row) {return new Key((String)row.get("ts_code"),FinancialGrowthEvidence.date((String)row.get("ann_date")),(String)row.get("end_date"));}
    private static Map<String,Object> physical(ResultSet rs)throws java.sql.SQLException {
        var row=new LinkedHashMap<String,Object>();var metadata=rs.getMetaData();
        for(int i=1;i<=metadata.getColumnCount();i++) {
            String name=metadata.getColumnLabel(i);Object value=rs.getObject(i);
            if(value instanceof java.sql.Timestamp stamp) {
                var local=stamp.toLocalDateTime();
                if(name.equals("ann_date")) {if(!local.toLocalTime().equals(LocalTime.MIDNIGHT))throw new IllegalStateException("Non-midnight financial announcement");value=local.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);}
                else value=local.toString();
            }
            if(value instanceof Double number && !Double.isFinite(number))value=null;
            row.put(name,value);
        }
        return Collections.unmodifiableMap(row);
    }
    private static boolean equal(Object actual,com.fasterxml.jackson.databind.JsonNode expected) {
        if(expected==null)return false;
        if(expected.isNull())return actual==null;
        if(expected.isNumber())return actual instanceof Number n && Double.compare(n.doubleValue(),expected.doubleValue())==0;
        return expected.isTextual() && Objects.equals(actual,expected.textValue());
    }
}
