import com.zoutrankil.data.domain.JobDefinitionJson;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Audit artifact only: never initializes the application or a writable ledger. */
public class ReadMarketSentimentLedger {
    static List<Map<String,Object>> query(Connection db,String sql,String run)throws Exception {
        var result=new ArrayList<Map<String,Object>>();
        var json=JobDefinitionJson.mapper();
        try(var statement=db.prepareStatement(sql)) {
            statement.setString(1,run);
            try(var rows=statement.executeQuery()) {
                var meta=rows.getMetaData();
                while(rows.next()) {
                    var item=new LinkedHashMap<String,Object>();
                    for(int i=1;i<=meta.getColumnCount();i++) {
                        String name=meta.getColumnLabel(i); Object value=rows.getObject(i);
                        item.put(name,value);
                        if(name.endsWith("_json")&&value!=null)item.put(name+"_parsed",json.readTree(value.toString()));
                    }
                    result.add(item);
                }
            }
        }
        return result;
    }
    public static void main(String[]args)throws Exception {
        Path ledger=Path.of(args[0]).toAbsolutePath().normalize();
        String run=args[1];var report=new LinkedHashMap<String,Object>();
        report.put("observedAtUtc",Instant.now());report.put("readOnly",true);report.put("ledger",ledger.toString());
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledger.toUri().toASCIIString()+"?mode=ro")) {
            try(var s=db.createStatement()){s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout=5000");}
            report.put("run",query(db,"SELECT * FROM sync_runs WHERE id=?",run));
            report.put("entries",query(db,"SELECT * FROM sync_entries WHERE run_id=? ORDER BY updated_at",run));
            report.put("events",query(db,"SELECT e.* FROM sync_events e JOIN sync_entries i ON e.entry_id=i.id WHERE i.run_id=? ORDER BY e.updated_at,e.revision",run));
            report.put("publications",query(db,"SELECT * FROM reference_publications WHERE run_id=?",run));
        }
        Files.writeString(Path.of(args[2]),JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Read-only market run and publication captured: "+run);
    }
}
