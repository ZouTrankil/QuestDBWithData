package com.zoutrankil.questdbwithdata.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent D028 verifier: parses ledger/raw receipts itself and selects the nine physical columns directly. */
public final class MarginAllIndependentReadback {
    private static final int MAX_ROWS=100_000,MAX_RECEIPT_BYTES=4*1024*1024,MAX_CHILDREN=500,MAX_EXAMPLES=50;
    private static final List<String> FIELDS=List.of("trade_date","exchange_id","rzye","rzmre","rzche","rqye","rqmcl","rzrqye","rqyl");
    private static final List<String> METRICS=List.of("rzye","rzmre","rzche","rqye","rqmcl","rzrqye","rqyl");
    private static final DateTimeFormatter BASIC=DateTimeFormatter.BASIC_ISO_DATE;
    private MarginAllIndependentReadback(){}
    private record Key(LocalDate date,String exchange) implements Comparable<Key>{public int compareTo(Key other){int d=date.compareTo(other.date);return d!=0?d:exchange.compareTo(other.exchange);}}

    public static Map<String,Object> verify(JdbcTemplate jdbc,Path ledgerPath,String table,String runId)throws Exception{
        Objects.requireNonNull(jdbc);Objects.requireNonNull(ledgerPath);Objects.requireNonNull(runId);
        if(table==null||!table.matches("java_d028_margin_all_[A-Za-z0-9_]+"))throw new IllegalArgumentException("Explicit D028 isolated table required");
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);var terminal=ledger.get(runId);
        if(!"data.margin_all".equals(run.jobId())||run.jobVersion()!=1||!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(terminal.state()))
            throw new IllegalStateException("D028 independent readback requires a terminal verified D028 run");
        var json=JobDefinitionJson.mapper();JsonNode frozen=json.readTree(run.frozenJson());
        SyncJobDefinition definition=json.treeToValue(frozen.path("definition"),SyncJobDefinition.class);
        if(!definition.equals(com.zoutrankil.questdbwithdata.service.MarginAllSyncJobOwner.DEFINITION)
                ||!run.targetId().equals(frozen.path("parameters").path("targetId").asText()))throw new IllegalStateException("D028 frozen job or logical target differs");
        LocalDate from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());
        if(from.isAfter(to)||java.time.temporal.ChronoUnit.DAYS.between(from,to)+1>366)throw new IllegalStateException("D028 frozen readback range invalid");
        Path evidenceRoot=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).normalize().toRealPath();
        var entries=ledger.entries(runId,null,MAX_CHILDREN);if(entries.size()>=MAX_CHILDREN)throw new IllegalStateException("D028 run exceeds independent verification child bound");
        var slices=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        if(slices.size()!=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1)throw new IllegalStateException("D028 source slices do not cover every frozen calendar date");
        var expected=new TreeMap<Key,Map<String,Double>>();var fingerprints=new ArrayList<String>();LocalDate next=from;
        for(var slice:slices){
            if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(slice.state()))throw new IllegalStateException("D028 nonterminal source slice in verified run");
            var fetched=ledger.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();
            if(fetched.size()!=1)throw new IllegalStateException("D028 slice must have exactly one FETCHED event");
            JsonNode event=json.readTree(fetched.getFirst().payloadJson());String cursor=required(event,"cursor");
            if(!cursor.matches("[0-9]{8}"))throw new IllegalStateException("D028 slice cursor is not one date");LocalDate day=LocalDate.parse(cursor,BASIC);
            if(!day.equals(next)||day.isAfter(to))throw new IllegalStateException("D028 date slices are not contiguous and ordered");next=next.plusDays(1);
            String fp=required(event,"sourceFingerprint"),rawPath=required(event,"responseEvidence");if(!fp.matches("[0-9a-f]{64}"))throw new IllegalStateException("D028 source SHA invalid");
            Path receipt=Path.of(rawPath).toAbsolutePath().normalize();if(!receipt.startsWith(evidenceRoot)||Files.isSymbolicLink(receipt)||!Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS)||Files.size(receipt)<1||Files.size(receipt)>MAX_RECEIPT_BYTES)throw new IllegalStateException("D028 receipt path invalid/oversized");
            byte[] bytes=Files.readAllBytes(receipt);if(!sha(bytes).equals(fp))throw new IllegalStateException("D028 raw source receipt SHA mismatch");JsonNode body=json.readTree(bytes);
            if(!"tushare".equals(body.path("sourceKind").asText())||!"margin".equals(body.path("endpoint").asText())||!body.path("sourceComplete").asBoolean(false)
                    ||!day.toString().equals(body.path("tradeDate").asText())||body.path("apiMaximumRows").asInt(-1)!=4000
                    ||!json.valueToTree(FIELDS).equals(body.path("fields"))||!day.format(BASIC).equals(body.path("parameters").path("trade_date").asText())
                    ||!body.path("rawRows").isArray()||body.path("rawRows").size()>=4000||body.path("returnedRows").asInt(-1)!=body.path("rawRows").size()
                    ||body.path("returnedRows").asInt(-1)!=event.path("returnedRows").asInt(-2)
                    ||(body.path("returnedRows").asInt()==0)!=(slice.state()==SyncRunState.VERIFIED_EMPTY))
                throw new IllegalStateException("D028 receipt differs from frozen endpoint/date/cap/slice evidence");
            List<Map<String,JsonNode>> raw=json.convertValue(body.path("rawRows"),new TypeReference<>(){});String prior="";
            for(var row:raw){if(!row.keySet().equals(new HashSet<>(FIELDS))||!day.format(BASIC).equals(text(row,"trade_date")))throw new IllegalStateException("D028 raw row fields/date invalid");
                String exchange=text(row,"exchange_id");if(!Set.of("SSE","SZSE","BSE").contains(exchange)||exchange.compareTo(prior)<=0)throw new IllegalStateException("D028 exchange key invalid or noncanonical");prior=exchange;
                var values=new LinkedHashMap<String,Double>();for(String metric:METRICS){JsonNode value=row.get(metric);if(value==null||value.isNull()||!(value.isNumber()||value.isTextual()))throw new IllegalStateException("D028 metric missing: "+metric);double n=new BigDecimal(value.asText().strip()).doubleValue();if(!Double.isFinite(n))throw new IllegalStateException("D028 metric nonfinite: "+metric);values.put(metric,n);}
                if(expected.putIfAbsent(new Key(day,exchange),Map.copyOf(values))!=null)throw new IllegalStateException("D028 duplicate source natural key");}
            fingerprints.add(fp);
        }
        if(!next.equals(to.plusDays(1)))throw new IllegalStateException("D028 source receipt window incomplete");
        var jdbcRead=new JdbcTemplate(jdbc.getDataSource());jdbcRead.setQueryTimeout(60);jdbcRead.setMaxRows(MAX_ROWS+1);
        long lower=new com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp(from).storageEpoch(com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.MICROS);
        long upper=new com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp(to.plusDays(1)).storageEpoch(com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.MICROS);
        String sql="SELECT cast(trade_date AS long) AS trade_micros,exchange_id,rzye,rzmre,rzche,rqye,rqmcl,rzrqye,rqyl FROM \""+table+"\" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,exchange_id LIMIT "+(MAX_ROWS+1);
        var physical=jdbcRead.query(sql,rs->{var rows=new ArrayList<Map.Entry<Key,Map<String,Double>>>();while(rs.next()){
                long micros=rs.getLong("trade_micros");long perDay=86_400_000_000L;if(Math.floorMod(micros,perDay)!=0)throw new SQLException("D028 physical timestamp is not UTC-midnight business date");LocalDate day=LocalDate.ofEpochDay(Math.floorDiv(micros,perDay));String exchange=rs.getString("exchange_id");if(!Set.of("SSE","SZSE","BSE").contains(exchange))throw new SQLException("D028 physical exchange invalid");
                var values=new LinkedHashMap<String,Double>();for(String metric:METRICS){Object raw=rs.getObject(metric);if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new SQLException("D028 physical numeric field invalid: "+metric);values.put(metric,n.doubleValue());}rows.add(Map.entry(new Key(day,exchange),Map.copyOf(values)));}return rows;},lower,upper);
        if(physical.size()>MAX_ROWS)throw new IllegalStateException("D028 physical window exceeds independent readback cap");
        var actual=new TreeMap<Key,Map<String,Double>>();for(var row:physical)if(actual.putIfAbsent(row.getKey(),row.getValue())!=null)throw new IllegalStateException("D028 physical duplicate natural key");
        int matched=0,missing=0,extra=0,mismatched=0;var examples=new ArrayList<String>();
        for(var entry:expected.entrySet()){var found=actual.get(entry.getKey());if(found==null){missing++;add(examples,"missing:"+entry.getKey());continue;}if(equal(entry.getValue(),found))matched++;else{mismatched++;add(examples,"values:"+entry.getKey());}}
        for(var key:actual.keySet())if(!expected.containsKey(key)){extra++;add(examples,"extra:"+key);}
        boolean pass=missing==0&&extra==0&&mismatched==0&&matched==expected.size()&&expected.size()==actual.size();
        var out=new LinkedHashMap<String,Object>();out.put("status",pass?"MATCHED":"MISMATCHED");out.put("dataset","margin_all");out.put("runId",runId);out.put("targetTable",table);out.put("targetId",run.targetId());out.put("mode",frozen.path("mode").asText());out.put("fromInclusive",from.toString());out.put("toInclusive",to.toString());out.put("sourceSlices",slices.size());out.put("receiptFingerprints",List.copyOf(fingerprints));out.put("expectedRows",expected.size());out.put("actualRows",actual.size());out.put("matchedRows",matched);out.put("missingKeys",missing);out.put("extraKeys",extra);out.put("mismatchedKeys",mismatched);out.put("examples",List.copyOf(examples));return Collections.unmodifiableMap(out);
    }
    private static boolean equal(Map<String,Double>a,Map<String,Double>b){for(String f:METRICS)if(Double.doubleToLongBits(a.get(f))!=Double.doubleToLongBits(b.get(f)))return false;return true;}
    private static void add(List<String> values,String item){if(values.size()<MAX_EXAMPLES)values.add(item);}
    private static String required(JsonNode node,String field){JsonNode v=node.path(field);if(!v.isTextual()||v.asText().isBlank())throw new IllegalStateException("D028 ledger event lacks "+field);return v.asText();}
    private static String text(Map<String,JsonNode> row,String field){JsonNode v=row.get(field);return v==null||v.isNull()?"":v.asText();}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
