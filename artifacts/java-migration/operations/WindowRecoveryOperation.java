import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.mapper.StockStDailyMapper;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import io.questdb.client.QuestDB;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit cancellation, stale-row and lost-ack operations on an already owned isolated window table. */
class WindowRecoveryOperation {
    static String task,table,command;static Path ledger,root;static ConfigurableApplicationContext context;
    public static void main(String[] args)throws Exception {
        Path accepted=Path.of(args[0]).toAbsolutePath();root=accepted.getParent();var json=JobDefinitionJson.mapper();
        var original=json.readTree(Files.readAllBytes(accepted));task=original.path("task").asText();table=original.path("table").asText();
        if(!Set.of("D011","D012").contains(task)||!original.path("result").asText().equals("VERIFIED")
                ||!table.matches("java_"+task.toLowerCase()+"_[a-z_]+_[a-f0-9]{32}"))throw new IllegalArgumentException("Verified owned target required");
        command=task.equals("D011")?"stk-suspend":"stk-st-daily";ledger=Path.of(original.path("ledger").asText());
        String nonce=UUID.randomUUID().toString();var report=new LinkedHashMap<String,Object>();
        report.put("task",task);report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());
        report.put("formalTableMutated",false);report.put("humanReview","pending_review");
        report.put("injectedScenarios","Cancellation after staged page, explicit synthetic stale status, lost acknowledgement after sender closed; only owned isolated target");
        var app=new SpringApplication(task.equals("D011")?local.market.StockSuspendOperationApplication.class:local.market.StockStOperationApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("window-recovery",
                Map.of("app.sync.ledger-path",ledger.toString(),"app.sync."+command+"-table",table,"app.tushare.concurrency",1))));
        try(var opened=app.run("list-sync-jobs")) {
            context=opened;var jdbc=context.getBean(JdbcTemplate.class);
            LocalDate start=LocalDate.parse(original.path("requestWindows").path("initial").get(0).asText());
            LocalDate logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            var prior=args.length==2?json.readTree(Files.readAllBytes(Path.of(args[1]))):null;
            if(prior!=null&&(!prior.path("table").asText().equals(table)||!prior.path("task").asText().equals(task)||!prior.path("status").asText().equals("FAILED")))throw new IllegalArgumentException("Matching failed owned injection report required");
            Map<String,Object> before=prior==null?snapshot(jdbc):json.convertValue(prior.path("initialSnapshot"),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});report.put("initialSnapshot",before);
            if(prior==null){
                var request=plan(start,logical);String target=request.parameters().get("targetId").toString();
                if(task.equals("D011"))recover(request,target,report,new StockSuspend(new StockSuspendKey("999999.SZ",start),1));
                else recover(request,target,report,new StockStDaily("999999.SZ",start,1));
                if(!before.equals(snapshot(jdbc)))throw new IllegalStateException("Recovery changed authoritative target values");
            }else{
                report.put("priorFailedInjection",args[1]);
                for(String key:List.of("cancelBeforeSource","cancelAfterStagedPage","cliResume","pageReuseScope"))report.put(key,prior.path(key));
            }
            var replacementRequest=prior==null?plan(start,logical):FrozenRunRequest.restore(ledger,prior.path("staleWindowReplacement").path("runId").asText(),task.equals("D011")?StockSuspendSyncJobOwner.DEFINITION:StockStDailySyncJobOwner.DEFINITION).request();
            // Seed one explicitly synthetic obsolete positive status in the same date window.
            if(prior==null&&task.equals("D011"))new StockSuspendWritePort(table,jdbc,context.getBean(QuestDB.class),context.getBean(StockSuspendJobService.class).physicalTargetId())
                    .send(List.of(new StockSuspend(new StockSuspendKey("999999.SZ",start),1)));
            else if(prior==null)new StockStDailyWritePort(table,context.getBean(StockStDailyJobService.class).physicalTargetId(),jdbc,context.getBean(QuestDB.class))
                    .send(List.of(new StockStDaily("999999.SZ",start,1)));
            report.put("staleSeed",Map.of("ts_code","999999.SZ","date",start,"synthetic",true));
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,table)){if(System.nanoTime()>deadline)throw new IllegalStateException("Seed WAL did not settle");Thread.sleep(50);}
            String replacementRun="window-stale-"+UUID.randomUUID();
            var replacementResult=new SyncJobRunner<Object,Object>(new SyncRunLedger(ledger),new DatasetIntervalLock(ledger))
                    .run(replacementRun,null,replacementRequest.parameters().get("targetId").toString(),replacementRequest,adapter(replacementRun,replacementRequest),()->false);
            var replaced=json.valueToTree(replacementResult);
            report.put("staleWindowReplacement",replaced);
            report.put("replacementSnapshot",snapshot(jdbc));
            if(!replaced.path("state").asText().equals("VERIFIED")||!json.valueToTree(before).equals(json.valueToTree(snapshot(jdbc))))throw new IllegalStateException("Stale key was not retracted or outside-window values changed");
            report.put("status","VERIFIED");report.put("finalSnapshot",snapshot(jdbc));
        }catch(Exception failure){report.put("status","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve(task+"-window-recovery-"+nonce+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
        System.out.println(task+" staged cancellation, exact CLI resume, and obsolete-key retraction VERIFIED");
    }
    static SyncJobDefinition.FrozenRequest plan(LocalDate day,LocalDate logical)throws Exception {
        return task.equals("D011")?context.getBean(StockSuspendJobService.class).planDetailed(SyncJobDefinition.Mode.BACKFILL,day,day,logical).request()
                :context.getBean(StockStDailyJobService.class).plan(SyncJobDefinition.Mode.BACKFILL,day,day,logical).request();
    }
    @SuppressWarnings("unchecked") static <T,K> SyncJobRunner.Adapter<T,K> adapter(String run,SyncJobDefinition.FrozenRequest r)throws Exception {
        return adapter(run,r,context.getBean(JdbcTemplate.class));
    }
    @SuppressWarnings("unchecked") static <T,K> SyncJobRunner.Adapter<T,K> adapter(String run,SyncJobDefinition.FrozenRequest r,JdbcTemplate jdbc)throws Exception {
        var qdb=context.getBean(QuestDB.class);var pages=context.getBean(TusharePageService.class);
        Path evidence=root.resolve("sync-evidence").resolve(run);String target=r.parameters().get("targetId").toString(),physical=r.parameters().get("physicalTargetId").toString();
        if(task.equals("D011"))return (SyncJobRunner.Adapter<T,K>)new StockSuspendSyncAdapter(pages,new StockSuspendWritePort(table,jdbc,qdb,physical),evidence,ledger,table,run,target,physical,jdbc);
        return (SyncJobRunner.Adapter<T,K>)new StockStDailySyncAdapter(new StockStDailySource(pages,new StockStDailyMapper(),evidence.resolve("source")),
                new StockStTradingDates(context.getBean(ExchangeCalendarReadRepository.class)),new StockStDailyWritePort(table,physical,jdbc,qdb),evidence,run,ledger,table,target,jdbc,qdb);
    }
    static <T,K> void recover(SyncJobDefinition.FrozenRequest request,String target,Map<String,Object> report,T unused)throws Exception {
        var durable=new SyncRunLedger(ledger);var runner=new SyncJobRunner<T,K>(durable,new DatasetIntervalLock(ledger));
        String early="window-cancel-before-"+UUID.randomUUID();
        var before=runner.run(early,null,target,request,adapter(early,request),()->true);report.put("cancelBeforeSource",before);
        if(before.state()!=SyncRunState.CANCELLED||before.sourceRows()!=0)throw new IllegalStateException("Before-source cancellation failed");
        String partial="window-cancel-after-"+UUID.randomUUID();SyncJobRunner.Adapter<T,K> real=adapter(partial,request);
        var tracked=new SyncJobRunner.Adapter<T,K>() {
            public void preflight(SyncJobDefinition.FrozenRequest r)throws Exception{real.preflight(r);}
            public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest r,SyncJobRunner.PageConsumer<T> consume,BooleanSupplier stopped)throws Exception{
                try { return real.fetch(r,page->{consume.accept(page);durable.requestCancellation(partial);},stopped); }
                catch(Exception failure){failure.printStackTrace();throw failure;}
            }
            public VerifiedBatchExecutor.Codec<T,K> codec(){return real.codec();}
            public VerifiedBatchExecutor.Port<T,K> port(){return real.port();}
        };
        var stopped=runner.run(partial,null,target,request,tracked,()->false);report.put("cancelAfterStagedPage",stopped);
        if(stopped.state()!=SyncRunState.CANCELLED||stopped.verifiedRows()<1)throw new IllegalStateException("After-page staged cancellation failed");
        var resumed=NextMarketAcceptanceOperation.call(context.getBean(CommandLineRunner.class),new String[]{"run-"+command+"-job","--resume-from",partial});report.put("cliResume",resumed);
        if(!resumed.path("state").asText().equals("VERIFIED")||resumed.path("reusedRows").asInt()!=stopped.verifiedRows())throw new IllegalStateException("Staged pages did not resume from exact saved request");
        report.put("pageReuseScope","Generic page send avoided after complete stage reconstruction and readback; stage creation may write rows");
    }
    static Map<String,Object> snapshot(JdbcTemplate jdbc)throws Exception {
        var definition=task.equals("D011")?StockSuspendDataset.DEFINITION:StockStDailyDataset.DEFINITION;
        return StockStAcceptanceOperation.snapshot(jdbc,table,definition);
    }
}
