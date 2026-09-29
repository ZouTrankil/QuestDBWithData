import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.mapper.IndexDailyMarketMapper;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class IndexDailyMarketSourceOperation {
 public static void main(String[] args)throws Exception {
  Path root=Path.of("artifacts/java-migration/D019/provider-diagnostic/"+UUID.randomUUID());Files.createDirectories(root);
  var report=new LinkedHashMap<String,Object>();report.put("databaseWrites",0);report.put("task","D019");
  var app=new SpringApplication(local.market.IndexDailyMarketOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
  try(var c=app.run("list-sync-jobs")){
   var source=new IndexDailyMarketSource(c.getBean(TusharePageService.class),new IndexDailyMarketMapper(),root.resolve("source"));
   var page=source.fetch("801080.SI",LocalDate.of(2026,9,24),LocalDate.of(2026,9,28),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
   report.put("rows",page.rows().size());report.put("status","SOURCE_COMPLETE");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{Files.writeString(root.resolve("source-operation.json"),JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
