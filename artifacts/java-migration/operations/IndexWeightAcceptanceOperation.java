import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;

/** Operational D021 dual-provider snapshot and bounded historical-route acceptance. */
class IndexWeightAcceptanceOperation {
 public static void main(String[] args)throws Exception {
  String id=UUID.randomUUID().toString().replace("-","");
  Path root=(args.length==1?Path.of(args[0]):Path.of("artifacts/java-migration/market-live-"+id)).toAbsolutePath(),ledger=root.resolve("sync-ledger.sqlite");Files.createDirectories(root);
  var previous=args.length==1?JobDefinitionJson.mapper().readTree(Files.readAllBytes(root.resolve("source-operation.json"))):null;
  String table=previous==null?"java_d021_index_weight_"+id:previous.path("table").asText();
  if(!table.matches("java_d021_index_weight_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned target required");
  var json=JobDefinitionJson.mapper();var report=new LinkedHashMap<String,Object>();report.put("task","D021");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
  var app=new SpringApplication(local.market.IndexWeightOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("weight-acceptance",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.index-weight-table",table,"app.tushare.concurrency",1))));
  System.out.println("Evidence directory: "+root);
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);if(previous==null)jdbc.execute(IndexWeightDataset.createIsolatedTableSql(table));var service=c.getBean(IndexWeightJobService.class);var logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));
   report.put("targetId",service.targetId());var runs=new ArrayList<Object>();var readbacks=new ArrayList<Object>();report.put("runs",runs);report.put("independentReadbacks",readbacks);
   IndexWeightJobService.Plan firstPlan; SyncJobRunner.Result first;
   if(previous==null){firstPlan=service.plan(null,null,null,logical,false);first=service.run(firstPlan);}else{
    var saved=com.zoutrankil.questdbwithdata.repository.SyncRunLedger.openReadOnly(ledger).getRun(previous.path("run").path("runId").asText());
    var frozen=json.readTree(saved.frozenJson());
    Map<String,Object> parameters=json.convertValue(frozen.path("parameters"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    var request=IndexWeightSyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.SNAPSHOT,parameters,null,null,LocalDate.parse(frozen.path("logicalDate").asText()));
    firstPlan=new IndexWeightJobService.Plan(request,saved.targetId(),null,null,null,null,false);
    first=json.treeToValue(previous.path("run"),SyncJobRunner.Result.class);
   }
   report.put("initialPlan",firstPlan);require(first);runs.add(first);readbacks.add(compare(jdbc,ledger,table,first));var baseline=snapshot(jdbc,table);report.put("readbackAfterFirst",baseline);
   var notDue=service.plan(null,null,null,logical,false);report.put("withinSevenDaysPlan",notDue);
   if(!notDue.notDue()||notDue.nextRefreshDate()==null)throw new IllegalStateException("Snapshot refresh gate did not suppress another default fetch");
   for(int i=0;i<2;i++){
    var plan=withObservation(service.plan(SyncJobDefinition.Mode.SNAPSHOT,null,null,logical,true),firstPlan.request().parameters().get("observedAt").toString());var run=service.run(plan);require(run);runs.add(run);readbacks.add(compare(jdbc,ledger,table,run));
    if(!baseline.equals(snapshot(jdbc,table)))throw new IllegalStateException("Frozen snapshot rerun changed full target");
   }
   var date=LocalDate.of(2026,8,31);var history=service.plan(SyncJobDefinition.Mode.BACKFILL,date,date,logical,false);report.put("historyPlan",history);var historical=service.run(history);require(historical);runs.add(historical);readbacks.add(compare(jdbc,ledger,table,historical));report.put("readbackAfterHistory",snapshot(jdbc,table));
   var afterHistoryGate=service.plan(null,null,null,logical,false);report.put("afterHistoryRefreshGate",afterHistoryGate);if(!afterHistoryGate.notDue()||!afterHistoryGate.nextRefreshDate().equals(notDue.nextRefreshDate()))throw new IllegalStateException("History changed snapshot refresh gate");
   var refreshed=service.run(service.plan(SyncJobDefinition.Mode.SNAPSHOT,null,null,logical,true));require(refreshed);runs.add(refreshed);readbacks.add(compare(jdbc,ledger,table,refreshed));report.put("readbackAfterRefresh",snapshot(jdbc,table));report.put("result","VERIFIED");
  }catch(Exception e){report.put("result","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D021-live-acceptance"+("VERIFIED".equals(report.get("result"))?"":"-failure")+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
 static IndexWeightJobService.Plan withObservation(IndexWeightJobService.Plan p,String observed){
  var r=p.request();var params=new LinkedHashMap<String,Object>(r.parameters());params.put("observedAt",observed);return new IndexWeightJobService.Plan(r.definition().freeze(r.mode(),params,r.from(),r.to(),r.logicalDate()),p.targetId(),p.targetMinBefore(),p.targetMaxBefore(),p.lastRefreshDate(),p.nextRefreshDate(),p.notDue());
 }
 static void require(SyncJobRunner.Result r){if(r.state()!=SyncRunState.VERIFIED||r.sourceRows()<1||r.verifiedRows()!=r.sourceRows())throw new IllegalStateException("Nonempty verified source required: "+r);}
 static Map<String,Object> compare(JdbcTemplate jdbc,Path ledger,String table,SyncJobRunner.Result r)throws Exception {
  // The separately implemented verifier reads raw provider artifacts and all physical fields.
  var proof=com.zoutrankil.questdbwithdata.operations.IndexWeightIndependentReadback.verify(jdbc,ledger,table,r.runId());
  if(!"MATCHED".equals(proof.get("status"))){Files.writeString(ledger.getParent().resolve("D021-readback-failure-"+UUID.randomUUID()+".json"),JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(proof));throw new IllegalStateException("D021 independent full-field comparison failed");}return proof;
 }
 static Map<String,Object> snapshot(JdbcTemplate jdbc,String table)throws Exception{
  if(!table.matches("java_d021_index_weight_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned target required");
  var rows=jdbc.queryForList("SELECT index_code,con_code,cast(trade_date AS long) AS trade_micros,index_name,index_name_en,con_name,con_name_en,exchange,exchange_en,weight,cast(update_time AS long) AS observed_micros FROM "+table+" ORDER BY index_code,trade_date,con_code LIMIT 500001");
  if(rows.size()>500000)throw new IllegalStateException("Bounded target exceeded");var keys=new HashSet<String>();for(var row:rows)if(!keys.add(row.get("index_code")+"/"+row.get("con_code")+"/"+row.get("trade_micros")))throw new IllegalStateException("Duplicate full key");
  return Map.of("rows",rows.size(),"duplicateKeys",0,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JobDefinitionJson.mapper().writeValueAsBytes(rows))));
 }
}
