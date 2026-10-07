import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.domain.JobDefinitionJson;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Actual read-only acceptance evidence collector; never invokes a provider or a writer. */
public class JavaSyncLedgerReadAudit {
    public static void main(String[] args) throws Exception {
        Path ledgerPath=Path.of(args[0]).toAbsolutePath().normalize();
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);
        var json=JobDefinitionJson.mapper();
        var jobs=Set.of("data.stk_factor","data.daily_basic","data.stk_limit","data.moneyflow","data.etf_factor");
        var runs=new ArrayList<Map<String,Object>>();
        String after=null;
        while(true){
            var summaries=ledger.history(null,after,1000);
            for(var summary:summaries){
                if(!jobs.contains(summary.jobId()))continue;
                var saved=ledger.getRun(summary.id());
                var item=new LinkedHashMap<String,Object>();
                item.put("summary",summary);
                item.put("frozen",json.readTree(saved.frozenJson()));
                item.put("runPayload",json.readTree(ledger.get(summary.id()).payloadJson()));
                var entries=new ArrayList<Map<String,Object>>();
                for(var entry:ledger.entries(summary.id(),null,1000)){
                    var child=new LinkedHashMap<String,Object>();
                    child.put("entry",entry);
                    child.put("payload",json.readTree(entry.payloadJson()));
                    child.put("events",ledger.events(entry.id(),-1,100));
                    entries.add(child);
                }
                item.put("entries",entries);runs.add(item);
            }
            if(summaries.size()<1000)break;
            after=summaries.getLast().id();
        }
        var report=new LinkedHashMap<String,Object>();
        report.put("observedAtUtc",Instant.now());report.put("readOnly",true);
        report.put("providerRequests",0);report.put("databaseWrites",0);
        report.put("ledgerPath",ledgerPath.toString());report.put("runs",runs);
        Files.writeString(Path.of(args[1]),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Read-only ledger runs captured: "+runs.size());
    }
}
