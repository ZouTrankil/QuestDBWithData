import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class IndexMonthlyFinishOperation {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]).toAbsolutePath(),ledger=root.resolve("sync-ledger.sqlite");String runId=args[1];var json=JobDefinitionJson.mapper();
  var original=json.readTree(Files.readAllBytes(root.resolve("D022-live-acceptance-failure.json")));String table=original.path("table").asText();
  if(!table.matches("java_d022_index_monthly_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned target required");
  var report=new LinkedHashMap<String,Object>();report.put("task","D022");report.put("runId",runId);report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("writerStopped",true);report.put("formalTableMutated",false);
  var app=new SpringApplication(local.market.IndexMonthlyOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
  app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("monthly-finish",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.index-monthly-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);report.put("before",IndexMonthlyAcceptanceOperation.snapshot(jdbc,table));
   var result=c.getBean(IndexMonthlyJobService.class).finishInterrupted(runId,true);report.put("recovery",result);
   report.put("independentReadback",IndexMonthlyIndependentReadback.verify(jdbc,ledger,table,runId));report.put("after",IndexMonthlyAcceptanceOperation.snapshot(jdbc,table));report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D022-finish-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
