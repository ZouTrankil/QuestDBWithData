import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.mapper.DcIndexMapper;
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

class DcIndexRecoveryOperation {
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();String action=args[1];var json=JobDefinitionJson.mapper();var accepted=json.readTree(Files.readAllBytes(input));String table=accepted.path("table").asText();Path ledger=Path.of(accepted.path("ledger").asText());
  if(!accepted.path("result").asText().equals("VERIFIED")||!table.matches("java_d023_dc_index_[a-f0-9]{32}")||!Set.of("prepare","cli","publication","post-cancel","post-failure","stage-only").contains(action))throw new IllegalArgumentException("Verified owned dc-index target and action required");
  var report=new LinkedHashMap<String,Object>();report.put("task","D023");report.put("table",table);report.put("ledger",ledger.toString());report.put("action",action);report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);
  var app=new SpringApplication(local.market.DcIndexOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("dc-index-recovery",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.dc-index-table",table,"app.tushare.concurrency",1))));
  try(var c=app.run("list-sync-jobs")){
   var jdbc=c.getBean(JdbcTemplate.class);var service=c.getBean(DcIndexJobService.class);var durable=new SyncRunLedger(ledger);var before=DcIndexAcceptanceOperation.snapshot(jdbc,table);report.put("before",before);
   if(action.equals("cli")){
    var cancelled=json.readTree(Files.readAllBytes(root.resolve("D023-cancel-prepare.json")));String prior=cancelled.path("cancelBeforeSource").path("runId").asText();var old=durable.getRun(prior);
    var result=NextMarketAcceptanceOperation.call(c.getBean(CommandLineRunner.class),new String[]{"run-dc-index-job","--resume-from",prior});report.put("cliResult",result);
    if(!"VERIFIED".equals(result.path("state").asText())||result.path("sourceRows").asInt()!=1031)throw new IllegalStateException("Frozen dc-index CLI continuation failed");
    String resumed=result.path("runId").asText();var current=durable.getRun(resumed);
    if(!SyncRequestIdentity.fingerprint(old.frozenJson(),old.targetId()).equals(SyncRequestIdentity.fingerprint(current.frozenJson(),current.targetId())))throw new IllegalStateException("CLI changed frozen dc-index request");
    report.put("independentReadback",DcIndexIndependentReadback.verify(jdbc,ledger,table,resumed));report.put("originalRunId",prior);report.put("sameFrozenRequest",true);
   }else{
    var plan=service.planDetailed(SyncJobDefinition.Mode.BACKFILL,LocalDate.of(2026,9,17),LocalDate.of(2026,9,17),LocalDate.now(ZoneId.of("Asia/Shanghai")));report.put("plan",plan);
    String run="dc-index-"+action+"-"+UUID.randomUUID();var fired=new AtomicBoolean();JdbcTemplate working=jdbc;
    if(action.equals("publication"))working=new JdbcTemplate(new DelegatingDataSource(jdbc.getDataSource()){
     @Override public Connection getConnection()throws SQLException{return PublicationRecoveryOperation.wrap(super.getConnection(),table,fired);}
     @Override public Connection getConnection(String user,String password)throws SQLException{return PublicationRecoveryOperation.wrap(super.getConnection(user,password),table,fired);}
    });
    var evidence=root.resolve("sync-evidence").resolve(run);var adapter=new DcIndexSyncAdapter(c.getBean(TusharePageService.class),new DcIndexTradingDates(c.getBean(ExchangeCalendarReadRepository.class)),new DcIndexWritePort(table,plan.physicalTargetId(),working,c.getBean(QuestDB.class)),evidence,ledger,table,run,plan.targetId(),plan.physicalTargetId(),working);
    var cancelAfterPublication=new AtomicBoolean();SyncJobRunner.Adapter<DcIndex,DcIndexKey> exercised=adapter;
    if(action.startsWith("post-")||action.equals("stage-only"))exercised=new SyncJobRunner.Adapter<>(){
     public void preflight(SyncJobDefinition.FrozenRequest r)throws Exception{adapter.preflight(r);}
     public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest r,SyncJobRunner.PageConsumer<DcIndex> consumer,java.util.function.BooleanSupplier cancelled)throws Exception{
      if(action.equals("stage-only"))return adapter.fetch(r,page->{consumer.accept(page);fired.set(true);throw new java.io.IOException("Injected failure after complete one-date stage write, before publication manifest");},cancelled);
      var completion=adapter.fetch(r,consumer,cancelled);fired.set(true);
      if(action.equals("post-failure"))throw new java.io.IOException("Injected completion failure after verified publication");
      cancelAfterPublication.set(true);return completion;
     }
     public VerifiedBatchExecutor.Codec<DcIndex,DcIndexKey> codec(){return adapter.codec();}
     public VerifiedBatchExecutor.Port<DcIndex,DcIndexKey> port(){return adapter.port();}
     public boolean recoveryRequired(String runId)throws Exception{return adapter.recoveryRequired(runId);}
    };
    var result=new SyncJobRunner<DcIndex,DcIndexKey>(durable,new DatasetIntervalLock(ledger)).run(run,null,plan.targetId(),plan.request(),exercised,()->action.equals("prepare")||cancelAfterPublication.get());
    if(action.equals("prepare")){
     report.put("cancelBeforeSource",result);if(result.state()!=SyncRunState.CANCELLED||result.sourceRows()!=0||result.verifiedRows()!=0)throw new IllegalStateException("Pre-source dc-index cancellation failed");
    }else{
     report.put("beforeRecovery",result);report.put("faultFired",fired.get());
     if(!fired.get()||result.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("Durable dc-index publication exception must remain IN_DOUBT");
     if(action.equals("stage-only")){
      boolean rejected=false;try{service.planDetailed(null,null,LocalDate.of(2026,9,18),LocalDate.now(ZoneId.of("Asia/Shanghai")));}
      catch(IllegalStateException pending){if(!pending.getMessage().contains("Unresolved D023 stage-only recovery"))throw pending;rejected=true;}
      report.put("orphanStageBlocksNewPlan",rejected);if(!rejected)throw new IllegalStateException("Unresolved stage must block subsequent planning");
     }
     service.finishPublication(run,true);report.put("finish","completed");report.put("durableStateAfter",durable.get(run).state());
     if(durable.get(run).state()!=SyncRunState.VERIFIED)throw new IllegalStateException("Monthly publication recovery did not verify ledger");
     report.put("independentReadback",DcIndexIndependentReadback.verify(jdbc,ledger,table,run));
    }
   }
   var after=DcIndexAcceptanceOperation.snapshot(jdbc,table);report.put("after",after);if(!before.equals(after))throw new IllegalStateException("Monthly recovery altered full target values");report.put("status","VERIFIED");
  }catch(Exception e){report.put("status","FAILED");report.put("failure",e.toString());throw e;}
  finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve(action.equals("prepare")?"D023-cancel-prepare.json":"D023-recovery-"+action+"-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
 }
}
