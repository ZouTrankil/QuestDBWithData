import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Read-only SQLite audit. No Spring initialization, migration, writer or provider. */
public class ReadPersistentBatchAudit {
    static Connection open(Path path)throws Exception {
        var db=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath().normalize().toUri().toASCIIString()+"?mode=ro");
        try(var s=db.createStatement()){s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout=5000");}return db;
    }
    static List<Map<String,Object>> query(Connection db,String sql,String instance)throws Exception {
        var result=new ArrayList<Map<String,Object>>();try(var s=db.prepareStatement(sql)){
            if(instance!=null)s.setString(1,instance);try(var rs=s.executeQuery()){
                var columns=rs.getMetaData();while(rs.next()){
                    if(result.size()>=1000)throw new IllegalStateException("Audit row budget exceeded");
                    var row=new LinkedHashMap<String,Object>();for(int i=1;i<=columns.getColumnCount();i++)row.put(columns.getColumnLabel(i),rs.getObject(i));result.add(row);
                }
            }
        }return result;
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!args[2].matches("[a-f0-9]{64}"))throw new IllegalArgumentException("source ledger, batch ledger, instance, output required");
        var output=new LinkedHashMap<String,Object>();output.put("observedAtUtc",Instant.now().toString());output.put("readOnly",true);output.put("instance",args[2]);
        try(var db=open(Path.of(args[0]))){
            output.put("syncRunCount",query(db,"SELECT count(*) AS rows FROM sync_runs",null));
            output.put("syncRunsByOwner",query(db,"SELECT job_id,count(*) AS runs FROM sync_runs GROUP BY job_id ORDER BY job_id",null));
            output.put("recentSyncRuns",query(db,"SELECT r.id,r.job_id,r.job_version,r.logical_date,r.target_id,e.state,e.revision,e.updated_at FROM sync_runs r JOIN sync_entries e ON r.id=e.id ORDER BY e.updated_at DESC,r.id DESC LIMIT 30",null));
            output.put("nativePublications",query(db,"SELECT id,dataset,run_id,state,revision,updated_at,intent_json FROM reference_publications WHERE dataset IN ('market_sentiment_daily','regime_features_monitor_daily','backtest_daily') ORDER BY dataset,run_id",null));
        }
        try(var db=open(Path.of(args[1]))){
            output.put("businessInstance",query(db,"SELECT * FROM business_instance WHERE instance_id=?",args[2]));
            output.put("stages",query(db,"SELECT * FROM stage_result WHERE instance_id=? ORDER BY stage",args[2]));
            output.put("recoveryBudget",query(db,"SELECT * FROM post_close_recovery_attempt WHERE instance_id=?",args[2]));
            output.put("batchExecutions",query(db,"SELECT e.JOB_EXECUTION_ID,e.JOB_INSTANCE_ID,e.CREATE_TIME,e.START_TIME,e.END_TIME,e.STATUS,e.EXIT_CODE,e.EXIT_MESSAGE FROM BATCH_JOB_EXECUTION e JOIN BATCH_JOB_EXECUTION_PARAMS p ON e.JOB_EXECUTION_ID=p.JOB_EXECUTION_ID WHERE p.PARAMETER_NAME='instance' AND p.PARAMETER_VALUE=? ORDER BY e.JOB_EXECUTION_ID",args[2]));
        }
        var path=Path.of(args[3]);Files.createDirectories(path.toAbsolutePath().getParent());new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),output);
        System.out.println("Read-only persistent Batch and sync ledger audit captured");
    }
}
