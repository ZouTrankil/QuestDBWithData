import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.mapper.IndexMonthlyMapper;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import io.questdb.client.QuestDB;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;

class IndexMonthlyRecoveryOperation {
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();String action=args[1];var json=JobDefinitionJson.mapper();var accepted=json.readTree(Files.readAllBytes(input));String table=accepted.path("table").asText();Path ledger=Path.of(accepted.path("ledger").asText());
  if(!accepted.path("result").asText().equals("VERIFIED")||!table.matches("java_d022_index_monthly_[a-f0-9]{32}")||!Set.of("prepare","cli","publication","post-cancel","post-failure").contains(action))throw new IllegalArgumentException("Verified owned monthly target and action required");
  var report=new LinkedHashMap<String,Object>();report.put("task","D022");report.put("table",table);report.put("ledger",ledger.toString());report.put("action",action);report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);
  var app=new SpringApplication(local.market.IndexMonthlyOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("monthly-recovery",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.index-monthly-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(IndexMonthlyJobService.class);var durable=new SyncRunLedger(ledger);var before=IndexMonthlyAcceptanceOperation.snapshot(jdbc,table);report.put("before",before);
   if(action.equals("cli")){
    var cancelled=json.readTree(Files.readAllBytes(root.resolve("D022-cancel-prepare.json")));String prior=cancelled.path("cancelBeforeSource").path("runId").asText();var old=durable.getRun(prior);
    var result=NextMarketAcceptanceOperation.call(c.getBean(CommandLineRunner.class),new String[]{"run-index-monthly-job","--resume-from",prior});report.put("cliResult",result);
    if(!"VERIFIED".equals(result.path("state").asText())||result.path("sourceRows").asInt()!=2)throw new IllegalStateException("Frozen monthly CLI continuation failed");
    String resumed=result.path("runId").asText();var current=durable.getRun(resumed);
    if(!SyncRequestIdentity.fingerprint(old.frozenJson(),old.targetId()).equals(SyncRequestIdentity.fingerprint(current.frozenJson(),current.targetId())))throw new IllegalStateException("CLI changed frozen monthly request");
    report.put("independentReadback",IndexMonthlyIndependentReadback.verify(jdbc,ledger,table,resumed));report.put("originalRunId",prior);report.put("sameFrozenRequest",true);
   }else{
    String oldRun=accepted.path("routes").get(0).path("runs").get(3).path("runId").asText();long micros=jdbc.queryForObject("SELECT cast(update_time AS long) FROM "+table+" WHERE ts_code='000300.SH' ORDER BY trade_date LIMIT 1",Long.class);String observed=Instant.ofEpochSecond(Math.floorDiv(micros,1000000L),Math.floorMod(micros,1000000L)*1000).toString();
    var plan=IndexMonthlyAcceptanceOperation.withObservation(service.plan(SyncJobDefinition.Mode.BACKFILL,"000300.SH",LocalDate.of(2026,6,1),LocalDate.of(2026,7,31),LocalDate.now(ZoneId.of("Asia/Shanghai"))),observed);report.put("plan",plan);
    String run="monthly-"+action+"-"+UUID.randomUUID();var fired=new AtomicBoolean();JdbcTemplate working=jdbc;
    if(action.equals("publication"))working=new JdbcTemplate(new DelegatingDataSource(jdbc.getDataSource()){
     @Override public Connection getConnection()throws SQLException{return PublicationRecoveryOperation.wrap(super.getConnection(),table,fired);}
     @Override public Connection getConnection(String user,String password)throws SQLException{return PublicationRecoveryOperation.wrap(super.getConnection(user,password),table,fired);}
    });
    var evidence=root.resolve("sync-evidence").resolve(run);var adapter=new IndexMonthlySyncAdapter(new IndexMonthlySource(c.getBean(TusharePageService.class),new IndexMonthlyMapper(),evidence.resolve("source")),new IndexMonthlyWritePort(table,plan.physicalTargetId(),working,c.getBean(QuestDB.class)),evidence,run,ledger,table,plan.targetId(),working);
    var cancelAfterPublication=new AtomicBoolean();SyncJobRunner.Adapter<IndexMonthly,IndexMonthlyKey> exercised=adapter;
    if(action.startsWith("post-"))exercised=new SyncJobRunner.Adapter<>(){
     public void preflight(SyncJobDefinition.FrozenRequest r)throws Exception{adapter.preflight(r);}
     public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest r,SyncJobRunner.PageConsumer<IndexMonthly> consumer,java.util.function.BooleanSupplier cancelled)throws Exception{
      var completion=adapter.fetch(r,consumer,cancelled);fired.set(true);
      if(action.equals("post-failure"))throw new java.io.IOException("Injected completion failure after verified publication");
      cancelAfterPublication.set(true);return completion;
     }
     public VerifiedBatchExecutor.Codec<IndexMonthly,IndexMonthlyKey> codec(){return adapter.codec();}
     public VerifiedBatchExecutor.Port<IndexMonthly,IndexMonthlyKey> port(){return adapter.port();}
     public boolean recoveryRequired(String runId)throws Exception{return adapter.recoveryRequired(runId);}
    };
    var result=new SyncJobRunner<IndexMonthly,IndexMonthlyKey>(durable,new DatasetIntervalLock(ledger)).run(run,null,plan.targetId(),plan.request(),exercised,()->action.equals("prepare")||cancelAfterPublication.get());
    if(action.equals("prepare")){
     report.put("cancelBeforeSource",result);if(result.state()!=SyncRunState.CANCELLED||result.sourceRows()!=0||result.verifiedRows()!=0)throw new IllegalStateException("Pre-source monthly cancellation failed");
    }else{
     report.put("beforeRecovery",result);report.put("faultFired",fired.get());
     if(!fired.get()||result.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("Durable monthly publication exception must remain IN_DOUBT");
     var finished=service.finishInterrupted(run,true);report.put("finish",finished);report.put("durableStateAfter",durable.get(run).state());
     if(durable.get(run).state()!=SyncRunState.VERIFIED)throw new IllegalStateException("Monthly publication recovery did not verify ledger");
     report.put("independentReadback",IndexMonthlyIndependentReadback.verify(jdbc,ledger,table,run));
    }
   }
   var after=IndexMonthlyAcceptanceOperation.snapshot(jdbc,table);report.put("after",after);if(!before.equals(after))throw new IllegalStateException("Monthly recovery altered full target values");report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve(action.equals("prepare")?"D022-cancel-prepare.json":"D022-recovery-"+action+"-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
