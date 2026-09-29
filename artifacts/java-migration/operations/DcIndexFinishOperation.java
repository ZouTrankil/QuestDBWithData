import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class DcIndexFinishOperation {
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();String run=args[1];var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
  if(!table.matches("java_d023_dc_index_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned D023 target required");
  var report=new LinkedHashMap<String,Object>();report.put("task","D023");report.put("table",table);report.put("ledger",ledger.toString());report.put("runId",run);report.put("writerStopped",true);report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);
  var app=new SpringApplication(local.market.DcIndexOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("dc-finish",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.dc-index-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(DcIndexJobService.class);var durable=SyncRunLedger.openReadOnly(ledger);report.put("stateBefore",durable.get(run).state());report.put("before",DcIndexAcceptanceOperation.snapshot(jdbc,table));
   service.finishPublication(run,true);report.put("stateAfter",durable.get(run).state());if(durable.get(run).state()!=SyncRunState.VERIFIED)throw new IllegalStateException("D023 recovered run not VERIFIED");report.put("independentReadback",DcIndexIndependentReadback.verify(jdbc,ledger,table,run));report.put("after",DcIndexAcceptanceOperation.snapshot(jdbc,table));report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D023-finish-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
