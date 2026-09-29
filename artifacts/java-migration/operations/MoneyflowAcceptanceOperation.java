import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;

class MoneyflowAcceptanceOperation {
 public static void main(String[] args)throws Exception {
  String id=UUID.randomUUID().toString().replace("-",""),table="java_d024_moneyflow_"+id;
  Path root=Path.of("artifacts/java-migration/market-live-"+id).toAbsolutePath(),ledger=root.resolve("sync-ledger.sqlite");Files.createDirectories(root);
  var report=new LinkedHashMap<String,Object>();report.put("task","D024");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
  var app=new SpringApplication(local.market.MoneyflowOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
  app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("moneyflow-acceptance",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.moneyflow-table",table,"app.tushare.concurrency",1))));
  System.out.println("Evidence directory: "+root);
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);jdbc.execute(MoneyflowDataset.createIsolatedTableSql(table));var service=c.getBean(MoneyflowJobService.class);var logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));report.put("targetId",service.targetId());
   var runs=new ArrayList<Object>();var proofs=new ArrayList<Object>();var plans=new ArrayList<Object>();report.put("runs",runs);report.put("independentReadbacks",proofs);report.put("plans",plans);
   var firstPlan=service.planDetailed(null,LocalDate.of(2026,9,17),LocalDate.of(2026,9,17),logical);plans.add(firstPlan);
   var first=service.run(firstPlan);require(first);runs.add(first);proofs.add(MoneyflowIndependentReadback.verify(jdbc,ledger,table,first.runId()));var baseline=snapshot(jdbc,table);report.put("afterFirst",baseline);
   for(int i=0;i<2;i++){
    var p=service.planDetailed(null,null,LocalDate.of(2026,9,17),logical);plans.add(p);var r=service.run(p);require(r);runs.add(r);proofs.add(MoneyflowIndependentReadback.verify(jdbc,ledger,table,r.runId()));
    if(!baseline.equals(snapshot(jdbc,table)))throw new IllegalStateException("D024 rerun changed target snapshot");
   }
   var increment=service.planDetailed(null,null,LocalDate.of(2026,9,18),logical);plans.add(increment);var next=service.run(increment);require(next);runs.add(next);proofs.add(MoneyflowIndependentReadback.verify(jdbc,ledger,table,next.runId()));
   report.put("finalSnapshot",snapshot(jdbc,table));report.put("result","VERIFIED");
  }catch(Exception e){report.put("result","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D024-live-acceptance"+("VERIFIED".equals(report.get("result"))?"":"-failure")+".json"),JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
 static void require(SyncJobRunner.Result r){if(r.state()!=SyncRunState.VERIFIED||r.sourceRows()<1||r.sourceRows()!=r.verifiedRows())throw new IllegalStateException("D024 nonempty verified source required: "+r);}
 static Map<String,Object> snapshot(JdbcTemplate jdbc,String table)throws Exception{
  if(!table.matches("java_d024_moneyflow_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned D024 target required");
  var rows=jdbc.queryForList("SELECT ts_code,cast(trade_date AS long) AS trade_micros,buy_sm_vol,buy_sm_amount,sell_sm_vol,sell_sm_amount,buy_md_vol,buy_md_amount,sell_md_vol,sell_md_amount,buy_lg_vol,buy_lg_amount,sell_lg_vol,sell_lg_amount,buy_elg_vol,buy_elg_amount,sell_elg_vol,sell_elg_amount,net_mf_vol,net_mf_amount FROM "+table+" ORDER BY trade_date,ts_code LIMIT 250001");
  if(rows.size()>250000)throw new IllegalStateException("D024 snapshot exceeded bound");var keys=new HashSet<String>();for(var r:rows)if(!keys.add(r.get("ts_code")+"/"+r.get("trade_micros")))throw new IllegalStateException("Duplicate D024 target key");
  return Map.of("rows",rows.size(),"duplicateKeys",0,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JobDefinitionJson.mapper().writeValueAsBytes(rows))));
 }
}
