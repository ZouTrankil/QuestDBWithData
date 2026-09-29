import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class DcIndexRevisionOperation {
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
  if(!a.path("result").asText().equals("VERIFIED")||!table.matches("java_d023_dc_index_[a-f0-9]{32}"))throw new IllegalArgumentException("Accepted isolated D023 target required");
  var report=new LinkedHashMap<String,Object>();report.put("task","D023");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);
  var app=new SpringApplication(local.market.DcIndexOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("dc-revision",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.dc-index-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(DcIndexJobService.class);var cal=new DcIndexTradingDates(c.getBean(ExchangeCalendarReadRepository.class));var before=DcIndexAcceptanceOperation.snapshot(jdbc,table);report.put("before",before);var checkpoint=DcIndexCoverage.checkpoint(ledger,service.targetId(),cal).orElseThrow();report.put("checkpointBefore",checkpoint);
   var plan=service.planDetailed(SyncJobDefinition.Mode.BACKFILL,LocalDate.of(2026,9,17),LocalDate.of(2026,9,17),LocalDate.now(ZoneId.of("Asia/Shanghai")));var r=service.run(plan);DcIndexAcceptanceOperation.require(r);report.put("backfill",r);report.put("backfillReadback",DcIndexIndependentReadback.verify(jdbc,ledger,table,r.runId()));
   var afterBackfill=DcIndexCoverage.checkpoint(ledger,service.targetId(),cal).orElseThrow();report.put("checkpointAfterBackfill",afterBackfill);if(!checkpoint.anchor().equals(afterBackfill.anchor())||!checkpoint.through().equals(afterBackfill.through()))throw new IllegalStateException("D023 backfill advanced checkpoint");
   var p=service.planDetailed(null,null,LocalDate.of(2026,9,18),LocalDate.now(ZoneId.of("Asia/Shanghai")));var next=service.run(p);DcIndexAcceptanceOperation.require(next);report.put("incremental",next);report.put("incrementalReadback",DcIndexIndependentReadback.verify(jdbc,ledger,table,next.runId()));
   var after=DcIndexAcceptanceOperation.snapshot(jdbc,table);report.put("after",after);if(!before.equals(after))throw new IllegalStateException("Stable historical D023 revision changed full target snapshot");report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D023-revision-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
