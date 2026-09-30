package com.zoutrankil.questdbwithdata.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent D031 verifier: parses source evidence itself and selects all six physical columns directly. */
public final class MarginZrzIndependentReadback {
    private static final int MAX_ROWS=100_000,MAX_RECEIPT_BYTES=4*1024*1024,MAX_CHILDREN=500,MAX_EXAMPLES=50;
    private static final int SOURCE_CAP=5000;
    private static final List<String> FIELDS=List.of("trade_date","ob","auc_amount","repo_amount","repay_amount","cb");
    private static final List<String> METRICS=List.of("ob","auc_amount","repo_amount","repay_amount","cb");
    private static final DateTimeFormatter BASIC=DateTimeFormatter.BASIC_ISO_DATE;
    private MarginZrzIndependentReadback(){}

    public static Map<String,Object> verify(JdbcTemplate jdbc,Path ledgerPath,String table,String runId)throws Exception {
        Objects.requireNonNull(jdbc);Objects.requireNonNull(ledgerPath);Objects.requireNonNull(runId);
        if(table==null||!table.matches("java_d031_margin_zrz_[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Explicit D031 isolated table required");
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);var terminal=ledger.get(runId);
        if(!"data.margin_zrz".equals(run.jobId())||run.jobVersion()!=1||terminal.state()!=SyncRunState.VERIFIED)
            throw new IllegalStateException("D031 independent readback requires a nonempty terminal verified D031 run");
        var json=JobDefinitionJson.mapper();JsonNode frozen=json.readTree(run.frozenJson());
        SyncJobDefinition definition=json.treeToValue(frozen.path("definition"),SyncJobDefinition.class);
        if(!definition.equals(com.zoutrankil.questdbwithdata.service.MarginZrzSyncJobOwner.DEFINITION)
                ||!run.targetId().equals(frozen.path("parameters").path("targetId").asText()))
            throw new IllegalStateException("D031 frozen job or logical target differs");
        LocalDate from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());
        if(from.isAfter(to)||ChronoUnit.DAYS.between(from,to)+1>366
                ||!"INCREMENTAL".equals(frozen.path("mode").asText())&&!Set.of("BACKFILL","RECONCILE").contains(frozen.path("mode").asText()))
                ||to.isAfter(LocalDate.parse(frozen.path("logicalDate").asText())))
            throw new IllegalStateException("D031 frozen readback range/mode invalid");
        Path evidenceRoot=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).normalize().toRealPath();
        var entries=ledger.entries(runId,null,MAX_CHILDREN);if(entries.size()>=MAX_CHILDREN)throw new IllegalStateException("D031 run exceeds independent verification child bound");
        var slices=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        if(slices.size()!=1||slices.getFirst().state()!=SyncRunState.VERIFIED)
            throw new IllegalStateException("D031 verified run must contain exactly one nonempty verified range slice");
        var slice=slices.getFirst();var fetched=ledger.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();
        if(fetched.size()!=1)throw new IllegalStateException("D031 range slice must have exactly one FETCHED event");
        JsonNode event=json.readTree(fetched.getFirst().payloadJson());String cursor=required(event,"cursor");
        String expectedCursor=from.format(BASIC)+".."+to.format(BASIC);
        if(!expectedCursor.equals(cursor))throw new IllegalStateException("D031 source cursor differs from frozen range");
        String fingerprint=required(event,"sourceFingerprint"),rawPath=required(event,"responseEvidence");
        if(!fingerprint.matches("[0-9a-f]{64}"))throw new IllegalStateException("D031 source SHA invalid");
        Path receipt=Path.of(rawPath).toAbsolutePath().normalize();
        if(!receipt.startsWith(evidenceRoot)||Files.isSymbolicLink(receipt)||!Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(receipt)<1||Files.size(receipt)>MAX_RECEIPT_BYTES)
            throw new IllegalStateException("D031 receipt path invalid or oversized");
        byte[] bytes=Files.readAllBytes(receipt);if(!sha(bytes).equals(fingerprint))throw new IllegalStateException("D031 raw source receipt SHA mismatch");
        JsonNode body=json.readTree(bytes);String first=from.format(BASIC),last=to.format(BASIC);
        if(!"tushare".equals(body.path("sourceKind").asText())||!"slb_len".equals(body.path("endpoint").asText())
                ||body.path("sourceContractVersion").asInt(-1)!=1||!body.path("sourceComplete").asBoolean(false)
                ||!from.toString().equals(body.path("fromInclusive").asText())||!to.toString().equals(body.path("toInclusive").asText())
                ||body.path("apiMaximumRows").asInt(-1)!=SOURCE_CAP||!json.valueToTree(FIELDS).equals(body.path("fields"))
                ||!first.equals(body.path("parameters").path("start_date").asText())
                ||!last.equals(body.path("parameters").path("end_date").asText())||!body.path("rawRows").isArray()
                ||body.path("rawRows").size()<1||body.path("rawRows").size()>=SOURCE_CAP
                ||body.path("returnedRows").asInt(-1)!=body.path("rawRows").size()
                ||body.path("returnedRows").asInt(-1)!=event.path("returnedRows").asInt(-2))
            throw new IllegalStateException("D031 receipt differs from frozen slb_len range/field/cap evidence");
        List<Map<String,JsonNode>> raw=json.convertValue(body.path("rawRows"),new TypeReference<>(){});
        var expected=new TreeMap<LocalDate,Map<String,Double>>();String prior="";
        for(var row:raw) {
            if(!row.keySet().equals(new HashSet<>(FIELDS)))throw new IllegalStateException("D031 raw source row has unexpected fields");
            String date=text(row,"trade_date");if(!date.matches("[0-9]{8}")||date.compareTo(prior)<=0)
                throw new IllegalStateException("D031 raw source dates are invalid, duplicate, or noncanonical");prior=date;
            LocalDate day=LocalDate.parse(date,BASIC);if(day.isBefore(from)||day.isAfter(to))throw new IllegalStateException("D031 raw source date outside frozen range");
            var values=new LinkedHashMap<String,Double>();
            for(String metric:METRICS){JsonNode value=row.get(metric);if(value==null)throw new IllegalStateException("D031 source field missing: "+metric);
                if(value.isNull()){values.put(metric,null);continue;}
                if(!(value.isNumber()||value.isTextual()))throw new IllegalStateException("D031 source numeric field invalid: "+metric);
                double number=new BigDecimal(value.asText().strip()).doubleValue();if(!Double.isFinite(number))throw new IllegalStateException("D031 source numeric value is nonfinite: "+metric);
                values.put(metric,number);}
            if(expected.putIfAbsent(day,Collections.unmodifiableMap(values))!=null)throw new IllegalStateException("D031 duplicate natural trade_date");
        }
        var jdbcRead=new JdbcTemplate(jdbc.getDataSource());jdbcRead.setQueryTimeout(60);jdbcRead.setMaxRows(MAX_ROWS+1);
        long lower=new com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp(from)
                .storageEpoch(com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.MICROS);
        long upper=new com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp(to.plusDays(1))
                .storageEpoch(com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.MICROS);
        String sql="SELECT cast(trade_date AS long) AS trade_micros,ob,auc_amount,repo_amount,repay_amount,cb FROM \""+table
                +"\" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT "+(MAX_ROWS+1);
        var physical=jdbcRead.query(sql,rs->{var rows=new ArrayList<Map.Entry<LocalDate,Map<String,Double>>>();while(rs.next()){
            long micros=rs.getLong("trade_micros"),perDay=86_400_000_000L;
            if(Math.floorMod(micros,perDay)!=0)throw new SQLException("D031 physical timestamp is not UTC-midnight business date");
            LocalDate day=LocalDate.ofEpochDay(Math.floorDiv(micros,perDay));var values=new LinkedHashMap<String,Double>();
            for(String metric:METRICS){Object value=rs.getObject(metric);if(value==null){values.put(metric,null);continue;}
                if(value instanceof Number number&&Double.isFinite(number.doubleValue()))values.put(metric,number.doubleValue());
                else throw new SQLException("D031 physical numeric field invalid: "+metric);}
            rows.add(new java.util.AbstractMap.SimpleImmutableEntry<>(day,Collections.unmodifiableMap(values)));}return rows;},lower,upper);
        if(physical.size()>MAX_ROWS)throw new IllegalStateException("D031 physical window exceeds independent readback cap");
        var actual=new TreeMap<LocalDate,Map<String,Double>>();for(var row:physical)
            if(actual.putIfAbsent(row.getKey(),row.getValue())!=null)throw new IllegalStateException("D031 physical duplicate natural trade_date");
        int matched=0,missing=0,extra=0,mismatched=0;var examples=new ArrayList<String>();
        for(var entry:expected.entrySet()){var found=actual.get(entry.getKey());if(found==null){missing++;add(examples,"missing:"+entry.getKey());continue;}
            if(equal(entry.getValue(),found))matched++;else{mismatched++;add(examples,"values:"+entry.getKey());}}
        for(var day:actual.keySet())if(!expected.containsKey(day)){extra++;add(examples,"extra:"+day);}
        boolean pass=missing==0&&extra==0&&mismatched==0&&matched==expected.size()&&expected.size()==actual.size();
        var out=new LinkedHashMap<String,Object>();out.put("status",pass?"MATCHED":"MISMATCHED");out.put("dataset","margin_zrz");out.put("runId",runId);
        out.put("targetTable",table);out.put("targetId",run.targetId());out.put("mode",frozen.path("mode").asText());
        out.put("fromInclusive",from.toString());out.put("toInclusive",to.toString());out.put("sourceSlices",1);
        out.put("receiptFingerprints",List.of(fingerprint));out.put("expectedRows",expected.size());out.put("actualRows",actual.size());
        out.put("matchedRows",matched);out.put("missingKeys",missing);out.put("extraKeys",extra);out.put("mismatchedKeys",mismatched);
        out.put("examples",List.copyOf(examples));return Collections.unmodifiableMap(out);
    }
    private static boolean equal(Map<String,Double> expected,Map<String,Double> actual){
        for(String field:METRICS){Double a=expected.get(field),b=actual.get(field);if(a==null?b!=null:b==null||Double.doubleToLongBits(a)!=Double.doubleToLongBits(b))return false;}return true;
    }
    private static void add(List<String> values,String item){if(values.size()<MAX_EXAMPLES)values.add(item);}
    private static String required(JsonNode node,String field){JsonNode v=node.path(field);if(!v.isTextual()||v.asText().isBlank())throw new IllegalStateException("D031 ledger event lacks "+field);return v.asText();}
    private static String text(Map<String,JsonNode> row,String field){JsonNode v=row.get(field);return v==null||v.isNull()?"":v.asText();}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
