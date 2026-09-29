import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class CompleteDcIndexAcceptanceOperation {
 public static void main(String[] args)throws Exception {
  Path file=Path.of(args[0]).toAbsolutePath();var json=JobDefinitionJson.mapper();var original=json.readTree(Files.readAllBytes(file));
  String table=original.path("table").asText();if(!table.matches("java_d023_dc_index_[a-f0-9]{32}")||original.path("runs").size()!=1||original.path("independentReadbacks").size()!=1||!original.path("independentReadbacks").get(0).path("status").asText().equals("MATCHED"))throw new IllegalArgumentException("Exactly one independently accepted initial D023 run required");
  Path root=file.getParent(),ledger=Path.of(original.path("ledger").asText());var report=json.convertValue(original,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});report.remove("failure");report.remove("result");report.put("continuedFrom",file.toString());
  var app=new SpringApplication(local.market.DcIndexOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
  app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("dc-index-continuation",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.dc-index-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(DcIndexJobService.class);var logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));
   var runs=new ArrayList<Object>();original.path("runs").forEach(runs::add);var proofs=new ArrayList<Object>();original.path("independentReadbacks").forEach(proofs::add);var plans=new ArrayList<Object>();original.path("plans").forEach(plans::add);report.put("runs",runs);report.put("independentReadbacks",proofs);report.put("plans",plans);
   var baseline=DcIndexAcceptanceOperation.snapshot(jdbc,table);if(!json.valueToTree(baseline).equals(original.path("afterFirst")))throw new IllegalStateException("Initial D023 target changed before continuation");
   for(int i=0;i<2;i++){
    var p=service.planDetailed(null,null,LocalDate.of(2026,9,17),logical);plans.add(p);var r=service.run(p);DcIndexAcceptanceOperation.require(r);runs.add(r);proofs.add(DcIndexIndependentReadback.verify(jdbc,ledger,table,r.runId()));if(!baseline.equals(DcIndexAcceptanceOperation.snapshot(jdbc,table)))throw new IllegalStateException("D023 rerun changed target snapshot");
   }
   var p=service.planDetailed(null,null,LocalDate.of(2026,9,18),logical);plans.add(p);var r=service.run(p);DcIndexAcceptanceOperation.require(r);runs.add(r);proofs.add(DcIndexIndependentReadback.verify(jdbc,ledger,table,r.runId()));report.put("finalSnapshot",DcIndexAcceptanceOperation.snapshot(jdbc,table));report.put("result","VERIFIED");
  }catch(Exception e){report.put("result","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D023-live-acceptance"+("VERIFIED".equals(report.get("result"))?"":"-continuation-failure")+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
