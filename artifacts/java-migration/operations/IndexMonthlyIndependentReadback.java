import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.ReferencePublicationJournal;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.service.IndexMonthlySyncJobOwner;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Independent D022 raw-receipt to all-physical-column checker; does not call the mapper or write port. */
public final class IndexMonthlyIndependentReadback {
    private static final List<String> METRICS=List.of("close","open","high","low","pre_close","change","pct_chg","vol","amount");
    private static final List<String> COLUMNS=List.of("ts_code","trade_date","close","open","high","low","pre_close","change","pct_chg","vol","amount","layer","bucket","update_time");
    private static final List<String> SOURCE_FIELDS=List.of("ts_code","trade_date","close","open","high","low","pre_close","change","pct_chg","vol","amount");
    private static final int MAX_SOURCE_BYTES=16*1024*1024;
    private IndexMonthlyIndependentReadback(){}

    public static Map<String,Object> verify(JdbcTemplate jdbc,Path ledgerPath,String table,String runId)throws Exception {
        Objects.requireNonNull(jdbc);Objects.requireNonNull(ledgerPath);
        if(table==null||!table.matches("[A-Za-z_][A-Za-z0-9_]*")||!table.startsWith("java_d022_index_monthly_"))
            throw new IllegalArgumentException("Explicit D022 isolated table required");
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);var runEntry=ledger.get(runId);
        if(!IndexMonthlySyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=IndexMonthlySyncJobOwner.DEFINITION.version()
                ||!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(runEntry.state()))
            throw new IllegalStateException("D022 run must be verified in the run ledger");
        JsonNode frozen=JobDefinitionJson.mapper().readTree(run.frozenJson()),params=frozen.path("parameters");
        String logical=run.targetId(),code=params.path("tsCode").asText();
        if(!logical.equals(params.path("targetId").asText())||!code.matches("[0-9]{6}\\.(SH|SZ)"))
            throw new IllegalStateException("D022 frozen logical target or provider code differs");
        LocalDate from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());
        if(from.isAfter(to)||ChronoUnit.DAYS.between(from,to)+1>3660)throw new IllegalStateException("D022 frozen window exceeds bound");
        Instant observed=Instant.parse(params.path("observedAt").asText());
        if(!observed.equals(observed.truncatedTo(ChronoUnit.MICROS)))throw new IllegalStateException("D022 observedAt is not microsecond precise");
        assertSchema(jdbc,table);

        List<SyncRunLedger.Entry> slices=new ArrayList<>();String after=null;
        while(true){var entries=ledger.entries(runId,after,100);for(var e:entries)if(e.kind()==SyncRunLedger.Kind.SLICE)slices.add(e);if(entries.size()<100)break;after=entries.getLast().id();}
        if(slices.size()!=1||!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(slices.getFirst().state()))
            throw new IllegalStateException("D022 run must have exactly one verified source slice");
        var slice=slices.getFirst();JsonNode fetched=null;
        for(var event:ledger.events(slice.id(),-1,100))if(event.state()==SyncRunState.FETCHED){if(fetched!=null)throw new IllegalStateException("Duplicate D022 fetched receipt");fetched=JobDefinitionJson.mapper().readTree(event.payloadJson());}
        if(fetched==null||!code.equals(fetched.path("cursor").asText()))throw new IllegalStateException("D022 fetched source cursor differs from code");
        String receiptPath=fetched.path("responseEvidence").asText(),fingerprint=fetched.path("sourceFingerprint").asText();
        if(receiptPath.isBlank()||!fingerprint.matches("[0-9a-f]{64}"))throw new IllegalStateException("D022 source receipt reference missing");
        Path receiptFile=Path.of(receiptPath).toAbsolutePath().normalize();
        if(!Files.isRegularFile(receiptFile)||Files.size(receiptFile)>MAX_SOURCE_BYTES)throw new IllegalStateException("D022 source receipt absent or oversized");
        byte[] bytes=Files.readAllBytes(receiptFile);if(!fingerprint.equals(sha256(bytes)))throw new IllegalStateException("D022 raw receipt SHA differs from ledger");
        JsonNode receipt=JobDefinitionJson.mapper().readTree(bytes);String start=from.format(DateTimeFormatter.BASIC_ISO_DATE),end=to.format(DateTimeFormatter.BASIC_ISO_DATE);
        if(!"tushare".equals(receipt.path("sourceKind").asText())||!"index_monthly".equals(receipt.path("endpoint").asText())
                ||!code.equals(receipt.path("providerCode").asText())||!from.toString().equals(receipt.path("from").asText())
                ||!to.toString().equals(receipt.path("to").asText())||!observed.toString().equals(receipt.path("observedAt").asText())
                ||!JobDefinitionJson.mapper().valueToTree(SOURCE_FIELDS).equals(receipt.path("fields"))
                ||!code.equals(receipt.path("parameters").path("ts_code").asText())
                ||!start.equals(receipt.path("parameters").path("start_date").asText())||!end.equals(receipt.path("parameters").path("end_date").asText())
                ||!receipt.path("sourceComplete").asBoolean(false)||!receipt.path("rawRows").isArray()
                ||receipt.path("returnedRows").asInt(-1)!=receipt.path("rawRows").size())
            throw new IllegalStateException("D022 receipt scope or completion differs from frozen request");
        if((receipt.path("rawRows").isEmpty())!=(slice.state()==SyncRunState.VERIFIED_EMPTY)
                ||(receipt.path("rawRows").isEmpty())!=(runEntry.state()==SyncRunState.VERIFIED_EMPTY))
            throw new IllegalStateException("D022 empty status differs from source receipt");
        String layer=receipt.path("layer").asText(),bucket=receipt.path("bucket").asText();
        if(layer.isBlank()||bucket.isBlank())throw new IllegalStateException("D022 derived index taxonomy missing from source receipt");
        long observedMicros=Math.addExact(Math.multiplyExact(observed.getEpochSecond(),1_000_000L),observed.getNano()/1_000L);
        var expected=new TreeMap<String,Map<String,Object>>();
        for(JsonNode row:receipt.path("rawRows")){
            if(!code.equals(row.path("ts_code").asText())||!row.path("trade_date").isTextual()||!row.path("trade_date").asText().matches("[0-9]{8}"))
                throw new IllegalStateException("D022 raw source key invalid");
            LocalDate date=LocalDate.parse(row.path("trade_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
            if(date.isBefore(from)||date.isAfter(to))throw new IllegalStateException("D022 raw source date outside frozen range");
            Map<String,Object> values=new LinkedHashMap<>();values.put("ts_code",code);values.put("tradeDate",date);
            for(String metric:METRICS){JsonNode value=row.get(metric);if(value==null||(!value.isNull()&&(!value.isNumber()||!Double.isFinite(value.doubleValue()))))throw new IllegalStateException("Invalid D022 source metric "+metric);values.put(metric,value.isNull()?null:value.doubleValue());}
            values.put("layer",layer);values.put("bucket",bucket);values.put("updateTimeMicros",observedMicros);
            if(expected.putIfAbsent(code+"|"+date,values)!=null)throw new IllegalStateException("Duplicate D022 source natural key");
        }
        String sql="SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,close,open,high,low,pre_close,change,pct_chg,vol,amount,layer,bucket,cast(update_time AS long) AS update_time_micros FROM \""+table+"\" WHERE ts_code=? AND trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT 1001";
        long lo=Math.multiplyExact(from.toEpochDay(),86_400_000_000L),hi=Math.multiplyExact(to.plusDays(1).toEpochDay(),86_400_000_000L);
        var actual=jdbc.queryForList(sql,code,lo,hi);if(actual.size()>1000)throw new IllegalStateException("D022 readback exceeds one thousand source rows");
        var found=new HashSet<String>();int mismatch=0,duplicates=0,missing=0,extra=0;
        for(var row:actual){Object d=row.get("trade_date_micros");if(!(d instanceof Number dn))throw new IllegalStateException("D022 physical trade_date is invalid");
            LocalDate date=LocalDate.ofEpochDay(Math.floorDiv(dn.longValue(),86_400_000_000L));String key=code+"|"+date;Map<String,Object> want=expected.get(key);
            if(!found.add(key)){duplicates++;continue;}if(want==null){extra++;continue;}
            if(!code.equals(row.get("ts_code"))||!Objects.equals(want.get("layer"),row.get("layer"))||!Objects.equals(want.get("bucket"),row.get("bucket"))
                    ||!Objects.equals(want.get("updateTimeMicros"),((Number)row.get("update_time_micros")).longValue())){mismatch++;continue;}
            for(String metric:METRICS){Object actualValue=row.get(metric);Double expectedValue=(Double)want.get(metric);
                if(expectedValue==null?actualValue!=null:!(actualValue instanceof Number n)||Double.doubleToLongBits(expectedValue)!=Double.doubleToLongBits(n.doubleValue()))mismatch++;}
        }
        missing=(int)expected.keySet().stream().filter(key->!found.contains(key)).count();
        if(actual.size()!=expected.size()||mismatch!=0||duplicates!=0||missing!=0||extra!=0)
            throw new IllegalStateException("D022 independent 14-column readback mismatch rows="+actual.size()+" expected="+expected.size()+" mismatches="+mismatch+" duplicates="+duplicates+" missing="+missing+" extra="+extra);
        var publications=new ReferencePublicationJournal(ledgerPath,"index_monthly");var publication=publications.findForRun(runId);
        if(expected.isEmpty()){
            if(publication.isPresent())throw new IllegalStateException("D022 empty no-op run must not publish a table replacement");
            var current=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
            if(current.size()!=1||!(current.getFirst().get("id") instanceof Number id)||!(current.getFirst().get("directoryName") instanceof String dir)
                    ||!params.path("physicalTargetId").asText().equals(StaticTargetIdentity.identify(jdbc,table,id.longValue(),dir)))
                throw new IllegalStateException("D022 empty run physical target differs from its frozen identity");
        }else{
            var p=publication.orElseThrow(()->new IllegalStateException("D022 nonempty run lacks publication journal"));
            if(p.state()!=ReferencePublicationJournal.State.VERIFIED||!table.equals(p.intent().target()))throw new IllegalStateException("D022 publication not VERIFIED for isolated target");
            var ids=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
            JsonNode scope=JobDefinitionJson.mapper().readTree(p.intent().scope());
            if(ids.size()!=1||!(ids.getFirst().get("id") instanceof Number id)||!(ids.getFirst().get("directoryName") instanceof String dir)
                    ||id.longValue()!=p.intent().replacementId()||!dir.equals(scope.path("stageDirectory").asText()))
                throw new IllegalStateException("D022 published target physical identity differs from journal");
        }
        return Map.ofEntries(Map.entry("status","MATCHED"),Map.entry("runId",runId),Map.entry("rows",actual.size()),
                Map.entry("expectedRows",expected.size()),Map.entry("comparedColumns",COLUMNS),Map.entry("duplicates",duplicates),
                Map.entry("missing",missing),Map.entry("extra",extra),Map.entry("mismatches",mismatch),
                Map.entry("formalTableMutated",false),Map.entry("passed",true));
    }
    private static void assertSchema(JdbcTemplate jdbc,String table){
        var rows=jdbc.queryForList("SELECT \"column\",\"type\",\"upsertKey\" FROM table_columns('"+table+"')");
        Map<String,String> types=new HashMap<>();Set<String> upsert=new HashSet<>();for(var row:rows){types.put(row.get("column").toString(),row.get("type").toString());if(Boolean.TRUE.equals(row.get("upsertKey")))upsert.add(row.get("column").toString());}
        Map<String,String> wanted=Map.ofEntries(Map.entry("ts_code","SYMBOL"),Map.entry("trade_date","TIMESTAMP"),Map.entry("close","DOUBLE"),Map.entry("open","DOUBLE"),Map.entry("high","DOUBLE"),Map.entry("low","DOUBLE"),Map.entry("pre_close","DOUBLE"),Map.entry("change","DOUBLE"),Map.entry("pct_chg","DOUBLE"),Map.entry("vol","DOUBLE"),Map.entry("amount","DOUBLE"),Map.entry("layer","SYMBOL"),Map.entry("bucket","SYMBOL"),Map.entry("update_time","TIMESTAMP"));
        if(!wanted.equals(types)||!upsert.isEmpty())throw new IllegalStateException("D022 isolated schema must have exact 14 columns and DEDUP=false");
    }
    private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
