import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Continue retained, actually written history after an independent-verifier route fix. */
class CompleteIndexWeightAcceptance {
 @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]).toAbsolutePath(),ledger=root.resolve("sync-ledger.sqlite");var json=JobDefinitionJson.mapper();
  Path prior=root.resolve("D021-live-acceptance-failure.json");
  var report=json.readValue(Files.readAllBytes(prior),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
  String table=(String)report.get("table");if(!table.matches("java_d021_index_weight_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned target required");
  var runs=(List<Object>)report.get("runs");var proofs=(List<Object>)report.get("independentReadbacks");
  if(runs.size()!=4||proofs.size()!=3)throw new IllegalStateException("Exactly three independently verified snapshots and one pending historical run required");
  report.put("priorVerifierFailure",report.remove("failure"));report.put("continuedAt",Instant.now().toString());
  var app=new SpringApplication(local.market.IndexWeightOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
  app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("weight-complete",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.index-weight-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(IndexWeightJobService.class);var logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));
   var history=json.convertValue(runs.getLast(),SyncJobRunner.Result.class);IndexWeightAcceptanceOperation.require(history);
   proofs.add(IndexWeightAcceptanceOperation.compare(jdbc,ledger,table,history));report.put("readbackAfterHistory",IndexWeightAcceptanceOperation.snapshot(jdbc,table));
   var gate=service.plan(null,null,null,logical,false);report.put("afterHistoryRefreshGate",gate);
   if(!gate.notDue()||!gate.nextRefreshDate().toString().equals(((Map<String,Object>)report.get("withinSevenDaysPlan")).get("nextRefreshDate")))throw new IllegalStateException("History altered snapshot gate");
   var refreshed=service.run(service.plan(SyncJobDefinition.Mode.SNAPSHOT,null,null,logical,true));IndexWeightAcceptanceOperation.require(refreshed);runs.add(refreshed);
   proofs.add(IndexWeightAcceptanceOperation.compare(jdbc,ledger,table,refreshed));report.put("readbackAfterRefresh",IndexWeightAcceptanceOperation.snapshot(jdbc,table));report.put("result","VERIFIED");
  }catch(Exception e){report.put("result","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("VERIFIED".equals(report.get("result"))?"D021-live-acceptance.json":"D021-complete-acceptance-failure.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
