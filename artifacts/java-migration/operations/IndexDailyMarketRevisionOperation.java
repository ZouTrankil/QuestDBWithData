import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class IndexDailyMarketRevisionOperation {
 public static void main(String[] args)throws Exception {
  var json=JobDefinitionJson.mapper();Path input=Path.of(args[0]).toAbsolutePath();var a=json.readTree(Files.readAllBytes(input));Path ledger=Path.of(a.path("ledger").asText());String table=a.path("table").asText();
  if(!"VERIFIED".equals(a.path("result").asText())||!table.matches("java_d019_index_daily_market_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned acceptance target required");
  var report=new LinkedHashMap<String,Object>();report.put("task","D019");report.put("table",table);report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");var routes=new ArrayList<Object>();report.put("routes",routes);
  var app=new SpringApplication(local.market.IndexDailyMarketOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("index-revision",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.index-daily-market-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(IndexDailyMarketJobService.class);var logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));
   for(var route:a.path("routes")){
    String code=route.path("code").asText(),other=code.equals("000300.SH")?"801080.SI":"000300.SH";var otherBefore=IndexDailyMarketAcceptanceOperation.snapshot(jdbc,table,other);
    var r=new LinkedHashMap<String,Object>();routes.add(r);r.put("code",code);
    var window=a.path("requestWindows").path("initial");var backfill=service.plan(SyncJobDefinition.Mode.BACKFILL,code,LocalDate.parse(window.get(0).asText()),LocalDate.parse(window.get(1).asText()),logical);
    var revision=service.run(backfill);IndexDailyMarketAcceptanceOperation.require(revision);r.put("backfill",revision);r.put("backfillReadback",IndexDailyMarketAcceptanceOperation.compare(jdbc,ledger,table,revision));
    var end=LocalDate.parse(a.path("requestWindows").path("incrementalTo").asText());var plan=service.plan(SyncJobDefinition.Mode.INCREMENTAL,code,null,end,logical);r.put("incrementalPlan",plan);
    if(!end.equals(plan.checkpointBefore()))throw new IllegalStateException("Backfill changed incremental checkpoint");
    var inc=service.run(plan);IndexDailyMarketAcceptanceOperation.require(inc);r.put("incremental",inc);r.put("incrementalReadback",IndexDailyMarketAcceptanceOperation.compare(jdbc,ledger,table,inc));
    var beforeEmpty=IndexDailyMarketAcceptanceOperation.snapshot(jdbc,table,null);var closed=LocalDate.of(2026,9,27);var empty=service.run(service.plan(SyncJobDefinition.Mode.BACKFILL,code,closed,closed,logical));r.put("emptyWindow",empty);
    if(empty.state()!=SyncRunState.VERIFIED_EMPTY||empty.sourceRows()!=0||empty.verifiedRows()!=0)throw new IllegalStateException("Closed date was not verified empty");
    r.put("emptyReadback",IndexDailyMarketAcceptanceOperation.compare(jdbc,ledger,table,empty));
    if(!beforeEmpty.equals(IndexDailyMarketAcceptanceOperation.snapshot(jdbc,table,null)))throw new IllegalStateException("Empty source changed target");
    if(!otherBefore.equals(IndexDailyMarketAcceptanceOperation.snapshot(jdbc,table,other)))throw new IllegalStateException("Code-scoped run changed other index");
    r.put("otherCodeUnchanged",true);
   }
   report.put("wholeTarget",IndexDailyMarketAcceptanceOperation.snapshot(jdbc,table,null));report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(input.getParent().resolve("D019-revision-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
