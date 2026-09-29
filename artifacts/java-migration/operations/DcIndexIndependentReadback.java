import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.service.DcIndexTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Independent D023 receipt-to-physical-table comparison; never calls the production source/mapper/write port. */
public final class DcIndexIndependentReadback {
    private static final String JOB="data.dc_index";private static final int VERSION=1,ROW_CAP=5_000,MAX_DAYS=5,MAX_EVIDENCE_BYTES=16*1024*1024;
    private static final DateTimeFormatter BASIC=DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> FIELDS=List.of("ts_code","trade_date","name","leading","leading_code","pct_change","leading_pct","total_mv","turnover_rate","up_num","down_num");
    private static final List<String> DOUBLE_FIELDS=List.of("pct_change","leading_pct","total_mv","turnover_rate");
    private record Key(LocalDate date,String code){}
    private record Expected(Key key,Map<String,Object> values){}
    private record Actual(Key key,long micros,Map<String,Object> values){}
    private DcIndexIndependentReadback(){}

    public static Map<String,Object> verify(JdbcTemplate jdbc,Path ledger,String table,String runId)throws Exception{
        Objects.requireNonNull(jdbc);Objects.requireNonNull(ledger);Objects.requireNonNull(table);Objects.requireNonNull(runId);
        if(!table.matches("java_d023_dc_index_[A-Za-z0-9_]{1,80}")||!runId.matches("[A-Za-z0-9_.-]{1,128}")||runId.contains(".."))throw new IllegalArgumentException("D023 isolated target and bounded run ID required");
        var json=JobDefinitionJson.mapper();var history=SyncRunLedger.openReadOnly(ledger.toAbsolutePath().normalize());var run=history.getRun(runId);var state=history.get(runId);
        if(!JOB.equals(run.jobId())||run.jobVersion()!=VERSION||!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(state.state()))throw new IllegalStateException("Terminal verified D023 run required for independent readback");
        JsonNode frozen=json.readTree(run.frozenJson()),params=frozen.path("parameters");
        if(!JOB.equals(frozen.path("definition").path("jobId").asText())||frozen.path("definition").path("version").asInt(-1)!=VERSION)
            throw new IllegalStateException("Frozen D023 definition differs from this oracle contract");
        String logical=required(params,"targetId"),physicalBefore=required(params,"physicalTargetId");
        if(!logical.equals(run.targetId())||!logical.matches("static-v2-[0-9a-f]{64}")||!physicalBefore.matches("static-v2-[0-9a-f]{64}"))throw new IllegalStateException("D023 logical/physical identities are not frozen consistently");
        LocalDate from=LocalDate.parse(required(frozen,"from")),to=LocalDate.parse(required(frozen,"to")),logicalDate=LocalDate.parse(required(frozen,"logicalDate"));
        long days=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;if(from.isAfter(to)||days<1||days>MAX_DAYS||to.isAfter(logicalDate))throw new IllegalStateException("Frozen D023 window outside its five-day/logical ceiling");
        List<LocalDate> dates=decodeDates(required(params,"trade_dates"),from,to);if(dates.isEmpty())throw new IllegalStateException("D023 requires at least one frozen open date");
        if(!logical.equals(DcIndexTargetIdentity.logical(jdbc,table)))throw new IllegalStateException("D023 endpoint/table logical identity differs");
        Path evidenceRoot=ledger.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).toRealPath();
        var slices=history.entries(runId,null,1000).stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();if(slices.size()!=dates.size())throw new IllegalStateException("D023 ledger slice count differs from frozen open dates");
        var byDate=new HashMap<LocalDate,SyncRunLedger.Entry>();
        for(var slice:slices){if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(slice.state()))throw new IllegalStateException("D023 contains unverified source slice");
            var fetched=history.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();if(fetched.size()!=1)throw new IllegalStateException("Each D023 slice must have one immutable FETCHED source event");
            JsonNode event=json.readTree(fetched.getFirst().payloadJson());String cursor=required(event,"cursor");if(!cursor.matches("[0-9]{8}"))throw new IllegalStateException("D023 source cursor is not BASIC_ISO_DATE");LocalDate date=LocalDate.parse(cursor,BASIC);
            if(!dates.contains(date)||byDate.putIfAbsent(date,slice)!=null)throw new IllegalStateException("D023 duplicate/out-of-range source date slice");}
        var expected=new TreeMap<Key,Expected>(Comparator.comparing(Key::date).thenComparing(Key::code));long sourceRows=0,sourceDuplicates=0,evidenceBytes=0;
        var receiptSummary=new ArrayList<Map<String,Object>>();var receiptPaths=new ArrayList<String>();var fingerprints=new ArrayList<String>();
        for(LocalDate date:dates){var slice=byDate.get(date);JsonNode fetch=json.readTree(history.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).findFirst().orElseThrow().payloadJson());
            int declared=fetch.path("returnedRows").asInt(-1);String fingerprint=required(fetch,"sourceFingerprint");if(declared<0||declared>=ROW_CAP||!fingerprint.matches("[0-9a-f]{64}")||!date.format(BASIC).equals(required(fetch,"cursor")))throw new IllegalStateException("D023 FETCHED event violates date/cap contract");
            Path receipt=safeEvidence(evidenceRoot,required(fetch,"responseEvidence"));byte[] rawBytes=Files.readAllBytes(receipt);evidenceBytes=Math.addExact(evidenceBytes,rawBytes.length);if(evidenceBytes>MAX_DAYS*16L*1024*1024||!sha(rawBytes).equals(fingerprint))throw new IllegalStateException("D023 raw receipt SHA or total evidence bound failed");
            JsonNode proof=json.readTree(rawBytes);validateReceipt(proof,date,declared,params);
            if((declared==0)!=(slice.state()==SyncRunState.VERIFIED_EMPTY))throw new IllegalStateException("D023 slice state differs from raw source response");
            for(JsonNode raw:proof.path("rawRows")){Expected row=normalize(raw,date);if(expected.putIfAbsent(row.key(),row)!=null)sourceDuplicates++;}
            sourceRows=Math.addExact(sourceRows,declared);fingerprints.add(fingerprint);receiptPaths.add(receipt.toString());
            receiptSummary.add(Map.of("date",date.toString(),"rows",declared,"path",receipt.toString(),"sha256",fingerprint));}

        long fromMicros=epochMicros(from),toMicros=epochMicros(to.plusDays(1));String sql="SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,name,leading,leading_code,pct_change,leading_pct,total_mv,turnover_rate,up_num,down_num FROM \""+table+"\" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "+(MAX_DAYS*ROW_CAP+1);
        List<Actual> actual=jdbc.query(sql,actualMapper(),fromMicros,toMicros);if(actual.size()>MAX_DAYS*ROW_CAP)throw new IllegalStateException("D023 physical-window readback exceeds five-day bound");
        var actualByKey=new HashMap<Key,Actual>();long duplicates=0,extras=0,missing=0,mismatchRows=0,matched=0;var mismatchSamples=new ArrayList<String>();
        for(var row:actual){if(actualByKey.putIfAbsent(row.key(),row)!=null){duplicates++;sample(mismatchSamples,"duplicate-target:"+row.key());}if(!expected.containsKey(row.key())){extras++;sample(mismatchSamples,"unexpected-target:"+row.key());}}
        for(var entry:expected.entrySet()){Actual row=actualByKey.get(entry.getKey());if(row==null){missing++;sample(mismatchSamples,"missing:"+entry.getKey());continue;}boolean mismatch=row.micros()!=epochMicros(entry.getKey().date());
            for(String field:FIELDS){if(field.equals("trade_date"))continue;if(!same(entry.getValue().values().get(field),row.values().get(field))){mismatch=true;sample(mismatchSamples,"value:"+entry.getKey()+":"+field);}}
            if(mismatch)mismatchRows++;else matched++;}
        Path completion=safeEvidence(evidenceRoot,evidenceRoot.resolve("complete-window.json").toString());JsonNode complete=json.readTree(completion.toFile());String combined=combine(fingerprints);
        if(!"dc_index".equals(complete.path("dataset").asText())||!"dc_index".equals(complete.path("endpoint").asText())
                ||!frozen.path("mode").asText().equals(complete.path("mode").asText())
                ||!complete.path("complete").asBoolean(false)||!complete.path("sourceComplete").asBoolean(false)||!complete.path("dedup").isBoolean()||complete.path("dedup").asBoolean()
                ||!from.toString().equals(complete.path("fromInclusive").asText())||!to.toString().equals(complete.path("toInclusive").asText())
                ||!json.valueToTree(dates).equals(complete.path("tradeDates"))||complete.path("completedDateSlices").asInt(-1)!=dates.size()
                ||complete.path("sourceRows").asLong(-1)!=sourceRows||complete.path("returnedRows").asLong(-1)!=sourceRows||complete.path("submittedRows").asLong(-1)!=sourceRows
                ||!combined.equals(required(complete,"sourceFingerprint"))||!json.valueToTree(receiptPaths).equals(complete.path("sourceReceipts"))
                ||!complete.path("snapshotProof").path("fingerprint").isTextual())throw new IllegalStateException("D023 stage completion receipt differs from frozen daily receipts");
        Path publicationPath=safeEvidence(evidenceRoot,evidenceRoot.resolve("publication-window.json").toString());JsonNode publication=json.readTree(publicationPath.toFile());
        String physicalAfter=required(publication,"physicalTargetAfter");var tables=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(tables.size()!=1||!(tables.getFirst().get("id") instanceof Number id)||!(tables.getFirst().get("directoryName") instanceof String directory)
                ||!physicalAfter.equals(com.zoutrankil.questdbwithdata.service.StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory)))throw new IllegalStateException("D023 published physical identity differs from the actual QuestDB target");
        if(!logical.equals(required(publication,"logicalTargetId"))||!physicalBefore.equals(required(publication,"physicalTargetBefore"))
                ||!from.toString().equals(required(publication,"fromInclusive"))||!to.toString().equals(required(publication,"toInclusive"))
                ||!combined.equals(required(publication,"sourceFingerprint"))||!completion.toString().equals(required(publication,"evidence"))||!publication.path("passed").asBoolean(false)
                ||!publication.path("writerStopped").asBoolean(false)||publication.path("expectedRows").asInt(-1)!=expected.size()
                ||publication.path("actualRows").asInt(-1)!=expected.size()||!complete.path("snapshotProof").path("fingerprint").asText().equals(required(publication,"fullTargetFingerprint")))
            throw new IllegalStateException("D023 publication proof differs from frozen window/source/stage snapshot");
        boolean matchedAll=sourceDuplicates==0&&duplicates==0&&extras==0&&missing==0&&mismatchRows==0&&actual.size()==expected.size();
        return Map.ofEntries(Map.entry("status",matchedAll?"MATCHED":"MISMATCH"),Map.entry("table",table),Map.entry("runId",runId),
                Map.entry("fromInclusive",from.toString()),Map.entry("toInclusive",to.toString()),Map.entry("tradeDates",dates.stream().map(LocalDate::toString).toList()),
                Map.entry("sourceRows",sourceRows),Map.entry("actualRows",actual.size()),Map.entry("matchedRows",matched),Map.entry("mismatchedRows",mismatchRows),
                Map.entry("sourceDuplicateKeys",sourceDuplicates),Map.entry("duplicateKeys",duplicates),Map.entry("missingKeys",missing),Map.entry("extraKeys",extras),
                Map.entry("evidenceBytes",evidenceBytes),Map.entry("receipts",receiptSummary),Map.entry("mismatchSamples",mismatchSamples),Map.entry("physicalTargetBefore",physicalBefore),Map.entry("physicalTargetAfter",physicalAfter));
    }
    private static void validateReceipt(JsonNode r,LocalDate date,int rows,JsonNode frozenParams){
        if(!"tushare".equals(r.path("sourceKind").asText())||!"dc_index".equals(r.path("endpoint").asText())||!r.path("sourceComplete").asBoolean(false)
                ||!date.toString().equals(r.path("tradeDate").asText())||r.path("sourceRowCap").asInt(-1)!=ROW_CAP||r.path("returnedRows").asInt(-1)!=rows
                ||!r.path("rawRows").isArray()||r.path("rawRows").size()!=rows||rows>=ROW_CAP||!JobDefinitionJson.mapper().valueToTree(FIELDS).equals(r.path("fields"))
                ||!date.format(BASIC).equals(r.path("parameters").path("trade_date").asText()))throw new IllegalStateException("D023 raw receipt differs from frozen endpoint/date/fields/cap");
        if(!r.path("parameters").path("trade_date").asText().matches("[0-9]{8}"))throw new IllegalStateException("D023 receipt request date is invalid");
    }
    private static Expected normalize(JsonNode raw,LocalDate date){var names=new TreeSet<String>();raw.fieldNames().forEachRemaining(names::add);if(!names.equals(new TreeSet<>(FIELDS)))throw new IllegalStateException("D023 source row fields differ from exact 11-column contract");
        String code=text(raw,"ts_code",false);if(!code.matches("[A-Z0-9_]+\\.DC")||!date.format(BASIC).equals(text(raw,"trade_date",false)))throw new IllegalStateException("D023 source code/date invalid");
        var v=new LinkedHashMap<String,Object>();v.put("ts_code",code);v.put("trade_date",date);v.put("name",text(raw,"name",true));v.put("leading",text(raw,"leading",true));v.put("leading_code",text(raw,"leading_code",true));
        for(String f:DOUBLE_FIELDS)v.put(f,number(raw.get(f),f));v.put("up_num",integer(raw.get("up_num"),"up_num"));v.put("down_num",integer(raw.get("down_num"),"down_num"));return new Expected(new Key(date,code),v);
    }
    private static RowMapper<Actual> actualMapper(){return (rs,n)->{Object raw=rs.getObject("trade_date_micros");if(!(raw instanceof Number micros))throw new SQLException("D023 trade_date missing");LocalDate date=fromMicros(micros.longValue());var v=new LinkedHashMap<String,Object>();
        v.put("ts_code",rs.getString("ts_code"));v.put("trade_date",date);v.put("name",rs.getString("name"));v.put("leading",rs.getString("leading"));v.put("leading_code",rs.getString("leading_code"));
        for(String f:DOUBLE_FIELDS){double d=rs.getDouble(f);v.put(f,rs.wasNull()?null:d);}for(String f:List.of("up_num","down_num")){int i=rs.getInt(f);v.put(f,rs.wasNull()?null:i);}
        return new Actual(new Key(date,rs.getString("ts_code")),micros.longValue(),v);};}
    private static String text(JsonNode row,String field,boolean nullable){JsonNode v=row.get(field);if(v==null||v.isNull()){if(nullable)return null;throw new IllegalStateException("Missing D023 field "+field);}if(!v.isTextual())throw new IllegalStateException("Nontext D023 field "+field);return v.textValue();}
    private static Double number(JsonNode value,String field){if(value==null||value.isNull())return null;if(!value.isNumber()&&!value.isTextual())throw new IllegalStateException("Nonnumeric D023 field "+field);double d;try{d=new BigDecimal(value.asText()).doubleValue();}catch(Exception e){throw new IllegalStateException("Invalid D023 numeric "+field,e);}if(!Double.isFinite(d))throw new IllegalStateException("Nonfinite D023 numeric "+field);return d;}
    private static Integer integer(JsonNode value,String field){if(value==null||value.isNull())return null;if(!value.isIntegralNumber()&&!value.isTextual())throw new IllegalStateException("Nonintegral D023 count "+field);try{int n=Integer.parseInt(value.asText());if(n<0)throw new NumberFormatException();return n;}catch(Exception e){throw new IllegalStateException("Invalid D023 count "+field,e);}}
    private static boolean same(Object a,Object b){if(a==null||b==null)return a==b;if(a instanceof Number x&&b instanceof Number y)return Double.compare(x.doubleValue(),y.doubleValue())==0;return a.equals(b);}
    private static List<LocalDate> decodeDates(String raw,LocalDate from,LocalDate to){var dates=Arrays.stream(raw.split(",",-1)).map(s->{if(!s.matches("[0-9]{8}"))throw new IllegalStateException("Invalid D023 trade-date list");return LocalDate.parse(s,BASIC);}).toList();
        if(dates.size()>MAX_DAYS||dates.stream().distinct().count()!=dates.size()||!dates.equals(dates.stream().sorted().toList())||dates.stream().anyMatch(d->d.isBefore(from)||d.isAfter(to)))throw new IllegalStateException("D023 trade dates duplicate/order/range failure");return dates;}
    private static String required(JsonNode json,String field){JsonNode v=json.path(field);if(!v.isTextual()||v.asText().isBlank())throw new IllegalStateException("D023 evidence missing "+field);return v.asText();}
    private static Path safeEvidence(Path root,String reference)throws Exception{Path raw=Path.of(reference).toAbsolutePath().normalize();if(!raw.startsWith(root)||!Files.isRegularFile(raw)||!raw.toRealPath().startsWith(root)||Files.size(raw)>MAX_EVIDENCE_BYTES)throw new IllegalStateException("D023 evidence absent/outside/oversized");return raw;}
    private static long epochMicros(LocalDate date){return date.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()*1_000_000L;}
    private static LocalDate fromMicros(long micros){long seconds=Math.floorDiv(micros,1_000_000),rest=Math.floorMod(micros,1_000_000);var instant=java.time.Instant.ofEpochSecond(seconds,rest*1000);if(!instant.atOffset(java.time.ZoneOffset.UTC).toLocalTime().equals(java.time.LocalTime.MIDNIGHT))throw new IllegalStateException("D023 trade_date timestamp not UTC-midnight calendar carrier");return instant.atOffset(java.time.ZoneOffset.UTC).toLocalDate();}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String combine(List<String> values)throws Exception{var digest=MessageDigest.getInstance("SHA-256");for(String value:values){digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);}return HexFormat.of().formatHex(digest.digest());}
    private static void sample(List<String> out,String value){if(out.size()<20)out.add(value);}
}
