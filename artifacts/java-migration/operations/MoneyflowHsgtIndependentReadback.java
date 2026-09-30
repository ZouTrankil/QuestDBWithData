package com.zoutrankil.questdbwithdata.artifacts.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.ReferencePublicationJournal;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Raw-receipt-to-QuestDB verifier independent of D027's Source, DTO mapper, and write port. */
public final class MoneyflowHsgtIndependentReadback {
    private static final List<String> FIELDS=List.of("trade_date","ggt_ss","ggt_sz","hgt","sgt","north_money","south_money");
    private static final List<String> METRICS=List.of("ggt_ss","ggt_sz","hgt","sgt","north_money","south_money");
    private static final int MAX_SLICES=12,MAX_WINDOW_DAYS=366,MAX_RECEIPT_BYTES=2*1024*1024;
    private MoneyflowHsgtIndependentReadback(){}
    private record Expected(LocalDate date,Map<String,Double> metrics){}
    private record Actual(LocalDate date,Map<String,Double> metrics){}

    public static Map<String,Object> verify(JdbcTemplate jdbc,Path ledgerPath,String table,String runId)throws Exception {
        if(jdbc==null||ledgerPath==null||table==null||!table.matches("java_d027_moneyflow_hsgt_[A-Za-z0-9_]+")
                ||runId==null||!runId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw new IllegalArgumentException("D027 isolated table, ledger, and run ID required");
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);var runState=ledger.get(runId).state();
        if(!"data.moneyflow_hsgt".equals(run.jobId())||run.jobVersion()!=1
                ||!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(runState))
            throw new IllegalStateException("D027 independent readback requires a terminal successful D027 run");
        var journal=new ReferencePublicationJournal(ledgerPath,"moneyflow_hsgt").findForRun(runId);
        if(journal.isEmpty()||journal.get().state()!=ReferencePublicationJournal.State.VERIFIED
                ||!table.equals(journal.get().intent().target()))throw new IllegalStateException("D027 verified publication journal must bind the exact table/run");
        var frozen=new ObjectMapper().readTree(run.frozenJson());LocalDate from=LocalDate.parse(frozen.path("from").asText());
        LocalDate to=LocalDate.parse(frozen.path("to").asText());long days=ChronoUnit.DAYS.between(from,to)+1;
        if(days<1||days>MAX_WINDOW_DAYS||to.isAfter(LocalDate.parse(run.logicalDate())))throw new IllegalStateException("D027 frozen run window is not bounded");
        requirePhysicalSchema(jdbc,table);
        Path evidenceRoot=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).toRealPath();
        var entries=ledger.entries(runId,null,1000);if(entries.size()>=1000)throw new IllegalStateException("D027 ledger entries exceed helper bound");
        var sourceSlices=new ArrayList<Map<String,Object>>();var sourceDates=new HashSet<LocalDate>();
        var slices=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        if(slices.isEmpty()||slices.size()>MAX_SLICES)throw new IllegalStateException("D027 source slices are missing or exceed 12");
        int rawRows=0;LocalDate next=from;
        for(var slice:slices){
            if(slice.state()!=SyncRunState.VERIFIED&&slice.state()!=SyncRunState.VERIFIED_EMPTY)throw new IllegalStateException("D027 slice did not reach verified state");
            var fetched=ledger.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();
            if(fetched.size()!=1)throw new IllegalStateException("D027 slice must have one FETCHED proof");
            JsonNode event=new ObjectMapper().readTree(fetched.getFirst().payloadJson());String cursor=event.path("cursor").asText("");
            String[] bounds=cursor.split("\\.\\.",-1);if(bounds.length!=2)throw new IllegalStateException("D027 slice cursor is not a bounded date range");
            LocalDate sliceFrom=LocalDate.parse(bounds[0],java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            LocalDate sliceTo=LocalDate.parse(bounds[1],java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            if(!sliceFrom.equals(next)||sliceTo.isBefore(sliceFrom)||sliceTo.isAfter(to)||ChronoUnit.DAYS.between(sliceFrom,sliceTo)+1>31)
                throw new IllegalStateException("D027 source slices are not a contiguous <=31-day cover");
            String hash=event.path("sourceFingerprint").asText(""),rawPath=event.path("responseEvidence").asText("");
            Path receipt=Path.of(rawPath).toAbsolutePath().normalize();
            if(!receipt.startsWith(evidenceRoot)||Files.isSymbolicLink(receipt)||!Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS)
                    ||Files.size(receipt)<1||Files.size(receipt)>MAX_RECEIPT_BYTES||!receipt.toRealPath().startsWith(evidenceRoot)
                    ||!sha(Files.readAllBytes(receipt)).equals(hash))
                throw new IllegalStateException("D027 immutable raw receipt path/size/SHA-256 mismatch");
            JsonNode body=new ObjectMapper().readTree(Files.readAllBytes(receipt));
            if(!"tushare".equals(body.path("sourceKind").asText())||!"moneyflow_hsgt".equals(body.path("endpoint").asText())
                    ||!body.path("sourceComplete").asBoolean(false)||body.path("apiMaximumRows").asInt(-1)!=300
                    ||!sliceFrom.toString().equals(body.path("fromInclusive").asText())||!sliceTo.toString().equals(body.path("toInclusive").asText())
                    ||body.path("returnedRows").asInt(-1)!=body.path("rawRows").size()
                    ||!new ObjectMapper().valueToTree(FIELDS).equals(body.path("fields"))
                    ||!sliceFrom.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).equals(body.path("parameters").path("start_date").asText())
                    ||!sliceTo.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).equals(body.path("parameters").path("end_date").asText()))
                throw new IllegalStateException("D027 raw response differs from the frozen endpoint/range/schema contract");
            var pageExpected=new ArrayList<Expected>();
            for(JsonNode raw:body.path("rawRows")){
                if(!fieldNames(raw).equals(new HashSet<>(FIELDS)))throw new IllegalStateException("D027 raw row has missing/extra columns");
                String basic=scalar(raw.get("trade_date"));if(!basic.matches("[0-9]{8}"))throw new IllegalStateException("D027 source date must be YYYYMMDD");
                LocalDate date=LocalDate.parse(basic,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                if(date.isBefore(sliceFrom)||date.isAfter(sliceTo)||!sourceDates.add(date))throw new IllegalStateException("D027 source duplicate/out-of-window business date");
                var metrics=new LinkedHashMap<String,Double>();for(String field:METRICS)metrics.put(field,numberOrNull(raw.get(field)));
                var row=new Expected(date,Collections.unmodifiableMap(new LinkedHashMap<>(metrics)));pageExpected.add(row);
            }
            if(pageExpected.isEmpty()!=(slice.state()==SyncRunState.VERIFIED_EMPTY)||pageExpected.size()!=event.path("returnedRows").asInt(-1))
                throw new IllegalStateException("D027 source rows and ledger slice status/count disagree");
            pageExpected.sort(Comparator.comparing(Expected::date));rawRows=Math.addExact(rawRows,pageExpected.size());
            var actual=readWindow(jdbc,table,sliceFrom,sliceTo);
            compare(pageExpected,actual);
            sourceSlices.add(Map.of("fromInclusive",sliceFrom,"toInclusive",sliceTo,"sourceRows",pageExpected.size(),
                    "matches",pageExpected.size(),"sourceFingerprint",hash,"receipt",receipt.toString()));
            next=sliceTo.plusDays(1);
        }
        if(!next.equals(to.plusDays(1)))
            throw new IllegalStateException("D027 source slices do not exactly cover request");
        if((runState==SyncRunState.VERIFIED_EMPTY)!=(rawRows==0))throw new IllegalStateException("D027 run terminal state disagrees with source total");
        var result=new LinkedHashMap<String,Object>();result.put("status","MATCHED");result.put("task","D027");
        result.put("runId",runId);result.put("table",table);result.put("sourceRows",rawRows);
        result.put("expectedRows",rawRows);result.put("actualRows",rawRows);result.put("matches",rawRows);
        result.put("mismatches",0);result.put("duplicateKeys",0);result.put("missingKeys",0);
        result.put("fieldsCompared",7);result.put("fieldNames",FIELDS);result.put("slices",sourceSlices);
        result.put("journalState",journal.get().state().name());result.put("dedup",false);return Map.copyOf(result);
    }

    private static List<Actual> readWindow(JdbcTemplate jdbc,String table,LocalDate from,LocalDate to){
        long lower=micros(from),upper=micros(to.plusDays(1));int cap=Math.toIntExact(ChronoUnit.DAYS.between(from,to)+1)+1;
        var rows=jdbc.query("SELECT cast(trade_date AS long) AS trade_micros,ggt_ss,ggt_sz,hgt,sgt,north_money,south_money FROM \""+table
                +"\" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT "+cap,(rs,n)->{
            Object rawTimestamp=rs.getObject("trade_micros");if(!(rawTimestamp instanceof Number number))throw new SQLException("D027 required physical trade_date");
            long timestamp=number.longValue();if(Math.floorMod(timestamp,86_400_000_000L)!=0)throw new SQLException("D027 trade_date is not UTC midnight");
            LocalDate date=LocalDate.ofEpochDay(Math.floorDiv(timestamp,86_400_000_000L));
            var metrics=new LinkedHashMap<String,Double>();for(String f:METRICS){Object v=rs.getObject(f);if(v!=null&&(!(v instanceof Number metric)||!Double.isFinite(metric.doubleValue())))throw new SQLException("D027 nonfinite physical metric "+f);metrics.put(f,v==null?null:((Number)v).doubleValue());}
            return new Actual(date,Collections.unmodifiableMap(new LinkedHashMap<>(metrics)));},lower,upper);
        if(rows.size()>ChronoUnit.DAYS.between(from,to)+1)throw new IllegalStateException("D027 QuestDB window has duplicate date rows");return rows;
    }
    private static void compare(List<Expected> expected,List<Actual> actual){
        if(expected.size()!=actual.size())throw new IllegalStateException("D027 source/QuestDB row count mismatch");
        var map=new HashMap<LocalDate,Expected>();for(var row:expected)if(map.putIfAbsent(row.date(),row)!=null)throw new IllegalStateException("D027 source duplicate key");
        var seen=new HashSet<LocalDate>();for(var row:actual){Expected want=map.get(row.date());if(want==null||!seen.add(row.date()))throw new IllegalStateException("D027 unexpected/duplicate QuestDB date");
            for(String field:METRICS)if(!same(want.metrics().get(field),row.metrics().get(field)))throw new IllegalStateException("D027 field mismatch on "+row.date()+" column="+field);}
        if(seen.size()!=expected.size())throw new IllegalStateException("D027 QuestDB missing source dates");
    }
    private static void requirePhysicalSchema(JdbcTemplate jdbc,String table){
        var columns=jdbc.queryForList("SELECT \"column\",\"type\",\"upsertKey\" FROM table_columns('"+table+"')");
        var expected=Map.of("trade_date","TIMESTAMP","ggt_ss","DOUBLE","ggt_sz","DOUBLE","hgt","DOUBLE","sgt","DOUBLE","north_money","DOUBLE","south_money","DOUBLE");
        if(columns.size()!=expected.size())throw new IllegalStateException("D027 physical schema column count differs");
        var actual=new HashMap<String,String>();for(var c:columns){String name=c.get("column").toString();actual.put(name,c.get("type").toString());if(Boolean.TRUE.equals(c.get("upsertKey")))throw new IllegalStateException("D027 target must preserve DEDUP=false");}
        if(!actual.equals(expected))throw new IllegalStateException("D027 physical columns/types differ from the frozen seven-field contract");
        var tableRow=jdbc.queryForList("SELECT designatedTimestamp,partitionBy,walEnabled FROM tables() WHERE table_name=?",table);
        if(tableRow.size()!=1||!"trade_date".equals(tableRow.getFirst().get("designatedTimestamp"))
                ||!"DAY".equals(tableRow.getFirst().get("partitionBy"))||!Boolean.TRUE.equals(tableRow.getFirst().get("walEnabled")))
            throw new IllegalStateException("D027 physical object must be DAY/WAL/DEDUP=false");
    }
    private static Set<String> fieldNames(JsonNode node){var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);return names;}
    private static String scalar(JsonNode node){if(node==null||node.isNull()||!(node.isTextual()||node.isIntegralNumber()))throw new IllegalStateException("D027 required raw scalar missing");return node.asText();}
    private static Double numberOrNull(JsonNode node){if(node==null||node.isNull())return null;if(!(node.isNumber()||node.isTextual()))throw new IllegalStateException("D027 metric is not scalar");try{double v=new BigDecimal(node.asText().strip()).doubleValue();if(!Double.isFinite(v))throw new NumberFormatException();return v;}catch(RuntimeException invalid){throw new IllegalStateException("D027 invalid raw numeric field",invalid);}}
    private static boolean same(Double a,Double b){return a==null?b==null:b!=null&&Double.doubleToLongBits(a)==Double.doubleToLongBits(b);}
    private static long micros(LocalDate date){return Math.multiplyExact(date.toEpochDay(),86_400_000_000L);}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
