package com.zoutrankil.questdbwithdata.artifacts.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import com.zoutrankil.questdbwithdata.service.MarginSecsSyncJobOwner;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent raw-receipt to SQL verifier; it intentionally avoids D030's Source, DTO, mapper, and port. */
public final class MarginSecsIndependentReadback {
    private static final String JOB="data.margin_secs";
    private static final int MAX_DAYS=31,API_CAP=6000,MAX_RECEIPT_BYTES=16*1024*1024;
    private static final long DAY_MICROS=86_400_000_000L;
    private static final DateTimeFormatter BASIC=DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> FIELDS=List.of("trade_date","ts_code","name","exchange");
    private MarginSecsIndependentReadback(){}
    private record Key(LocalDate date,String code){}
    private record Expected(Key key,String name,String exchange){}
    private record Actual(Key key,long micros,String name,String exchange){}
    private record SourceSlice(LocalDate date,int rows,String fingerprint,Path receipt,List<Expected> expected){}

    public static Map<String,Object> verify(JdbcTemplate jdbc,Path ledgerPath,String table,String runId)throws Exception{
        if(jdbc==null||ledgerPath==null||table==null||!table.matches("java_d030_margin_secs_[A-Za-z0-9_]{1,80}")
                ||runId==null||!runId.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("D030 explicit isolated table, ledger and run ID required");
        Path ledgerPathAbs=ledgerPath.toAbsolutePath().normalize();var ledger=SyncRunLedger.openReadOnly(ledgerPathAbs);var json=JobDefinitionJson.mapper();
        var run=ledger.getRun(runId);var terminal=ledger.get(runId);
        if(!JOB.equals(run.jobId())||run.jobVersion()!=1||!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(terminal.state()))
            throw new IllegalStateException("D030 terminal verified run required");
        JsonNode frozen=json.readTree(run.frozenJson()),params=frozen.path("parameters");
        if(!MarginSecsSyncJobOwner.DEFINITION.equals(json.treeToValue(frozen.path("definition"),SyncJobDefinition.class)))
            throw new IllegalStateException("D030 frozen job definition differs from this verifier's contract");
        LocalDate from=LocalDate.parse(required(frozen,"from")),to=LocalDate.parse(required(frozen,"to")),logical=LocalDate.parse(required(frozen,"logicalDate"));
        long span=ChronoUnit.DAYS.between(from,to)+1;if(from.isAfter(to)||span<1||span>MAX_DAYS||to.isAfter(logical))throw new IllegalStateException("D030 frozen date range is invalid");
        String targetId=required(params,"targetId"),physicalId=required(params,"physicalTargetId");
        if(!targetId.equals(run.targetId())||!targetId.equals(physicalId)||!targetId.matches("static-v2-[0-9a-f]{64}"))throw new IllegalStateException("D030 frozen logical/physical target differs");
        verifySchema(jdbc,table,targetId);
        List<String> calendarDays=split(required(params,"calendarDays")),tradeDates=split(required(params,"tradeDates"));
        String calendarFingerprint=required(params,"calendarFingerprint");
        if(!sha(String.join("\n",calendarDays).getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(calendarFingerprint))throw new IllegalStateException("D030 frozen SSE calendar fingerprint mismatch");
        var expectedOpen=new ArrayList<String>();LocalDate cursor=from;
        for(String encoded:calendarDays){if(!encoded.matches("[0-9]{8}:[01]")||!LocalDate.parse(encoded.substring(0,8),BASIC).equals(cursor))throw new IllegalStateException("D030 calendar does not exactly cover request dates");if(encoded.endsWith(":1"))expectedOpen.add(encoded.substring(0,8));cursor=cursor.plusDays(1);}
        if(!cursor.equals(to.plusDays(1))||!expectedOpen.equals(tradeDates)||tradeDates.isEmpty()||tradeDates.size()>MAX_DAYS)throw new IllegalStateException("D030 frozen trade sessions differ from exact calendar proof");
        Path runRoot=ledgerPathAbs.getParent().resolve("sync-evidence").resolve(runId).toRealPath();Path sourceRoot=runRoot.resolve("source").toRealPath();
        if(!sourceRoot.startsWith(runRoot))throw new IllegalStateException("D030 source receipts root escapes run evidence");
        var slices=readSlices(ledger,json,runId,tradeDates,sourceRoot);
        long sourceRows=slices.stream().mapToLong(SourceSlice::rows).sum(),receiptBytes=0;for(var s:slices)receiptBytes=Math.addExact(receiptBytes,Files.size(s.receipt()));
        long maxRows=(long)tradeDates.size()*API_CAP;if(sourceRows>maxRows||receiptBytes>(long)tradeDates.size()*MAX_RECEIPT_BYTES)throw new IllegalStateException("D030 run exceeds independent evidence bounds");
        var expected=new HashMap<Key,Expected>();long duplicateSource=0;var mismatchSamples=new ArrayList<String>();
        for(var slice:slices)for(var row:slice.expected())if(expected.putIfAbsent(row.key(),row)!=null){duplicateSource++;sample(mismatchSamples,"duplicate-source:"+row.key());}
        String sql="SELECT cast(trade_date AS long) AS trade_micros,ts_code,name,exchange FROM \""+table+"\" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "+(maxRows+1);
        var actualRows=jdbc.query(sql,(rs,n)->actual(rs),micros(from),micros(to.plusDays(1)));
        if(actualRows.size()>maxRows)throw new IllegalStateException("D030 actual row count exceeds the frozen session/cap bound");
        var actual=new HashMap<Key,Actual>();long duplicateTarget=0,extra=0;
        for(var row:actualRows){if(actual.putIfAbsent(row.key(),row)!=null){duplicateTarget++;sample(mismatchSamples,"duplicate-target:"+row.key());}
            if(!expected.containsKey(row.key())){extra++;sample(mismatchSamples,"extra-target:"+row.key());}}
        long missing=0,mismatched=0,matched=0,fieldsCompared=0;
        for(var item:expected.entrySet()){Actual row=actual.get(item.getKey());if(row==null){missing++;sample(mismatchSamples,"missing:"+item.getKey());continue;}
            fieldsCompared+=FIELDS.size();Expected want=item.getValue();boolean bad=row.micros()!=micros(want.key().date())||!java.util.Objects.equals(want.name(),row.name())||!want.exchange().equals(row.exchange());
            if(bad){mismatched++;sample(mismatchSamples,"value-mismatch:"+item.getKey());}else matched++;}
        var terminalEvents=ledger.events(runId,-1,100);if(terminalEvents.isEmpty()||terminalEvents.getLast().state()!=terminal.state())throw new IllegalStateException("D030 terminal ledger event missing");
        JsonNode terminalProof=json.readTree(terminalEvents.getLast().payloadJson());
        if(terminal.state()==SyncRunState.VERIFIED_EMPTY){if(sourceRows!=0||!terminalProof.path("sourceComplete").asBoolean(false)||terminalProof.path("returnedRows").asInt(-1)!=0)
            throw new IllegalStateException("D030 verified-empty ledger evidence disagrees with raw receipts");}
        else {JsonNode v=terminalProof.path("verification");if(!v.path("passed").asBoolean(false)||v.path("expectedRows").asLong(-1)!=sourceRows
                    ||v.path("actualRows").asLong(-1)!=sourceRows||v.path("matchedRows").asLong(-1)!=sourceRows||v.path("mismatchedRows").asLong(-1)!=0
                    ||v.path("duplicateKeys").asLong(-1)!=0||v.path("missingKeys").asLong(-1)!=0)throw new IllegalStateException("D030 run's ledger verification proof does not agree with receipt row count");}
        boolean passed=duplicateSource==0&&duplicateTarget==0&&extra==0&&missing==0&&mismatched==0&&expected.size()==actualRows.size();
        var result=new LinkedHashMap<String,Object>();result.put("status",passed?"MATCHED":"MISMATCH");result.put("task","D030");result.put("dataset","margin_secs");
        result.put("runId",runId);result.put("table",table);result.put("targetId",targetId);result.put("fromInclusive",from.toString());result.put("toInclusive",to.toString());
        result.put("tradeDates",tradeDates);result.put("comparedColumns",FIELDS);result.put("fieldsCompared",fieldsCompared);result.put("sourceRows",sourceRows);
        result.put("expectedRows",expected.size());result.put("actualRows",actualRows.size());result.put("matchedRows",matched);result.put("mismatchedRows",mismatched);
        result.put("sourceDuplicateKeys",duplicateSource);result.put("duplicateKeys",duplicateTarget);result.put("missingKeys",missing);result.put("extraKeys",extra);
        result.put("receiptBytes",receiptBytes);result.put("sourceFingerprints",slices.stream().map(SourceSlice::fingerprint).toList());result.put("query",sql);
        result.put("mismatchSamples",mismatchSamples);result.put("passed",passed);return Map.copyOf(result);
    }

    private static List<SourceSlice> readSlices(SyncRunLedger ledger,ObjectMapper json,String runId,List<String> dates,Path sourceRoot)throws Exception{
        var entries=ledger.entries(runId,null,1000);if(entries.size()>=1000)throw new IllegalStateException("D030 run ledger child bound exceeded");
        var slices=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();if(slices.size()!=dates.size())throw new IllegalStateException("D030 ledger slice count differs from frozen open dates");
        var found=new HashMap<String,SyncRunLedger.Entry>();for(var slice:slices){if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(slice.state()))throw new IllegalStateException("D030 has an unverified session slice");
            var events=ledger.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();if(events.size()!=1)throw new IllegalStateException("D030 slice must have one FETCHED raw receipt event");
            JsonNode e=json.readTree(events.getFirst().payloadJson());String date=required(e,"cursor");if(!date.matches("[0-9]{8}")||found.putIfAbsent(date,slice)!=null)throw new IllegalStateException("D030 duplicate/invalid slice cursor");}
        var result=new ArrayList<SourceSlice>();for(String basic:dates){var slice=found.get(basic);if(slice==null)throw new IllegalStateException("D030 frozen session has no source slice");
            JsonNode event=json.readTree(ledger.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).findFirst().orElseThrow().payloadJson());int count=event.path("returnedRows").asInt(-1);
            String fingerprint=required(event,"sourceFingerprint"),raw=required(event,"responseEvidence");if(count<0||count>=API_CAP||!fingerprint.matches("[0-9a-f]{64}"))throw new IllegalStateException("D030 response hit/violated conservative unpaged cap");
            Path candidate=Path.of(raw).toAbsolutePath().normalize();if(!candidate.startsWith(sourceRoot)||Files.isSymbolicLink(candidate)||!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)
                    ||Files.size(candidate)<1||Files.size(candidate)>MAX_RECEIPT_BYTES||!candidate.toRealPath().startsWith(sourceRoot))throw new IllegalStateException("D030 receipt missing/escaped/symlinked/over bound");
            byte[] bytes=Files.readAllBytes(candidate);if(!sha(bytes).equals(fingerprint))throw new IllegalStateException("D030 source receipt SHA-256 mismatch");JsonNode body=json.readTree(bytes);
            if(!"tushare".equals(body.path("sourceKind").asText())||!"margin_secs".equals(body.path("endpoint").asText())||!body.path("sourceComplete").asBoolean(false)
                    ||body.path("apiMaximumRows").asInt(-1)!=API_CAP||!basic.equals(body.path("tradeDate").asText().replace("-",""))
                    ||!basic.equals(body.path("parameters").path("trade_date").asText())||body.path("returnedRows").asInt(-1)!=count
                    ||count!=body.path("rawRows").size()||!json.valueToTree(FIELDS).equals(body.path("fields")))throw new IllegalStateException("D030 raw receipt differs from exact source contract");
            var expected=new ArrayList<Expected>(count);for(JsonNode row:body.path("rawRows")){if(!fieldNames(row).equals(Set.copyOf(FIELDS)))throw new IllegalStateException("D030 raw row has missing/extra fields");
                String date=scalar(row.get("trade_date")).replace("-","");String code=scalar(row.get("ts_code")).strip();String exchange=scalar(row.get("exchange"));JsonNode rawName=row.get("name");
                if(!basic.equals(date)||!code.matches("[0-9]{6}\\.(?:SH|SZ|BJ)")||!exchange.equals(exchange.strip())||rawName!=null&&!rawName.isNull()&&!rawName.isTextual())throw new IllegalStateException("D030 raw source row violates date/key/text contract");
                expected.add(new Expected(new Key(LocalDate.parse(date,BASIC),code),rawName==null||rawName.isNull()?null:rawName.textValue(),exchange));}
            if((count==0)!=(slice.state()==SyncRunState.VERIFIED_EMPTY))throw new IllegalStateException("D030 empty-slice ledger state disagrees with raw row count");
            result.add(new SourceSlice(LocalDate.parse(basic,BASIC),count,fingerprint,candidate,List.copyOf(expected)));}
        return List.copyOf(result);
    }
    private static void verifySchema(JdbcTemplate jdbc,String table,String targetId){
        var columns=jdbc.queryForList("SELECT \"column\",\"type\",\"upsertKey\" FROM table_columns('"+table+"')");
        Map<String,String> expected=Map.of("trade_date","TIMESTAMP","ts_code","SYMBOL","name","STRING","exchange","SYMBOL");if(columns.size()!=expected.size())throw new IllegalStateException("D030 physical schema column count differs");
        var actual=new HashMap<String,String>();var keys=new HashSet<String>();for(var col:columns){String name=String.valueOf(col.get("column"));actual.put(name,String.valueOf(col.get("type")));if(Boolean.TRUE.equals(col.get("upsertKey")))keys.add(name);}
        if(!actual.equals(expected)||!keys.equals(Set.of("trade_date","ts_code")))throw new IllegalStateException("D030 schema/upsert-key differs from frozen physical contract");
        var meta=jdbc.queryForList("SELECT id,directoryName,designatedTimestamp,partitionBy,walEnabled,dedup FROM tables() WHERE table_name=?",table);
        if(meta.size()!=1||!(meta.getFirst().get("id") instanceof Number id)||!(meta.getFirst().get("directoryName") instanceof String directory)
                ||!targetId.equals(StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory))||!"trade_date".equals(meta.getFirst().get("designatedTimestamp"))
                ||!"YEAR".equals(meta.getFirst().get("partitionBy"))||!Boolean.TRUE.equals(meta.getFirst().get("walEnabled"))||!Boolean.TRUE.equals(meta.getFirst().get("dedup")))
            throw new IllegalStateException("D030 exact isolated YEAR/WAL/DEDUP target identity/layout required");
    }
    private static Actual actual(java.sql.ResultSet rs)throws SQLException{Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number n))throw new SQLException("D030 physical timestamp missing");long micros=n.longValue();
        if(Math.floorMod(micros,DAY_MICROS)!=0)throw new SQLException("D030 trade_date must be UTC-midnight calendar timestamp");LocalDate date=LocalDate.ofEpochDay(Math.floorDiv(micros,DAY_MICROS));
        String code=rs.getString("ts_code"),exchange=rs.getString("exchange");if(code==null||exchange==null)throw new SQLException("D030 required physical symbol is null");return new Actual(new Key(date,code),micros,rs.getString("name"),exchange);}
    private static Set<String> fieldNames(JsonNode node){var result=new HashSet<String>();node.fieldNames().forEachRemaining(result::add);return result;}
    private static String scalar(JsonNode node){if(node==null||node.isNull()||!(node.isTextual()||node.isIntegralNumber()))throw new IllegalStateException("D030 required raw scalar missing");return node.asText();}
    private static String required(JsonNode node,String name){JsonNode value=node.path(name);if(!value.isTextual()||value.asText().isBlank())throw new IllegalStateException("D030 required receipt field missing: "+name);return value.asText();}
    private static List<String> split(String text){if(text.isBlank())return List.of();return List.of(text.split(",",-1));}
    private static long micros(LocalDate date){return Math.multiplyExact(date.toEpochDay(),DAY_MICROS);}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void sample(List<String> samples,String value){if(samples.size()<20)samples.add(value);}
}
