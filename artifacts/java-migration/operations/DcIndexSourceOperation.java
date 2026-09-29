import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class DcIndexSourceOperation {
 public static void main(String[] args)throws Exception {
  Path root=Path.of("artifacts/java-migration/D023/provider-diagnostic/"+UUID.randomUUID().toString().replace("-","")).toAbsolutePath();Files.createDirectories(root);
  var report=new LinkedHashMap<String,Object>();report.put("startedAt",Instant.now().toString());report.put("task","D023");report.put("sourceDate","2026-09-17");report.put("formalTableMutated",false);report.put("questdbWrites",0);
  var app=new SpringApplication(local.market.DcIndexOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);System.out.println("Source evidence: "+root);
  try(var c=app.run("list-sync-jobs")){
   var page=new DcIndexSource(c.getBean(TusharePageService.class),root.resolve("source")).fetch(LocalDate.of(2026,9,17),()->false);
   report.put("sourceRows",page.rows().size());report.put("receipt",page.responseEvidence());report.put("sourceFingerprint",page.sourceFingerprint());report.put("status","SOURCE_CAPTURED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("source-operation.json"),JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
