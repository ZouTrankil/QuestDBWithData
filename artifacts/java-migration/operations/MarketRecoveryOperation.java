import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.DailyBasicMapper;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.service.*;
import io.questdb.client.QuestDB;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Operational fault exercise using real source requests and previously owned isolated tables. */
class MarketRecoveryOperation {
    public static void main(String[] args) throws Exception {
        for (String argument : args) {
            Path reportPath=Path.of(argument).toAbsolutePath().normalize();
            var json=JobDefinitionJson.mapper(); var original=json.readTree(Files.readAllBytes(reportPath));
            String task=original.path("task").asText(), table=original.path("table").asText();
            if (!Set.of("D007","D008","D009","D010","D014","D015").contains(task) || !table.matches("java_(d00[789]_[a-z]+|d010_stk_limit|d014_etf_daily|d015_etf_adj)_[a-f0-9]{32}"))
                throw new IllegalArgumentException("Explicit owned market acceptance target required");
            Path root=reportPath.getParent();
            String id=UUID.randomUUID().toString(); Path ledger=root.resolve("recovery-"+id+".sqlite");
            var output=new LinkedHashMap<String,Object>();
            output.put("task",task); output.put("table",table); output.put("startedAt",Instant.now().toString());
            output.put("ledger",ledger.toString()); output.put("faults","Explicitly injected at cancellation and post-send acknowledgement boundaries");
            output.put("formalTableMutated",false); output.put("humanReview","pending_review");
            var app=new SpringApplication(task.equals("D015")?local.market.EtfAdjOperationApplication.class:task.equals("D014")?local.market.EtfDailyOperationApplication.class:local.market.MarketOperationApplication.class); app.setWebApplicationType(WebApplicationType.NONE);
            app.setLogStartupInfo(false);
            app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("market-recovery",
                Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.daily-table",task.equals("D007")?table:"daily",
                    "app.sync.daily-basic-table",task.equals("D008")?table:"daily_basic",
                    "app.sync.stk-factor-table",task.equals("D009")?table:"stk_factor",
                    "app.sync.stk-limit-table",task.equals("D010")?table:"java_d010_stk_limit_acceptance",
                    "app.sync.etf-daily-table",task.equals("D014")?table:"java_d014_etf_daily_acceptance",
                    "app.sync.etf-adj-table",task.equals("D015")?table:"java_d015_etf_adj_acceptance",
                    "app.tushare.concurrency",1))));
            try (var context=app.run("list-sync-jobs")) {
                var jdbc=context.getBean(JdbcTemplate.class); var qdb=context.getBean(QuestDB.class);
                var pages=context.getBean(TusharePageService.class); var calendar=context.getBean(ExchangeCalendarReadRepository.class);
                LocalDate date=LocalDate.parse(original.path("requestWindows").path("initial").get(0).asText());
                LocalDate logicalDate=LocalDate.now(ZoneId.of("Asia/Shanghai"));
                long before=jdbc.queryForObject("SELECT count() FROM "+table,Long.class); output.put("rowsBefore",before);
                Path receipts=root.resolve("sync-evidence").resolve("recovery-"+id);
                if (task.equals("D007")) {
                    var plan=context.getBean(DailyJobService.class).plan(date,date,logicalDate,SyncJobDefinition.Mode.BACKFILL);
                    var adapter=new DailySyncAdapter(pages,calendar,context.getBean(StockDetailInfoReadRepository.class),
                        new DailyWritePort(table,jdbc,qdb),receipts);
                    exercise(task,table,jdbc,ledger,plan.request(),plan.targetId(),adapter,output);
                } else if (task.equals("D008")) {
                    var plan=context.getBean(DailyBasicJobService.class).plan(date,date,logicalDate,SyncJobDefinition.Mode.BACKFILL);
                    var adapter=new DailyBasicSyncAdapter(new DailyBasicSource(pages,new DailyBasicMapper(),receipts),
                        new DailyBasicTradingDates(calendar),new DailyBasicWritePort(table,jdbc,qdb),receipts);
                    exercise(task,table,jdbc,ledger,plan.request(),plan.targetId(),adapter,output);
                } else if (task.equals("D009")) {
                    var plan=context.getBean(StockFactorJobService.class).planDetailed(SyncJobDefinition.Mode.BACKFILL,date,date,logicalDate,null);
                    var adapter=new StockFactorSyncAdapter(pages,new StockFactorWritePort(table,jdbc,qdb,1048576,Duration.ofSeconds(10),plan.targetId()),receipts);
                    exercise(task,table,jdbc,ledger,plan.request(),plan.targetId(),adapter,output);
                } else if (task.equals("D010")) {
                    var plan=context.getBean(StockLimitJobService.class).plan(SyncJobDefinition.Mode.BACKFILL,date,date,logicalDate);
                    var adapter=new StockLimitSyncAdapter(new StockLimitSource(pages,new com.zoutrankil.questdbwithdata.mapper.StockLimitMapper(),receipts),
                        new StockLimitTradingDates(calendar),new StockLimitWritePort(table,plan.targetId(),jdbc,qdb),receipts);
                    exercise(task,table,jdbc,ledger,plan.request(),plan.targetId(),adapter,output);
                } else if(task.equals("D015")) {
                    var plan=context.getBean(EtfAdjJobService.class).plan(SyncJobDefinition.Mode.BACKFILL,date,date,logicalDate);
                    var adapter=new EtfAdjSyncAdapter(new EtfAdjSource(pages,new com.zoutrankil.questdbwithdata.mapper.EtfAdjMapper(),receipts),
                        new EtfAdjTradingDates(calendar),new EtfAdjWritePort(table,plan.targetId(),jdbc,qdb),receipts);
                    exercise(task,table,jdbc,ledger,plan.request(),plan.targetId(),adapter,output);
                } else {
                    var plan=context.getBean(EtfDailyJobService.class).plan(SyncJobDefinition.Mode.BACKFILL,date,date,logicalDate);
                    var adapter=new EtfDailySyncAdapter(new EtfDailySource(pages,new com.zoutrankil.questdbwithdata.mapper.EtfDailyMapper(),receipts),
                        new EtfDailyTradingDates(calendar),new EtfDailyWritePort(table,plan.targetId(),jdbc,qdb),receipts);
                    exercise(task,table,jdbc,ledger,plan.request(),plan.targetId(),adapter,output);
                }
                long after=jdbc.queryForObject("SELECT count() FROM "+table,Long.class); output.put("rowsAfter",after);
                if (before!=after) throw new IllegalStateException("Same-key recovery changed target count");
                output.put("status","VERIFIED");
            } catch (Exception failure) {
                output.put("status","FAILED"); output.put("failure",failure.toString()); throw failure;
            } finally {
                output.put("finishedAt",Instant.now().toString());
                Files.writeString(root.resolve(task+"-recovery-"+id+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            }
            System.out.println(task+" cancellation/resume/unknown-write operations VERIFIED");
        }
    }
    static <T,K> void exercise(String task,String table,JdbcTemplate jdbc,Path ledgerPath,
            SyncJobDefinition.FrozenRequest request,String target,SyncJobRunner.Adapter<T,K> real,
            Map<String,Object> output) throws Exception {
        var ledger=new SyncRunLedger(ledgerPath); var runner=new SyncJobRunner<T,K>(ledger,new DatasetIntervalLock(ledgerPath));
        String cancelledId="cancel-before-"+UUID.randomUUID();
        var before=runner.run(cancelledId,null,target,request,real,()->true); output.put("cancelBeforeSource",before);
        if(before.state()!=SyncRunState.CANCELLED || before.sourceRows()!=0) throw new IllegalStateException("Cancellation before source failed");
        var sendCount=new AtomicInteger(); var sample=new AtomicReference<List<T>>();
        var stopAfterPage=new AtomicBoolean(true); String partialId="cancel-after-page-"+UUID.randomUUID();
        var trackedPort=new VerifiedBatchExecutor.Port<T,K>() {
            public void preflight() throws Exception {real.port().preflight();}
            public void send(List<T> rows) throws Exception {sendCount.incrementAndGet();real.port().send(rows);}
            public List<T> readback(List<K> keys) throws Exception {return real.port().readback(keys);}
            public boolean walSettled() throws Exception {return real.port().walSettled();}
        };
        var tracked=new SyncJobRunner.Adapter<T,K>() {
            public void preflight(SyncJobDefinition.FrozenRequest r)throws Exception{real.preflight(r);}
            public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest r,SyncJobRunner.PageConsumer<T> consumer,BooleanSupplier cancelled)throws Exception{
                return real.fetch(r,page->{sample.set(page.rows().subList(0,Math.min(2,page.rows().size())));consumer.accept(page);
                    if(stopAfterPage.get())ledger.requestCancellation(partialId);},cancelled);
            }
            public VerifiedBatchExecutor.Codec<T,K> codec(){return real.codec();}
            public VerifiedBatchExecutor.Port<T,K> port(){return trackedPort;}
        };
        var partial=runner.run(partialId,null,target,request,tracked,()->false); output.put("cancelAfterVerifiedPage",partial);
        if(partial.state()!=SyncRunState.CANCELLED || partial.verifiedRows()<(Set.of("D019","D020").contains(task)?1:251))throw new IllegalStateException("Expected cancelled run with a large verified source page");
        stopAfterPage.set(false); sendCount.set(0);
        var recovered=runner.resume("resume-"+UUID.randomUUID(),partialId,target,request,tracked,()->false);
        output.put("resume",recovered); output.put("resumeSendCalls",sendCount.get());
        if(recovered.state()!=SyncRunState.VERIFIED || recovered.reusedRows()!=partial.verifiedRows() || sendCount.get()!=0)
            throw new IllegalStateException("Full page recovery must revalidate all keys with zero sends");
        var independent=task.equals("D020")?com.zoutrankil.questdbwithdata.operations.IndexDailyBasicIndependentReadback.verify(jdbc,ledgerPath,table,recovered.runId()):task.equals("D019")?IndexDailyMarketIndependentReadback.verify(jdbc,ledgerPath,table,recovered.runId()):task.equals("D017")?EtfFactorIndependentReadback.verify(jdbc,ledgerPath,table,recovered.runId()):task.equals("D016")?EtfShareIndependentReadback.verify(jdbc,ledgerPath,table,recovered.runId()):task.equals("D013")?EtfBasicSourceReadback.verify(jdbc,ledgerPath,table,recovered.runId())
                :MarketSourceReadbackVerifier.verify(jdbc,task,table,ledgerPath,recovered.runId());
        output.put("independentResumeReadback",independent);
        if(!"MATCHED".equals(independent.get("status")))throw new IllegalStateException("Independent resume source mismatch");
        var samples=sample.get(); if(samples==null || samples.isEmpty())throw new IllegalStateException("Real source samples required");
        var returnedFromSend=new AtomicBoolean();
        var unknown=new VerifiedBatchExecutor.Port<T,K>(){
            public void preflight()throws Exception{real.port().preflight();}
            public void send(List<T> rows)throws Exception{real.port().send(rows);returnedFromSend.set(true);throw new java.io.IOException("Injected loss after sender closed");}
            public List<T> readback(List<K> keys)throws Exception{return real.port().readback(keys);}
            public boolean walSettled()throws Exception{return real.port().walSettled();}
            public boolean uncertainSenderStopped(){return returnedFromSend.get();}
        };
        var policy=new VerifiedBatchExecutor.Policy(250,1048576,1,Duration.ofSeconds(10),Duration.ofMillis(100));
        var reconciled=new VerifiedBatchExecutor<T,K>(policy,real.codec(),unknown).execute(samples.iterator());
        output.put("postSendLostAcknowledgement",reconciled);
        if(reconciled.status()!=VerifiedBatchExecutor.Status.VERIFIED || reconciled.receipts().getFirst().delivery()!=VerifiedBatchExecutor.Delivery.UNKNOWN_RECONCILED)
            throw new IllegalStateException("Unknown acknowledgement requires stopped sender and exact live readback");
    }
}
