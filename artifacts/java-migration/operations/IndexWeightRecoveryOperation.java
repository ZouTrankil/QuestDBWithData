import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.mapper.IndexWeightMapper;
import io.questdb.client.QuestDB;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

class IndexWeightRecoveryOperation {
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));String table=a.path("table").asText();Path ledgerPath=Path.of(a.path("ledger").asText());
  if(!"VERIFIED".equals(a.path("result").asText())||!table.matches("java_d021_index_weight_[a-f0-9]{32}"))throw new IllegalArgumentException("Verified owned D021 target required");
  String nonce=UUID.randomUUID().toString();var report=new LinkedHashMap<String,Object>();report.put("task","D021");report.put("table",table);report.put("ledger",ledgerPath.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
  var app=new SpringApplication(local.market.IndexWeightOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("weight-recovery",Map.of("app.sync.ledger-path",ledgerPath.toString(),"app.sync.index-weight-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(IndexWeightJobService.class);var ledger=new SyncRunLedger(ledgerPath);var before=IndexWeightAcceptanceOperation.snapshot(jdbc,table);report.put("before",before);
   var previous=ledger.getRun(a.path("runs").get(a.path("runs").size()-1).path("runId").asText());String observed=json.readTree(previous.frozenJson()).path("parameters").path("observedAt").asText();
   var plan=IndexWeightAcceptanceOperation.withObservation(service.plan(SyncJobDefinition.Mode.SNAPSHOT,null,null,LocalDate.now(ZoneId.of("Asia/Shanghai")),true),observed);Path evidence=root.resolve("sync-evidence/recovery-"+nonce);
   var real=new IndexWeightSyncAdapter(new IndexWeightSource(c.getBean(TusharePageService.class),new IndexWeightMapper(),evidence.resolve("source"),c.getBean(IndexWeightNameResolver.class)),new IndexWeightWritePort(table,plan.targetId(),jdbc,c.getBean(QuestDB.class)),evidence);
   var runner=new SyncJobRunner<IndexWeight,IndexWeightKey>(ledger,new DatasetIntervalLock(ledgerPath));var zero=runner.run("cancel-before-"+UUID.randomUUID(),null,plan.targetId(),plan.request(),real,()->true);report.put("cancelBeforeSource",zero);if(zero.state()!=SyncRunState.CANCELLED||zero.sourceRows()!=0)throw new IllegalStateException("Pre-source cancellation failed");
   String cancelledId="cancel-first-page-"+UUID.randomUUID();var cancel=new AtomicBoolean(true);var sent=new AtomicInteger();var sample=new AtomicReference<List<IndexWeight>>();
   var trackedPort=new VerifiedBatchExecutor.Port<IndexWeight,IndexWeightKey>(){
    public void preflight()throws Exception{real.port().preflight();}
    public void send(List<IndexWeight> rows)throws Exception{sent.addAndGet(rows.size());real.port().send(rows);}
    public List<IndexWeight> readback(List<IndexWeightKey> keys)throws Exception{return real.port().readback(keys);}
    public boolean walSettled()throws Exception{return real.port().walSettled();}
   };
   var tracked=new SyncJobRunner.Adapter<IndexWeight,IndexWeightKey>(){
    public void preflight(SyncJobDefinition.FrozenRequest r)throws Exception{real.preflight(r);}
    public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest r,SyncJobRunner.PageConsumer<IndexWeight> consumer,BooleanSupplier stop)throws Exception{
     return real.fetch(r,page->{if(sample.get()==null&&!page.rows().isEmpty())sample.set(page.rows().subList(0,Math.min(2,page.rows().size())));consumer.accept(page);if(cancel.get())ledger.requestCancellation(cancelledId);},stop);
    }
    public VerifiedBatchExecutor.Codec<IndexWeight,IndexWeightKey> codec(){return real.codec();}
    public VerifiedBatchExecutor.Port<IndexWeight,IndexWeightKey> port(){return trackedPort;}
   };
   var partial=runner.run(cancelledId,null,plan.targetId(),plan.request(),tracked,()->false);report.put("cancelAfterVerifiedPage",partial);if(partial.state()!=SyncRunState.CANCELLED||partial.verifiedRows()!=300)throw new IllegalStateException("First real 300-row index page was not safely cancelled");
   cancel.set(false);sent.set(0);var resumed=runner.resume("resume-"+UUID.randomUUID(),cancelledId,plan.targetId(),plan.request(),tracked,()->false);report.put("resume",resumed);report.put("resumeSentRows",sent.get());IndexWeightAcceptanceOperation.require(resumed);
   if(resumed.reusedRows()!=partial.verifiedRows()||sent.get()!=resumed.verifiedRows()-resumed.reusedRows())throw new IllegalStateException("Resume did not reuse first verified page and send only remaining rows");
   report.put("independentResumeReadback",IndexWeightAcceptanceOperation.compare(jdbc,ledgerPath,table,resumed));
   var stopped=new AtomicBoolean();var unknown=new VerifiedBatchExecutor.Port<IndexWeight,IndexWeightKey>(){
    public void preflight()throws Exception{real.port().preflight();}
    public void send(List<IndexWeight> rows)throws Exception{real.port().send(rows);stopped.set(true);throw new java.io.IOException("Injected acknowledgement loss after closed sender");}
    public List<IndexWeight> readback(List<IndexWeightKey> keys)throws Exception{return real.port().readback(keys);}
    public boolean walSettled()throws Exception{return real.port().walSettled();}
    public boolean uncertainSenderStopped(){return stopped.get();}
   };
   var lost=new VerifiedBatchExecutor<IndexWeight,IndexWeightKey>(new VerifiedBatchExecutor.Policy(250,1048576,1,Duration.ofSeconds(10),Duration.ofMillis(100)),real.codec(),unknown).execute(sample.get().iterator());report.put("postSendLostAcknowledgement",lost);
   if(lost.status()!=VerifiedBatchExecutor.Status.VERIFIED||lost.receipts().getFirst().delivery()!=VerifiedBatchExecutor.Delivery.UNKNOWN_RECONCILED)throw new IllegalStateException("Lost acknowledgement was not reconciled from physical rows");
   var after=IndexWeightAcceptanceOperation.snapshot(jdbc,table);report.put("after",after);if(!before.equals(after))throw new IllegalStateException("Recovery changed full target values");report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D021-recovery-"+nonce+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
