import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MoneyflowHsgtMapper;
import com.zoutrankil.data.repository.MoneyflowHsgtWritePort;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Read-only audit artifact: existing JSON receipts + SELECT-only HTTP. No Spring, provider or writer. */
public class AuditHsgtRuntime {
    static final ObjectMapper JSON=JobDefinitionJson.mapper();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static final List<String> FIELDS=List.of("ggt_ss","ggt_sz","hgt","sgt","north_money","south_money");
    static JsonNode sql(String query)throws Exception {
        var uri=URI.create("http://localhost:9000/exec?query="+URLEncoder.encode(query,StandardCharsets.UTF_8));
        var r=HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()!=200)throw new IllegalStateException("Read-only SQL failed: HTTP "+r.statusCode());
        JsonNode node=JSON.readTree(r.body());if(node.has("error"))throw new IllegalStateException("Read-only SQL returned an error");return node;
    }
    static JsonNode clock(String table)throws Exception {
        return sql("SELECT t.id,t.directoryName,t.walEnabled,t.designatedTimestamp,t.partitionBy,w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='"+table+"'").path("dataset");
    }
    static void require(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String fingerprint(List<MoneyflowHsgt> rows)throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");
        for(var row:rows){hash.update(MoneyflowHsgtWritePort.CODEC.canonicalBytes(row));hash.update((byte)'\n');}
        return HexFormat.of().formatHex(hash.digest());
    }
    static List<MoneyflowHsgt> rows(String table)throws Exception {
        var response=sql("SELECT cast(trade_date AS LONG) trade_micros,ggt_ss,ggt_sz,hgt,sgt,north_money,south_money FROM \""+table+"\" ORDER BY trade_date LIMIT 100001");
        var result=new ArrayList<MoneyflowHsgt>();var seen=new HashSet<LocalDate>();
        for(JsonNode row:response.path("dataset")){
            require(row.size()==7&&row.get(0).isIntegralNumber(),"Typed complete seven-field row required");
            long micros=row.get(0).longValue();require(Math.floorMod(micros,86400000000L)==0,"UTC-midnight trade_date required");
            LocalDate date=LocalDate.ofEpochDay(Math.floorDiv(micros,86400000000L));require(seen.add(date),"Duplicate natural business date");
            var values=new LinkedHashMap<String,Object>();values.put("trade_date",date);
            for(int n=0;n<FIELDS.size();n++){JsonNode v=row.get(n+1);require(v.isNull()||v.isNumber()&&Double.isFinite(v.doubleValue()),"Invalid finite or nullable physical metric");values.put(FIELDS.get(n),v.isNull()?null:v.doubleValue());}
            result.add(new MoneyflowHsgtMapper().fromValues(new DatasetValues(values)));
        }
        require(response.path("count").asInt()==result.size()&&result.size()<100001,"Full table exceeds audit bound");return List.copyOf(result);
    }
    static Map<String,Object> layout(String table,long expectedId,String expectedDirectory)throws Exception {
        JsonNode clocks=clock(table);require(clocks.size()==1,"One physical table required");JsonNode c=clocks.get(0);
        require(c.get(0).asLong()==expectedId&&c.get(1).asText().equals(expectedDirectory),"Physical lineage differs from publication journal");
        require(c.get(2).asBoolean()&&c.get(3).asText().equals("trade_date")&&c.get(4).asText().equals("DAY")&&!c.get(7).asBoolean()
                &&c.get(5).asLong()==c.get(6).asLong()&&c.get(8).asLong()==0,"DAY/WAL and settled frontier required");
        var columns=sql("SELECT \"column\",type,upsertKey FROM table_columns('"+table+"')").path("dataset");
        var actual=new LinkedHashMap<String,String>();var keys=new ArrayList<String>();
        for(JsonNode row:columns){actual.put(row.get(0).asText(),row.get(1).asText());if(row.get(2).asBoolean())keys.add(row.get(0).asText());}
        var expected=new LinkedHashMap<String,String>();expected.put("trade_date","TIMESTAMP");for(String f:FIELDS)expected.put(f,"DOUBLE");
        require(actual.equals(expected)&&keys.equals(List.of("trade_date")),"Exact seven-field schema and trade_date DEDUP required");
        return Map.of("table",table,"clock",clocks,"columns",columns,"dedup",true,"dedupKeys",keys);
    }
    static void exact(List<MoneyflowHsgt> a,List<MoneyflowHsgt> b){
        require(a.size()==b.size(),"Row count differs");var mapper=new MoneyflowHsgtMapper();
        for(int n=0;n<a.size();n++){
            require(a.get(n).key().equals(b.get(n).key()),"Business date differs");var av=mapper.values(a.get(n));var bv=mapper.values(b.get(n));
            for(String f:FIELDS){Double x=av.get(f,Double.class),y=bv.get(f,Double.class);require(x==null?y==null:y!=null&&Double.doubleToRawLongBits(x)==Double.doubleToRawLongBits(y),"IEEE-754 source or outside field differs");}
        }
    }
    public static void main(String[]args)throws Exception {
        Path ledgerDump=Path.of(args[0]).toAbsolutePath(),evidence=Path.of(args[1]).toRealPath(),output=Path.of(args[2]).toAbsolutePath();
        JsonNode ledger=JSON.readTree(Files.readAllBytes(ledgerDump));JsonNode run=ledger.path("run").get(0);String runId=run.path("id").asText();
        var states=new ArrayList<String>();for(JsonNode e:ledger.path("entries"))if(e.path("kind").asText().equals("RUN"))states.add(e.path("state").asText());
        require(states.equals(List.of("VERIFIED")),"Owning sync run must be VERIFIED");require(ledger.path("publications").size()==1,"Exactly one publication required");
        JsonNode pub=ledger.path("publications").get(0);require(pub.path("state").asText().equals("VERIFIED"),"Publication must be VERIFIED");
        JsonNode intent=pub.path("intent_json_parsed"),scope=JSON.readTree(intent.path("scope").asText());require(scope.path("dedup").asBoolean(),"Journal formal layout must retain DEDUP");
        Path stageReceipt=Path.of(scope.path("stageReceipt").asText()).toRealPath();require(stageReceipt.startsWith(evidence)&&sha(Files.readAllBytes(stageReceipt)).equals(scope.path("stageReceiptFingerprint").asText()),"Stage receipt path/SHA differs");
        JsonNode stage=JSON.readTree(Files.readAllBytes(stageReceipt));require(stage.path("dedup").asBoolean()&&stage.path("sourceComplete").asBoolean()&&stage.path("runId").asText().equals(runId),"Verified stage source/layout differs");
        String table=intent.path("target").asText(),backup=intent.path("backup").asText();require(table.equals("moneyflow_hsgt")&&backup.matches("java_d027_moneyflow_hsgt_backup_[0-9a-f]{32}"),"Formal target and exact backup required");
        var formalLayout=layout(table,intent.path("replacementId").asLong(),scope.path("replacementDirectory").asText());
        var backupLayout=layout(backup,intent.path("originalId").asLong(),intent.path("originalDirectory").asText());
        JsonNode formalClock=clock(table),backupClock=clock(backup);var formal=rows(table);var old=rows(backup);
        require(clock(table).equals(formalClock)&&clock(backup).equals(backupClock),"Physical frontier changed during full audit");
        LocalDate from=LocalDate.parse(stage.path("windowFrom").asText()),to=LocalDate.parse(stage.path("windowTo").asText());
        var outside=formal.stream().filter(r->r.tradeDate().isBefore(from)||r.tradeDate().isAfter(to)).toList();
        var oldOutside=old.stream().filter(r->r.tradeDate().isBefore(from)||r.tradeDate().isAfter(to)).toList();
        var window=formal.stream().filter(r->!r.tradeDate().isBefore(from)&&!r.tradeDate().isAfter(to)).toList();exact(outside,oldOutside);
        require(fingerprint(formal).equals(intent.path("afterFingerprint").asText())&&fingerprint(old).equals(intent.path("beforeFingerprint").asText()),"Full formal/backup canonical SHA differs from journal");
        require(fingerprint(outside).equals(stage.path("preservedOutside").path("fingerprint").asText())&&outside.size()==stage.path("preservedOutside").path("rows").asInt(),"Outside SHA/count differs");
        var source=new ArrayList<MoneyflowHsgt>();var refs=new ArrayList<Map<String,Object>>();
        for(JsonNode ref:stage.path("sourceReceipts")){
            Path p=Path.of(ref.path("responseEvidence").asText()).toRealPath();String hash=sha(Files.readAllBytes(p));require(p.startsWith(evidence)&&hash.equals(ref.path("sourceFingerprint").asText()),"Source receipt path/SHA differs");
            JsonNode body=JSON.readTree(Files.readAllBytes(p));require(body.path("sourceComplete").asBoolean()&&body.path("returnedRows").asInt()==body.path("rawRows").size()&&body.path("endpoint").asText().equals("moneyflow_hsgt"),"Source completion/count differs");
            for(JsonNode raw:body.path("rawRows")){var values=new LinkedHashMap<String,JsonNode>();raw.fields().forEachRemaining(f->values.put(f.getKey(),f.getValue()));var mapper=new MoneyflowHsgtMapper();source.add(mapper.fromSource(mapper.dto(values)));}
            refs.add(Map.of("file",p.toString(),"sha256",hash,"rows",body.path("returnedRows").asInt(),"parameters",body.path("parameters")));
        }
        source.sort(Comparator.comparing(MoneyflowHsgt::tradeDate));exact(source,window);
        require(source.size()==3&&source.size()==stage.path("sourceRows").asInt()&&fingerprint(window).equals(stage.path("authoritativeWindow").path("fingerprint").asText()),"Authoritative source window differs");
        require(sql("SELECT id FROM tables() WHERE table_name='"+intent.path("stage").asText()+"'").path("count").asInt()==0,"Published stage must be renamed, not duplicated");
        var report=new LinkedHashMap<String,Object>();report.put("observedAtUtc",Instant.now());report.put("readOnly",true);report.put("providerCalls",0);report.put("dbWrites",0);report.put("status","VERIFIED");report.put("runId",runId);
        report.put("publicationId",intent.path("id").asText());report.put("runState","VERIFIED");report.put("publicationState","VERIFIED");report.put("range",Map.of("from",from,"to",to));
        report.put("formal",formalLayout);report.put("backup",backupLayout);report.put("stageRenamedToFormal",true);report.put("stageIdentity",stage.path("stageAfter").path("identity"));report.put("stageProofSha256",sha(Files.readAllBytes(stageReceipt)));
        report.put("fullRows",formal.size());report.put("backupRows",old.size());report.put("fullCanonicalSha256",fingerprint(formal));report.put("backupCanonicalSha256",fingerprint(old));report.put("outsideRows",outside.size());report.put("outsideCanonicalSha256",fingerprint(outside));report.put("outsideComparedFieldValues",outside.size()*7);report.put("outsideIeeeExact",true);
        report.put("sourceRows",source.size());report.put("sourceComparedFieldValues",source.size()*7);report.put("sourceIeeeExact",true);report.put("sourceCanonicalSha256",fingerprint(source));report.put("sourceReceipts",refs);report.put("duplicateBusinessDates",0);report.put("invalidRequiredDatesOrInfiniteValues",0);report.put("sourceWindow",source.stream().map(r->new MoneyflowHsgtMapper().values(r).asMap()).toList());
        report.put("ledgerEvidence",ledgerDump.toString());report.put("ledgerEvidenceSha256",sha(Files.readAllBytes(ledgerDump)));
        Files.createDirectories(output.getParent());Files.writeString(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));System.out.println("HSGT read-only audit VERIFIED: formal="+formal.size()+", outside="+outside.size()+", source="+source.size());
    }
}
