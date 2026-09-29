import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.mapper.IndexDailyBasicMapper;
import com.zoutrankil.questdbwithdata.service.*;
import io.questdb.client.QuestDB;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;

class IndexDailyBasicRecoveryOperation {
 public static void main(String[] args)throws Exception {
  if(args.length!=1)throw new IllegalArgumentException("One verified acceptance report required");
  var json=JobDefinitionJson.mapper();Path input=Path.of(args[0]).toAbsolutePath();var accepted=json.readTree(Files.readAllBytes(input));
  String table=accepted.path("table").asText();Path root=input.getParent(),ledger=Path.of(accepted.path("ledger").asText());
  if(!"D020".equals(accepted.path("task").asText())||!"VERIFIED".equals(accepted.path("result").asText())||!table.matches("java_d020_index_daily_basic_[a-f0-9]{32}"))throw new IllegalArgumentException("Verified owned D020 target required");
  var app=new SpringApplication(local.market.IndexDailyBasicOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
  app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("index-recovery",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.index-daily-basic-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(IndexDailyBasicJobService.class);
   for(var route:accepted.path("routes")){
    String code=route.path("code").asText(),id=UUID.randomUUID().toString();Path evidence=root.resolve("sync-evidence/recovery-"+id);
    var report=new LinkedHashMap<String,Object>();report.put("task","D020");report.put("table",table);report.put("ledger",ledger.toString());report.put("code",code);report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
    try{
     var before=IndexDailyBasicAcceptanceOperation.snapshot(jdbc,table,null);report.put("before",before);
     var window=accepted.path("requestWindows").path("initial");
     var plan=service.plan(SyncJobDefinition.Mode.BACKFILL,code,LocalDate.parse(window.get(0).asText()),LocalDate.parse(window.get(1).asText()),LocalDate.now(ZoneId.of("Asia/Shanghai")));
     var adapter=new IndexDailyBasicSyncAdapter(new IndexDailyBasicSource(c.getBean(TusharePageService.class),new IndexDailyBasicMapper(),evidence.resolve("source")),new IndexDailyBasicWritePort(table,plan.targetId(),jdbc,c.getBean(QuestDB.class)),evidence);
     MarketRecoveryOperation.exercise("D020",table,jdbc,ledger,plan.request(),plan.targetId(),adapter,report);
     var after=IndexDailyBasicAcceptanceOperation.snapshot(jdbc,table,null);report.put("after",after);
     if(!before.equals(after))throw new IllegalStateException("Recovery changed full 12-column whole-target snapshot");
     report.put("status","VERIFIED");
    }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
    finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D020-recovery-"+code.replace('.','-')+"-"+id+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
   }
  }
 }
}
