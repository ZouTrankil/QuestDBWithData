import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class MoneyflowDcRevisionOperation {
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
  if(!a.path("result").asText().equals("VERIFIED")||!table.matches("java_d026_moneyflow_dc_[a-f0-9]{32}"))throw new IllegalArgumentException("Accepted isolated D026 target required");
  var report=new LinkedHashMap<String,Object>();report.put("task","D026");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);
  var app=new SpringApplication(local.market.MoneyflowDcOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("moneyflow_dc-revision",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.moneyflow-dc-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(MoneyflowDcJobService.class);var cal=new MoneyflowDcTradingDates(c.getBean(ExchangeCalendarReadRepository.class));var before=MoneyflowDcAcceptanceOperation.snapshot(jdbc,table);report.put("before",before);var checkpoint=MoneyflowDcCoverage.checkpoint(ledger,service.targetId(),cal).orElseThrow();report.put("checkpointBefore",checkpoint);
   var plan=service.planDetailed(SyncJobDefinition.Mode.BACKFILL,LocalDate.of(2026,9,17),LocalDate.of(2026,9,17),LocalDate.now(ZoneId.of("Asia/Shanghai")));var r=service.run(plan);MoneyflowDcAcceptanceOperation.require(r);report.put("backfill",r);report.put("backfillReadback",MoneyflowDcIndependentReadback.verify(jdbc,ledger,table,r.runId()));
   var afterBackfill=MoneyflowDcCoverage.checkpoint(ledger,service.targetId(),cal).orElseThrow();report.put("checkpointAfterBackfill",afterBackfill);if(!checkpoint.anchor().equals(afterBackfill.anchor())||!checkpoint.through().equals(afterBackfill.through()))throw new IllegalStateException("D026 backfill advanced checkpoint");
   var p=service.planDetailed(null,null,LocalDate.of(2026,9,18),LocalDate.now(ZoneId.of("Asia/Shanghai")));var next=service.run(p);MoneyflowDcAcceptanceOperation.require(next);report.put("incremental",next);report.put("incrementalReadback",MoneyflowDcIndependentReadback.verify(jdbc,ledger,table,next.runId()));
   var after=MoneyflowDcAcceptanceOperation.snapshot(jdbc,table);report.put("after",after);if(!before.equals(after))throw new IllegalStateException("Stable historical D026 revision changed full target snapshot");report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D026-revision-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
