import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class IndexMonthlyRevisionOperation {
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
  if(!"VERIFIED".equals(a.path("result").asText())||!table.matches("java_d022_index_monthly_[a-f0-9]{32}"))throw new IllegalArgumentException("Verified owned monthly target required");
  var report=new LinkedHashMap<String,Object>();report.put("task","D022");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);
  var app=new SpringApplication(local.market.IndexMonthlyOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("monthly-revision",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.index-monthly-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(IndexMonthlyJobService.class);var logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));var routes=new ArrayList<Object>();report.put("routes",routes);
   for(var route:a.path("routes")){
    String code=route.path("providerCode").asText();var r=new LinkedHashMap<String,Object>();routes.add(r);r.put("code",code);var other=IndexMonthlyAcceptanceOperation.otherCodes(jdbc,table,code);r.put("otherCodesBefore",other);
    var before=IndexMonthlyCoverage.checkpoint(ledger,service.targetId(),code).orElseThrow();r.put("checkpointBefore",before);
    var backfill=service.run(service.plan(SyncJobDefinition.Mode.BACKFILL,code,LocalDate.of(2026,6,1),LocalDate.of(2026,7,31),logical));IndexMonthlyAcceptanceOperation.require(backfill);r.put("backfill",backfill);r.put("backfillReadback",IndexMonthlyIndependentReadback.verify(jdbc,ledger,table,backfill.runId()));
    var afterBackfill=IndexMonthlyCoverage.checkpoint(ledger,service.targetId(),code).orElseThrow();r.put("checkpointAfterBackfill",afterBackfill);if(!before.equals(afterBackfill))throw new IllegalStateException("Monthly backfill advanced checkpoint");
    var next=service.run(service.plan(null,code,null,LocalDate.of(2026,8,31),logical));IndexMonthlyAcceptanceOperation.require(next);r.put("incremental",next);r.put("incrementalReadback",IndexMonthlyIndependentReadback.verify(jdbc,ledger,table,next.runId()));
    var after=IndexMonthlyCoverage.checkpoint(ledger,service.targetId(),code).orElseThrow();r.put("checkpointAfterIncremental",after);if(!before.through().equals(after.through())||!before.anchor().equals(after.anchor()))throw new IllegalStateException("Monthly checkpoint range changed on same-end repeat");
    var beforeEmpty=IndexMonthlyAcceptanceOperation.snapshot(jdbc,table);var empty=service.run(service.plan(SyncJobDefinition.Mode.BACKFILL,code,LocalDate.of(1990,1,1),LocalDate.of(1990,1,31),logical));r.put("preInceptionEmpty",empty);
    if(empty.state()!=SyncRunState.VERIFIED_EMPTY||empty.sourceRows()!=0||empty.verifiedRows()!=0)throw new IllegalStateException("Pre-inception monthly window not empty");r.put("emptyReadback",IndexMonthlyIndependentReadback.verify(jdbc,ledger,table,empty.runId()));
    var afterEmpty=IndexMonthlyAcceptanceOperation.snapshot(jdbc,table);r.put("emptyTargetBefore",beforeEmpty);r.put("emptyTargetAfter",afterEmpty);if(!beforeEmpty.equals(afterEmpty))throw new IllegalStateException("Empty monthly source altered target");
    var otherAfter=IndexMonthlyAcceptanceOperation.otherCodes(jdbc,table,code);r.put("otherCodesAfter",otherAfter);if(!other.equals(otherAfter))throw new IllegalStateException("Monthly revision changed another code");
   }
   try{service.plan(SyncJobDefinition.Mode.BACKFILL,"000300.SH",logical.withDayOfMonth(1),logical.withDayOfMonth(logical.lengthOfMonth()),logical);throw new IllegalStateException("Incomplete current month accepted");}catch(IllegalArgumentException expected){report.put("incompleteMonthRejected",expected.getMessage());}
   report.put("finalSnapshot",IndexMonthlyAcceptanceOperation.snapshot(jdbc,table));report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D022-revision-empty-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
