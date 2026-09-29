import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import io.questdb.client.QuestDB;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
class MoneyflowThsRecoveryOperation {
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();String action=args[1];var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
  if(!a.path("result").asText().equals("VERIFIED")||!table.matches("java_d025_moneyflow_ths_[a-f0-9]{32}")||!Set.of("exercise","cli").contains(action))throw new IllegalArgumentException("Accepted D025 owned target required");
  var report=new LinkedHashMap<String,Object>();report.put("task","D025");report.put("table",table);report.put("ledger",ledger.toString());report.put("action",action);report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);
  var app=new SpringApplication(local.market.MoneyflowThsOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("moneyflow-ths-recovery",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.moneyflow-ths-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(MoneyflowThsJobService.class);var durable=new SyncRunLedger(ledger);var before=MoneyflowThsAcceptanceOperation.snapshot(jdbc,table);report.put("before",before);
   if(action.equals("cli")){
    var saved=json.readTree(Files.readAllBytes(root.resolve("D025-recovery-exercise.json")));String prior=saved.path("cancelBeforeSource").path("runId").asText();var old=durable.getRun(prior);
    var result=NextMarketAcceptanceOperation.call(c.getBean(CommandLineRunner.class),new String[]{"run-moneyflow-ths-job","--resume-from",prior});report.put("cliResult",result);
    if(!"VERIFIED".equals(result.path("state").asText()))throw new IllegalStateException("D025 CLI resume not verified");String run=result.path("runId").asText();var current=durable.getRun(run);
    if(!SyncRequestIdentity.fingerprint(old.frozenJson(),old.targetId()).equals(SyncRequestIdentity.fingerprint(current.frozenJson(),current.targetId())))throw new IllegalStateException("D025 CLI changed frozen request");report.put("sameFrozenRequest",true);report.put("independentReadback",MoneyflowThsIndependentReadback.verify(jdbc,ledger,table,run));
   }else{
    var plan=service.plan(SyncJobDefinition.Mode.BACKFILL,LocalDate.of(2026,9,17),LocalDate.of(2026,9,17),LocalDate.now(ZoneId.of("Asia/Shanghai")));report.put("plan",plan);
    var runner=new SyncJobRunner<MoneyflowThs,MoneyflowThsKey>(durable,new DatasetIntervalLock(ledger));var pages=c.getBean(TusharePageService.class);var calendar=c.getBean(ExchangeCalendarReadRepository.class);var qdb=c.getBean(QuestDB.class);
    String cancelled="moneyflow-ths-cancel-before-"+UUID.randomUUID();var empty=adapter(pages,calendar,jdbc,qdb,table,plan.targetId(),root,cancelled);var result=runner.run(cancelled,null,plan.targetId(),plan.request(),empty,()->true);report.put("cancelBeforeSource",result);if(result.state()!=SyncRunState.CANCELLED||result.sourceRows()!=0)throw new IllegalStateException("D025 pre-source cancellation failed");
    String partialId="moneyflow-ths-cancel-page-"+UUID.randomUUID();var first=adapter(pages,calendar,jdbc,qdb,table,plan.targetId(),root,partialId);var sample=new AtomicReference<List<MoneyflowThs>>();
    var tracked=new SyncJobRunner.Adapter<MoneyflowThs,MoneyflowThsKey>(){
     public void preflight(SyncJobDefinition.FrozenRequest r)throws Exception{first.preflight(r);}
     public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest r,SyncJobRunner.PageConsumer<MoneyflowThs> consumer,BooleanSupplier cancelled)throws Exception{return first.fetch(r,page->{sample.set(page.rows().subList(0,Math.min(2,page.rows().size())));consumer.accept(page);durable.requestCancellation(partialId);},cancelled);}
     public VerifiedBatchExecutor.Codec<MoneyflowThs,MoneyflowThsKey> codec(){return first.codec();}public VerifiedBatchExecutor.Port<MoneyflowThs,MoneyflowThsKey> port(){return first.port();}
    };
    var partial=runner.run(partialId,null,plan.targetId(),plan.request(),tracked,()->false);report.put("cancelAfterVerifiedPage",partial);if(partial.state()!=SyncRunState.CANCELLED||partial.verifiedRows()<251)throw new IllegalStateException("D025 large verified page cancellation required");
    String resumedId="moneyflow-ths-resume-"+UUID.randomUUID();var resumedAdapter=adapter(pages,calendar,jdbc,qdb,table,plan.targetId(),root,resumedId);var sendCount=new AtomicInteger();var actualPort=resumedAdapter.port();
    var counted=new VerifiedBatchExecutor.Port<MoneyflowThs,MoneyflowThsKey>(){public void preflight()throws Exception{actualPort.preflight();}public void send(List<MoneyflowThs> rows)throws Exception{sendCount.incrementAndGet();actualPort.send(rows);}public List<MoneyflowThs> readback(List<MoneyflowThsKey> keys)throws Exception{return actualPort.readback(keys);}public boolean walSettled()throws Exception{return actualPort.walSettled();}public boolean uncertainSenderStopped()throws Exception{return actualPort.uncertainSenderStopped();}};
    var resumedWrapped=new SyncJobRunner.Adapter<MoneyflowThs,MoneyflowThsKey>(){public void preflight(SyncJobDefinition.FrozenRequest r)throws Exception{resumedAdapter.preflight(r);}public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest r,SyncJobRunner.PageConsumer<MoneyflowThs> consumer,BooleanSupplier cancel)throws Exception{return resumedAdapter.fetch(r,consumer,cancel);}public VerifiedBatchExecutor.Codec<MoneyflowThs,MoneyflowThsKey> codec(){return resumedAdapter.codec();}public VerifiedBatchExecutor.Port<MoneyflowThs,MoneyflowThsKey> port(){return counted;}};
    var resumed=runner.resume(resumedId,partialId,plan.targetId(),plan.request(),resumedWrapped,()->false);report.put("resume",resumed);report.put("resumeSendCalls",sendCount.get());if(resumed.state()!=SyncRunState.VERIFIED||resumed.reusedRows()!=partial.verifiedRows()||sendCount.get()!=0)throw new IllegalStateException("D025 resume must reuse complete page with zero sends");report.put("independentResumeReadback",MoneyflowThsIndependentReadback.verify(jdbc,ledger,table,resumedId));
    var sent=new AtomicBoolean();var unknown=new VerifiedBatchExecutor.Port<MoneyflowThs,MoneyflowThsKey>(){public void preflight()throws Exception{actualPort.preflight();}public void send(List<MoneyflowThs> rows)throws Exception{actualPort.send(rows);sent.set(true);throw new java.io.IOException("Injected lost ACK after real sender stopped");}public List<MoneyflowThs> readback(List<MoneyflowThsKey> keys)throws Exception{return actualPort.readback(keys);}public boolean walSettled()throws Exception{return actualPort.walSettled();}public boolean uncertainSenderStopped(){return sent.get();}};
    var reconciled=new VerifiedBatchExecutor<MoneyflowThs,MoneyflowThsKey>(new VerifiedBatchExecutor.Policy(250,1048576,1,Duration.ofSeconds(10),Duration.ofMillis(100)),first.codec(),unknown).execute(sample.get().iterator());report.put("postSendLostAcknowledgement",reconciled);if(reconciled.status()!=VerifiedBatchExecutor.Status.VERIFIED||reconciled.receipts().getFirst().delivery()!=VerifiedBatchExecutor.Delivery.UNKNOWN_RECONCILED)throw new IllegalStateException("D025 lost ACK was not independently reconciled");
   }
   var after=MoneyflowThsAcceptanceOperation.snapshot(jdbc,table);report.put("after",after);if(!before.equals(after))throw new IllegalStateException("D025 recovery changed target snapshot");report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D025-recovery-"+action+("VERIFIED".equals(report.get("status"))?"":"-failed-"+UUID.randomUUID())+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
 static MoneyflowThsSyncAdapter adapter(TusharePageService pages,ExchangeCalendarReadRepository calendar,JdbcTemplate jdbc,QuestDB qdb,String table,String target,Path root,String run){return new MoneyflowThsSyncAdapter(new MoneyflowThsSource(pages,root.resolve("sync-evidence").resolve(run).resolve("source")),calendar,new MoneyflowThsWritePort(table,target,jdbc,qdb),root.resolve("sync-evidence").resolve(run));}
}
