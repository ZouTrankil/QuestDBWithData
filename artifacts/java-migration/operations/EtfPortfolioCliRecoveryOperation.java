import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.EtfPortfolioMapper;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.service.*;
import io.questdb.client.QuestDB;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fresh-JVM CLI restoration of a partly verified D018 date, plus zero-source cancel and lost ACK. */
class EtfPortfolioCliRecoveryOperation {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));
        String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
        if(!a.path("task").asText().equals("D018")||!a.path("result").asText().equals("VERIFIED")||!table.matches("java_d018_etf_portfolio_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned accepted D018 target required");
        Path recovery;try(var paths=Files.list(root)){recovery=paths.filter(p->p.getFileName().toString().startsWith("D018-multichunk-recovery-")&&p.toString().endsWith(".json")).max(Comparator.comparingLong(p->p.toFile().lastModified())).orElseThrow();}
        var prior=json.readTree(Files.readAllBytes(recovery));
        if(!prior.path("table").asText().equals(table)||!prior.path("fullNineColumnSnapshotUnchanged").asBoolean()||!prior.path("independentSourceReadback").path("status").asText().equals("MATCHED"))throw new IllegalArgumentException("Verified multichunk recovery evidence required");
        var report=new LinkedHashMap<String,Object>();report.put("task","D018");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");report.put("multichunkEvidence",recovery.toString());
        var app=new SpringApplication(local.market.EtfPortfolioOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("portfolio-cli-recovery",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.etf-portfolio-table",table,"app.tushare.concurrency",1))));
        try(var context=app.run("list-sync-jobs")){
            var jdbc=context.getBean(JdbcTemplate.class);var service=context.getBean(EtfPortfolioJobService.class);var before=EtfPortfolioAcceptanceOperation.snapshot(jdbc,table);report.put("before",before);
            String priorId=prior.path("cancelledRun").path("runId").asText();var old=SyncRunLedger.openReadOnly(ledger).getRun(priorId);
            var resumed=NextMarketAcceptanceOperation.call(context.getBean(CommandLineRunner.class),new String[]{"run-etf-portfolio-job","--resume-from",priorId});report.put("priorRunId",priorId);report.put("cliResume",resumed);
            if(!resumed.path("state").asText().equals("VERIFIED")||resumed.path("reusedRows").asLong()!=10000||resumed.path("verifiedRows").asLong()!=18718)throw new IllegalStateException("CLI must restore and reuse the exact first 10000-row chunk");
            var restored=SyncRunLedger.openReadOnly(ledger).getRun(resumed.path("runId").asText());
            if(!old.targetId().equals(restored.targetId())||!SyncRequestIdentity.fingerprint(old.frozenJson(),old.targetId()).equals(SyncRequestIdentity.fingerprint(restored.frozenJson(),restored.targetId())))throw new IllegalStateException("CLI changed frozen scope/target");
            var comparison=EtfPortfolioIndependentReadback.verify(jdbc,ledger,table,resumed.path("runId").asText());report.put("independentCliReadback",comparison);if(!"MATCHED".equals(comparison.get("status")))throw new IllegalStateException("CLI raw readback mismatch");
            LocalDate date=LocalDate.parse(prior.path("date").asText());
            var plan=service.plan(SyncJobDefinition.Mode.BACKFILL,date,date,LocalDate.now(ZoneId.of("Asia/Shanghai")));
            var port=new EtfPortfolioWritePort(table,service.targetId(),jdbc,context.getBean(QuestDB.class));Path evidence=root.resolve("sync-evidence").resolve("cancel-before-"+UUID.randomUUID());
            var adapter=new EtfPortfolioSyncAdapter(new EtfPortfolioSource(context.getBean(TusharePageService.class),new EtfPortfolioMapper(),evidence.resolve("source")),port,evidence);
            var runner=new SyncJobRunner<EtfPortfolio,EtfPortfolioKey>(new SyncRunLedger(ledger),new DatasetIntervalLock(ledger));
            var cancelled=runner.run("cancel-before-"+UUID.randomUUID(),null,plan.targetId(),plan.request(),adapter,()->true);report.put("cancelBeforeSource",cancelled);
            if(cancelled.state()!=SyncRunState.CANCELLED||cancelled.sourceRows()!=0||cancelled.verifiedRows()!=0)throw new IllegalStateException("Cancellation before source must write zero rows");
            var samples=port.readAnnouncementDate(date).subList(0,2);var stopped=new AtomicBoolean();
            var unknown=new VerifiedBatchExecutor.Port<EtfPortfolio,EtfPortfolioKey>(){
                public void preflight()throws Exception{port.preflight();}
                public void send(List<EtfPortfolio> rows)throws Exception{port.send(rows);stopped.set(true);throw new java.io.IOException("Injected acknowledgement loss after actual sender closed");}
                public List<EtfPortfolio> readback(List<EtfPortfolioKey> keys)throws Exception{return port.readback(keys);}
                public boolean walSettled()throws Exception{return port.walSettled();}
                public boolean uncertainSenderStopped(){return stopped.get();}
            };
            var reconciled=new VerifiedBatchExecutor<EtfPortfolio,EtfPortfolioKey>(new VerifiedBatchExecutor.Policy(250,1048576,1,Duration.ofSeconds(10),Duration.ofMillis(100)),EtfPortfolioWritePort.CODEC,unknown).execute(samples.iterator());report.put("postSendLostAcknowledgement",reconciled);
            if(reconciled.status()!=VerifiedBatchExecutor.Status.VERIFIED||reconciled.receipts().getFirst().delivery()!=VerifiedBatchExecutor.Delivery.UNKNOWN_RECONCILED)throw new IllegalStateException("Lost ACK must reconcile by exact readback after sender close");
            var after=EtfPortfolioAcceptanceOperation.snapshot(jdbc,table);report.put("after",after);if(!before.equals(after))throw new IllegalStateException("CLI/reconciliation changed full physical values");report.put("status","VERIFIED");
        }catch(Exception failure){report.put("status","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D018-cli-recovery-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
}
