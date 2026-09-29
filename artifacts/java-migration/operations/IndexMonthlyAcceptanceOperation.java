import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;

class IndexMonthlyAcceptanceOperation {
 public static void main(String[] args)throws Exception {
  String id=UUID.randomUUID().toString().replace("-",""),table="java_d022_index_monthly_"+id;
  Path root=Path.of("artifacts/java-migration/market-live-"+id).toAbsolutePath(),ledger=root.resolve("sync-ledger.sqlite");Files.createDirectories(root);
  var json=JobDefinitionJson.mapper();var report=new LinkedHashMap<String,Object>();report.put("task","D022");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
  var app=new SpringApplication(local.market.IndexMonthlyOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
  app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("monthly-acceptance",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.index-monthly-table",table,"app.tushare.concurrency",1))));
  System.out.println("Evidence directory: "+root);
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);jdbc.execute(IndexMonthlyDataset.createIsolatedTableSql(table));var service=c.getBean(IndexMonthlyJobService.class);var logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));report.put("targetId",service.targetId());
   var routes=new ArrayList<Object>();report.put("routes",routes);
   for(String code:List.of("000300.SH","000985.CSI","399975.SZ")){
    var route=new LinkedHashMap<String,Object>();route.put("requestedCode",code);routes.add(route);var runs=new ArrayList<Object>();var proofs=new ArrayList<Object>();route.put("runs",runs);route.put("independentReadbacks",proofs);
    var plan=service.plan(null,code,LocalDate.of(2026,6,1),LocalDate.of(2026,7,31),logical);route.put("initialPlan",plan);route.put("providerCode",plan.tsCode());String observed=plan.request().parameters().get("observedAt").toString();
    var outside=otherCodes(jdbc,table,plan.tsCode());route.put("otherCodesBefore",outside);
    var first=service.run(plan);require(first);runs.add(first);proofs.add(compare(jdbc,ledger,table,first));var baseline=snapshot(jdbc,table);route.put("afterFirst",baseline);
    for(int i=0;i<2;i++){
     var repeat=withObservation(service.plan(null,code,null,LocalDate.of(2026,7,31),logical),observed);var r=service.run(repeat);require(r);runs.add(r);proofs.add(compare(jdbc,ledger,table,r));
     if(!baseline.equals(snapshot(jdbc,table)))throw new IllegalStateException("Monthly rerun changed target rows");
    }
    var increment=service.plan(null,code,null,LocalDate.of(2026,8,31),logical);route.put("incrementalPlan",increment);var next=service.run(increment);require(next);runs.add(next);proofs.add(compare(jdbc,ledger,table,next));route.put("afterIncremental",snapshot(jdbc,table));
    var outsideAfter=otherCodes(jdbc,table,plan.tsCode());route.put("otherCodesAfter",outsideAfter);if(!outside.equals(outsideAfter))throw new IllegalStateException("Monthly publication changed another code");
   }
   report.put("finalSnapshot",snapshot(jdbc,table));report.put("result","VERIFIED");
  }catch(Exception e){report.put("result","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D022-live-acceptance"+("VERIFIED".equals(report.get("result"))?"":"-failure")+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
 static IndexMonthlyJobService.Plan withObservation(IndexMonthlyJobService.Plan p,String observed){var r=p.request();var m=new LinkedHashMap<String,Object>(r.parameters());m.put("observedAt",observed);return new IndexMonthlyJobService.Plan(r.definition().freeze(r.mode(),m,r.from(),r.to(),r.logicalDate()),p.targetId(),p.physicalTargetId(),p.tsCode(),p.checkpointBefore(),p.checkpointAnchor(),p.physicalRange(),p.bootstrap());}
 static void require(SyncJobRunner.Result r){if(r.state()!=SyncRunState.VERIFIED||r.sourceRows()<1||r.sourceRows()!=r.verifiedRows())throw new IllegalStateException("Nonempty monthly verified source required: "+r);}
 static Map<String,Object> compare(JdbcTemplate jdbc,Path ledger,String table,SyncJobRunner.Result r)throws Exception {return IndexMonthlyIndependentReadback.verify(jdbc,ledger,table,r.runId());}
 static Map<String,Object> snapshot(JdbcTemplate jdbc,String table)throws Exception{return snapshotWhere(jdbc,table,"");}
 static Map<String,Object> otherCodes(JdbcTemplate jdbc,String table,String code)throws Exception {if(!code.matches("[0-9]{6}\\.(SH|SZ)"))throw new IllegalArgumentException("Monthly provider code required");return snapshotWhere(jdbc,table," WHERE ts_code!='"+code+"'");}
 static Map<String,Object> snapshotWhere(JdbcTemplate jdbc,String table,String predicate)throws Exception {
  if(!table.matches("java_d022_index_monthly_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned monthly target required");
  var rows=jdbc.queryForList("SELECT ts_code,cast(trade_date AS long) AS trade_micros,close,open,high,low,pre_close,change,pct_chg,vol,amount,layer,bucket,cast(update_time AS long) AS observed_micros FROM "+table+predicate+" ORDER BY ts_code,trade_date LIMIT 250001");
  if(rows.size()>250000)throw new IllegalStateException("Target snapshot exceeded bound");var keys=new HashSet<String>();for(var r:rows)if(!keys.add(r.get("ts_code")+"/"+r.get("trade_micros")))throw new IllegalStateException("Duplicate monthly target key");
  return Map.of("rows",rows.size(),"duplicateKeys",0,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JobDefinitionJson.mapper().writeValueAsBytes(rows))));
 }
}
