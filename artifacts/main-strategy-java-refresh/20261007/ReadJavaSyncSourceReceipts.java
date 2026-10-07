import com.zoutrankil.data.domain.JobDefinitionJson;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Reads retained source receipts; makes no network request and initializes no application. */
public class ReadJavaSyncSourceReceipts {
    static String sha(byte[] bytes)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args)throws Exception {
        var json=JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
        var ledger=json.readTree(Files.readAllBytes(Path.of(args[0])));
        var records=new ArrayList<Map<String,Object>>();
        for(var run:ledger.path("runs")) {
            if(!run.path("summary").path("state").asText().equals("VERIFIED"))continue;
            String job=run.path("summary").path("jobId").asText();
            for(var entry:run.path("entries")) {
                if(!entry.path("entry").path("kind").asText().equals("SLICE"))continue;
                JsonNode fetched=null;
                for(var event:entry.path("events"))if(event.path("state").asText().equals("FETCHED")) {
                    fetched=json.readTree(event.path("payloadJson").asText());break;
                }
                if(fetched==null)throw new IllegalStateException("Verified slice lacks fetched receipt");
                Path source=Path.of(fetched.path("responseEvidence").asText()).toAbsolutePath().normalize();
                byte[] bytes=Files.readAllBytes(source);var document=json.readTree(bytes);
                String fileSha=sha(bytes),fingerprint=fileSha;
                var rows=document.has("rawRows")?document.path("rawRows"):document.path("rows");
                if(job.equals("data.daily_basic")) {
                    var canonical=new LinkedHashMap<String,Object>();
                    for(String name:List.of("endpoint","parameters","fields","tradeDate","rows"))canonical.put(name,document.path(name));
                    var version=document.path("receipt").path("sourceVersion");
                    if(!version.isMissingNode()&&!version.isNull())canonical.put("sourceVersion",version);
                    fingerprint=sha(json.writeValueAsBytes(canonical));
                }
                var keys=new HashSet<String>();int duplicateKeys=0,invalidCodes=0,wrongDates=0;
                String date=document.path("parameters").path("trade_date").asText();
                for(var row:rows) {
                    String code=row.path("ts_code").asText(),rowDate=row.path("trade_date").asText();
                    String codePattern=job.equals("data.etf_factor")?"[0-9]{6}\\.(SH|SZ|OF)":"[0-9]{6}\\.(SH|SZ|BJ)";
                    if(!code.matches(codePattern))invalidCodes++;
                    if(!rowDate.equals(date))wrongDates++;
                    if(!keys.add(code+"|"+rowDate))duplicateKeys++;
                }
                var verification=entry.path("payload").path("verification");
                var item=new LinkedHashMap<String,Object>();
                item.put("runId",run.path("summary").path("id"));item.put("jobId",job);
                item.put("sliceId",entry.path("entry").path("id"));item.put("state",entry.path("entry").path("state"));
                item.put("tradeDate",date);item.put("endpoint",document.path("endpoint"));
                item.put("sourceContractVersion",document.path("sourceContractVersion"));
                item.put("fieldCount",document.path("fields").size());item.put("fields",document.path("fields"));
                item.put("sourceRows",rows.size());item.put("ledgerFetchedRows",fetched.path("returnedRows"));
                item.put("verification",verification);item.put("sourceEvidence",source.toString());
                item.put("fileSha256",fileSha);item.put("calculatedSourceFingerprint",fingerprint);
                item.put("ledgerSourceFingerprint",fetched.path("sourceFingerprint"));
                boolean fingerprintMatches=fingerprint.equals(fetched.path("sourceFingerprint").asText())&&fingerprint.equals(verification.path("sourceFingerprint").asText());
                item.put("fingerprintMatches",fingerprintMatches);
                item.put("duplicateSourceKeys",duplicateKeys);item.put("invalidSourceCodes",invalidCodes);item.put("wrongSourceDates",wrongDates);
                item.put("completion",document.has("completion")?document.path("completion"):document.path("sourceComplete"));
                if(document.has("responseEvidence")) {
                    Path raw=Path.of(document.path("responseEvidence").asText()).toAbsolutePath().normalize();
                    item.put("rawResponseEvidence",raw.toString());item.put("rawResponseSha256",sha(Files.readAllBytes(raw)));
                }
                boolean passed=fingerprintMatches&&duplicateKeys==0&&invalidCodes==0&&wrongDates==0&&rows.size()==fetched.path("returnedRows").asInt()&&rows.size()==verification.path("matchedRows").asInt()&&verification.path("passed").asBoolean();
                item.put("passed",passed);records.add(item);
                if(!passed)throw new IllegalStateException("Retained source receipt audit failed: "+source);
            }
        }
        var report=new LinkedHashMap<String,Object>();report.put("observedAtUtc",Instant.now());report.put("providerRequests",0);report.put("databaseWrites",0);report.put("readOnly",true);report.put("receipts",records);
        Files.writeString(Path.of(args[1]),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Read-only retained source receipt audit passed: "+records.size());
    }
}
