import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class MoneyflowDcSourceOperation {
 public static void main(String[] args)throws Exception {
 int offset=Integer.parseInt(args[0]);
  Path root=Path.of("artifacts/java-migration/D026/provider-diagnostic/"+UUID.randomUUID().toString().replace("-","")).toAbsolutePath();Files.createDirectories(root);
  var report=new LinkedHashMap<String,Object>();report.put("startedAt",Instant.now().toString());report.put("task","D026");report.put("sourceDate","2026-09-17");report.put("formalTableMutated",false);report.put("questdbWrites",0);
  var app=new SpringApplication(local.market.MoneyflowDcOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);System.out.println("Source evidence: "+root);
  try(var c=app.run("list-sync-jobs")){
   var page=c.getBean(com.zoutrankil.questdbwithdata.client.TushareClient.class).request(new com.zoutrankil.questdbwithdata.client.dto.TushareRequest("moneyflow_dc",Map.of("trade_date","20260917","limit",2000,"offset",offset),MoneyflowDcSource.FIELDS,30000));
   report.put("sourceRows",page.rows().size());report.put("offset",offset);report.put("limit",2000);report.put("fields",page.rows().isEmpty()?List.of():page.rows().getFirst().keySet());Files.writeString(root.resolve("raw-response.json"),JobDefinitionJson.mapper().writeValueAsString(page));report.put("status","DIAGNOSTIC_ONLY_NOT_ACCEPTED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("source-operation.json"),JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
