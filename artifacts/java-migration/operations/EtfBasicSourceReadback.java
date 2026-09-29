import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Independent raw-field comparison; never calls EtfBasicMapper or the typed readback port. */
class EtfBasicSourceReadback {
    static final List<String> FIELDS=List.of("ts_code","name","management","custodian","fund_type",
        "found_date","due_date","list_date","issue_date","delist_date","issue_amount","m_fee","c_fee",
        "duration_year","p_value","min_amount","exp_return","benchmark","status","invest_type","type",
        "trustee","purc_startdate","redm_startdate","market");
    static final Set<String> DATES=Set.of("found_date","due_date","list_date","issue_date","delist_date","purc_startdate","redm_startdate");
    static final Set<String> NUMBERS=Set.of("issue_amount","m_fee","c_fee","duration_year","p_value","min_amount","exp_return");
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static Map<String,Object> verify(JdbcTemplate jdbc,Path ledgerPath,String table,String runId)throws Exception{
        if(!table.matches("java_d013_etf_basic_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned D013 target required");
        var json=JobDefinitionJson.mapper();var ledger=SyncRunLedger.openReadOnly(ledgerPath);
        if(ledger.get(runId).state()!=SyncRunState.VERIFIED)throw new IllegalStateException("Verified run required");
        var frozen=json.readTree(ledger.getRun(runId).frozenJson());
        Instant observed=Instant.parse(frozen.path("parameters").path("observedAt").asText());
        long observedMicros=Math.addExact(Math.multiplyExact(observed.getEpochSecond(),1_000_000L),observed.getNano()/1000);
        Path root=ledgerPath.toAbsolutePath().getParent().resolve("sync-evidence").toRealPath();
        Path source=null;var slices=new ArrayList<JsonNode>();
        for(var entry:ledger.entries(runId,null,1000))if(entry.kind()==SyncRunLedger.Kind.SLICE){
            for(var event:ledger.events(entry.id(),-1,100))if(event.state()==SyncRunState.FETCHED){
                var fetched=json.readTree(event.payloadJson());slices.add(fetched);
                Path candidate=Path.of(fetched.path("responseEvidence").asText()).toRealPath();
                if(!candidate.startsWith(root)||source!=null&&!source.equals(candidate))throw new IllegalStateException("Invalid source receipt scope");
                source=candidate;
            }
        }
        if(source==null||Files.size(source)>64*1024*1024)throw new IllegalStateException("Bounded raw receipt required");
        byte[] bytes=Files.readAllBytes(source);var receipt=json.readTree(bytes);String sourceHash=hash(bytes);
        if(!receipt.path("endpoint").asText().equals("fund_basic")||!receipt.path("parameters").path("market").asText().equals("E")
            ||!receipt.path("sourceComplete").asBoolean()||receipt.path("sourceContractVersion").asInt()!=1
            ||!receipt.path("observedAt").asText().equals(observed.toString())
            ||!receipt.path("fields").equals(json.valueToTree(FIELDS)))throw new IllegalStateException("Source contract differs");
        var rows=receipt.path("rows");
        if(!rows.isArray()||rows.isEmpty()||rows.size()>=15000||receipt.path("responseRows").asInt()!=rows.size())throw new IllegalStateException("Incomplete source");
        var expected=new LinkedHashMap<String,Map<String,Object>>();var canonical=new ArrayList<byte[]>();
        int nullStatuses=0;
        for(var row:rows){
            var names=new TreeSet<String>();row.fieldNames().forEachRemaining(names::add);
            if(!names.equals(new TreeSet<>(FIELDS)))throw new IllegalStateException("Raw fields differ");
            var values=new LinkedHashMap<String,Object>();var normalized=new LinkedHashMap<String,Object>();
            for(String field:FIELDS){
                var node=row.get(field);Object value;
                if(node.isNull())value=null;
                else if(NUMBERS.contains(field))value=new java.math.BigDecimal(node.asText()).doubleValue();
                else if(DATES.contains(field)){
                    String text=node.asText();value=Set.of("","null","Null").contains(text)?null:LocalDate.parse(text,DateTimeFormatter.BASIC_ISO_DATE);
                }else value=node.textValue();
                normalized.put(field,value);
                values.put(field,value instanceof LocalDate day?day.format(DateTimeFormatter.BASIC_ISO_DATE):value);
            }
            if(!"E".equals(values.get("market")))throw new IllegalStateException("Source escaped market=E");
            String code=(String)values.get("ts_code");
            if(code==null||expected.containsKey(code))throw new IllegalStateException("Missing or duplicate source key");
            if(values.get("status")==null)nullStatuses++;
            values.put("timestamp",0L);values.put("update_time",observedMicros);expected.put(code,values);
            normalized.put("timestamp",new com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.TechnicalTimestamp(Instant.EPOCH,
                "Fixed 1970-01-01 carrier for QuestDB designated timestamp/dedup; not a source or business date"));
            normalized.put("update_time",observed);canonical.add(json.writeValueAsBytes(normalized));
        }
        int accounted=0;var chunks=new HashSet<Integer>();
        for(var slice:slices){
            int page=Integer.parseInt(slice.path("cursor").asText());if(!chunks.add(page)||page<1||page>2)throw new IllegalStateException("Invalid chunk");
            int from=(page-1)*10000,to=Math.min(canonical.size(),from+10000);
            if(from>=to||slice.path("returnedRows").asInt()!=to-from)throw new IllegalStateException("Invalid chunk count");
            var digest=MessageDigest.getInstance("SHA-256");digest.update(sourceHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);
            digest.update(Integer.toString(page).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            for(int i=from;i<to;i++){byte[] data=canonical.get(i);digest.update(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());digest.update(data);}
            if(!HexFormat.of().formatHex(digest.digest()).equals(slice.path("sourceFingerprint").asText()))throw new IllegalStateException("Raw receipt/chunk fingerprint mismatch");
            accounted+=to-from;
        }
        if(accounted!=expected.size())throw new IllegalStateException("Missing source chunks");
        String query="SELECT "+String.join(",",FIELDS.stream().map(f->"\""+f+"\"").toList())+
            ",cast(timestamp AS long) AS timestamp,cast(update_time AS long) AS update_time FROM "+table+" ORDER BY ts_code,timestamp LIMIT 15001";
        var actual=jdbc.queryForList(query);if(actual.size()!=expected.size())throw new IllegalStateException("Full table census differs");
        var seen=new HashSet<String>();int matches=0;
        for(var row:actual){
            String code=(String)row.get("ts_code");if(!seen.add(code)||!expected.containsKey(code))throw new IllegalStateException("Unexpected target key");
            var want=expected.get(code);
            for(String field:want.keySet()){
                Object left=want.get(field),right=row.get(field);
                boolean equal=left==null?right==null:left instanceof Double number&&right instanceof Number physical
                    ?Double.doubleToLongBits(number)==Double.doubleToLongBits(physical.doubleValue()):Objects.equals(left,right);
                if(!equal)throw new IllegalStateException("Source/target differs: "+code+"/"+field);
            }
            matches++;
        }
        var report=new LinkedHashMap<String,Object>();report.put("task","D013");report.put("runId",runId);report.put("status","MATCHED");
        report.put("sourceReceipt",source.toString());report.put("sourceFingerprint",sourceHash);report.put("sourceRows",rows.size());report.put("matches",matches);
        report.put("comparedColumns",new ArrayList<>(expected.values().iterator().next().keySet()));report.put("nullStatusRows",nullStatuses);
        report.put("mismatches",0);report.put("duplicateKeys",0);report.put("query",query);report.put("sourceReceiptCount",1);
        return report;
    }
}
