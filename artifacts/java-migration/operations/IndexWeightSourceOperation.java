import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.mapper.IndexWeightMapper;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class IndexWeightSourceOperation {
 public static void main(String[] args)throws Exception {
  boolean write=args.length==1 && args[0].equals("--write");
  String id=UUID.randomUUID().toString().replace("-",""),table="java_d021_index_weight_"+id;Path root=Path.of(write?"artifacts/java-migration/market-live-"+id:"artifacts/java-migration/D021/provider-diagnostic/"+id).toAbsolutePath();Files.createDirectories(root);var report=new LinkedHashMap<String,Object>();report.put("task","D021");report.put("startedAt",Instant.now().toString());report.put("table",table);report.put("dataRowsWritten",0);report.put("formalTableMutated",false);
  var app=new SpringApplication(local.market.IndexWeightOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("weight-source-probe",Map.of("app.sync.ledger-path",root.resolve("sync-ledger.sqlite").toString(),"app.sync.index-weight-table",table,"app.tushare.concurrency",1))));
  System.out.println("Evidence directory: "+root);
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);jdbc.execute(IndexWeightDataset.createIsolatedTableSql(table));
   var plan=c.getBean(IndexWeightJobService.class).plan(null,null,null,LocalDate.now(ZoneId.of("Asia/Shanghai")),false);report.put("plan",plan);
   if(write){
    var result=c.getBean(IndexWeightJobService.class).run(plan);report.put("run",result);report.put("ledger",root.resolve("sync-ledger.sqlite").toString());report.put("targetId",plan.targetId());report.put("dataRowsWritten",result.verifiedRows());
    report.put("status",result.state()==SyncRunState.VERIFIED?"WRITE_VERIFIED_PENDING_INDEPENDENT_READBACK":"WRITE_INCOMPLETE");
   }else{
    var source=new IndexWeightSource(c.getBean(TusharePageService.class),new IndexWeightMapper(),root.resolve("source"),c.getBean(IndexWeightNameResolver.class));var result=source.fetch(plan.request(),()->false);
    report.put("calls",result.calls());report.put("sourceRows",result.sourceRows());report.put("status",result.complete()?"SOURCE_COMPLETE":"SOURCE_INCOMPLETE");
   }
   report.put("physicalRowsAfter",jdbc.queryForObject("SELECT count() FROM "+table,Long.class));
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("source-operation.json"),JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
