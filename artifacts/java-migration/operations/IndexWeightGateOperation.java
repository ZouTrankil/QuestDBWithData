import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class IndexWeightGateOperation {
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var accepted=json.readTree(Files.readAllBytes(input));
  String table=accepted.path("table").asText();Path ledger=Path.of(accepted.path("ledger").asText());
  if(!accepted.path("result").asText().equals("VERIFIED")||!table.matches("java_d021_index_weight_[a-f0-9]{32}"))throw new IllegalArgumentException("Verified owned target required");
  var report=new LinkedHashMap<String,Object>();report.put("task","D021");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);
  var app=new SpringApplication(local.market.IndexWeightOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
  app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("weight-gate",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.index-weight-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(IndexWeightJobService.class);var logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));var before=IndexWeightAcceptanceOperation.snapshot(jdbc,table);report.put("before",before);
   long beforeRuns=runCount(ledger);var cli=NextMarketAcceptanceOperation.call(c.getBean(CommandLineRunner.class),new String[]{"run-index-weight-job","--logical-date",logical.toString()});report.put("notDueCli",cli);long afterRuns=runCount(ledger);
   report.put("runsBeforeNotDue",beforeRuns);report.put("runsAfterNotDue",afterRuns);
   if(!"NOT_DUE".equals(cli.path("status").asText())||cli.path("executed").asBoolean(true)||cli.path("dataVerified").asBoolean(true)||cli.path("sourceRequests").asInt(-1)!=0||beforeRuns!=afterRuns)throw new IllegalStateException("Not-due CLI performed work or claimed acceptance");
   var six=service.plan(null,null,null,logical.plusDays(6),false);var seven=service.plan(null,null,null,logical.plusDays(7),false);report.put("daySixPreview",six);report.put("daySevenPreview",seven);
   if(!six.notDue()||seven.notDue())throw new IllegalStateException("Seven-day calendar gate boundary differs");
   LocalDate closed=LocalDate.of(2026,9,27);var result=service.run(service.plan(SyncJobDefinition.Mode.BACKFILL,closed,closed,logical,false));report.put("closedWindow",result);
   if(result.state()!=SyncRunState.VERIFIED_EMPTY||result.sourceRows()!=0||result.verifiedRows()!=0)throw new IllegalStateException("Closed window was not verified empty");
   report.put("independentEmptyReadback",IndexWeightAcceptanceOperation.compare(jdbc,ledger,table,result));var after=IndexWeightAcceptanceOperation.snapshot(jdbc,table);report.put("after",after);
   if(!before.equals(after))throw new IllegalStateException("Empty window changed stored rows");report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D021-gate-empty-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
 static long runCount(Path ledger)throws Exception{try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+ledger);var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM sync_runs")){r.next();return r.getLong(1);}}
}
