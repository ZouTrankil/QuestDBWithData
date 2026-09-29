import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import io.questdb.client.QuestDB;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
class EmptyWindowOperation {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var accepted=json.readTree(Files.readAllBytes(input));
        String task=accepted.path("task").asText(),table=accepted.path("table").asText();Path ledger=Path.of(accepted.path("ledger").asText());
        if(!Set.of("D011","D012").contains(task)||!accepted.path("result").asText().equals("VERIFIED")||!table.matches("java_"+task.toLowerCase()+"_[a-z_]+_[0-9a-f]{32}"))throw new IllegalArgumentException("Owned verified target required");
        var output=new LinkedHashMap<String,Object>();output.put("task",task);output.put("table",table);output.put("ledger",ledger.toString());output.put("startedAt",Instant.now().toString());output.put("formalTableMutated",false);output.put("humanReview","pending_review");
        var app=new SpringApplication(task.equals("D011")?local.market.StockSuspendOperationApplication.class:local.market.StockStOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("empty-window",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync."+(task.equals("D011")?"stk-suspend":"stk-st-daily")+"-table",table,"app.tushare.concurrency",1))));
        try(var context=app.run("list-sync-jobs")) {
            WindowRecoveryOperation.task=task;WindowRecoveryOperation.table=table;WindowRecoveryOperation.ledger=ledger;WindowRecoveryOperation.root=root;WindowRecoveryOperation.context=context;
            var jdbc=context.getBean(JdbcTemplate.class);var qdb=context.getBean(QuestDB.class);
            var prior=args.length==2?json.readTree(Files.readAllBytes(Path.of(args[1]))):null;
            Map<String,Object> before=prior==null?WindowRecoveryOperation.snapshot(jdbc):json.convertValue(prior.path("before"),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
            output.put("before",before);
            LocalDate day=LocalDate.parse(accepted.path("requestWindows").path("initial").get(0).asText()).minusDays(1);
            var calendar=jdbc.queryForList("SELECT is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date=cast(? AS TIMESTAMP)",day.toString());
            if(calendar.size()!=1||((Number)calendar.getFirst().get("is_open")).intValue()!=0)throw new IllegalStateException("Explicitly closed SSE date required");
            if(prior!=null&&(!prior.path("table").asText().equals(table)||!prior.path("task").asText().equals(task)||!prior.path("status").asText().equals("FAILED")))throw new IllegalArgumentException("Matching failed owned injection report required");
            var request=prior==null?WindowRecoveryOperation.plan(day,LocalDate.now(ZoneId.of("Asia/Shanghai"))):FrozenRunRequest.restore(ledger,prior.path("run").path("runId").asText(),task.equals("D011")?StockSuspendSyncJobOwner.DEFINITION:StockStDailySyncJobOwner.DEFINITION).request();
            String physical=request.parameters().get("physicalTargetId").toString();
            if(prior==null){
                if(task.equals("D011"))new StockSuspendWritePort(table,jdbc,qdb,physical).send(List.of(new StockSuspend(new StockSuspendKey("999999.SZ",day),1)));
                else new StockStDailyWritePort(table,physical,jdbc,qdb).send(List.of(new StockStDaily("999999.SZ",day,1)));
            } else output.put("priorFailedInjection",args[1]);
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,table)){if(System.nanoTime()>deadline)throw new IllegalStateException("Seed WAL did not settle");Thread.sleep(50);}
            output.put("injectedStaleRow",Map.of("synthetic",true,"ts_code","999999.SZ","date",day,"purpose","Exercise complete empty-window retraction while preserving actual outside rows"));
            String run="empty-window-"+UUID.randomUUID();
            var result=new SyncJobRunner<Object,Object>(new SyncRunLedger(ledger),new DatasetIntervalLock(ledger)).run(run,null,request.parameters().get("targetId").toString(),request,WindowRecoveryOperation.adapter(run,request),()->false);
            output.put("run",result);output.put("after",WindowRecoveryOperation.snapshot(jdbc));
            if(result.state()!=SyncRunState.VERIFIED_EMPTY||!json.valueToTree(before).equals(json.valueToTree(WindowRecoveryOperation.snapshot(jdbc))))throw new IllegalStateException("Empty source failed to retract stale window while preserving outside target");
            var independent=task.equals("D011")?StockSuspendIndependentReadback.verify(jdbc,ledger,table,run):StockStIndependentReadback.verify(jdbc,ledger,table,run);
            output.put("independentReadback",independent);
            if(!"MATCHED".equals(independent.get("status")))throw new IllegalStateException("Independent empty readback mismatch");
            var sample=jdbc.queryForList("SELECT ts_code,cast(timestamp AS long) AS micros FROM "+table+" ORDER BY timestamp,ts_code LIMIT 2");
            if(sample.size()!=2)throw new IllegalStateException("Two preserved real source rows required for lost acknowledgement operation");
            if(task.equals("D011")){
                var rows=sample.stream().map(r->new StockSuspend(new StockSuspendKey(r.get("ts_code").toString(),date(r)),1)).toList();
                output.put("lostAcknowledgement",lostAck(StockSuspendWritePort.CODEC,new StockSuspendWritePort(table,jdbc,qdb,context.getBean(StockSuspendJobService.class).physicalTargetId()),rows));
            }else{
                var rows=sample.stream().map(r->new StockStDaily(r.get("ts_code").toString(),date(r),1)).toList();
                output.put("lostAcknowledgement",lostAck(StockStDailyWritePort.CODEC,new StockStDailyWritePort(table,context.getBean(StockStDailyJobService.class).physicalTargetId(),jdbc,qdb),rows));
            }
            if(!json.valueToTree(before).equals(json.valueToTree(WindowRecoveryOperation.snapshot(jdbc))))throw new IllegalStateException("Lost-ack replay changed values");
            output.put("status","VERIFIED");
        }catch(Exception failure){output.put("status","FAILED");output.put("failure",failure.toString());throw failure;}
        finally{output.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve(task+"-empty-window-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(output));}
        System.out.println(task+" empty authoritative retraction, outside preservation and lost-ack readback VERIFIED");
    }
    static LocalDate date(Map<String,Object> r){return LocalDate.ofEpochDay(((Number)r.get("micros")).longValue()/86_400_000_000L);}
    static <T,K> VerifiedBatchExecutor.Result lostAck(VerifiedBatchExecutor.Codec<T,K> codec,VerifiedBatchExecutor.Port<T,K> actual,List<T> rows)throws Exception {
        var closed=new AtomicBoolean();var port=new VerifiedBatchExecutor.Port<T,K>(){
            public void preflight()throws Exception{actual.preflight();}
            public void send(List<T> values)throws Exception{actual.send(values);closed.set(true);throw new java.io.IOException("Injected acknowledgement loss after closed sender");}
            public List<T> readback(List<K> keys)throws Exception{return actual.readback(keys);}
            public boolean walSettled()throws Exception{return actual.walSettled();}
            public boolean uncertainSenderStopped(){return closed.get();}
        };
        var result=new VerifiedBatchExecutor<T,K>(new VerifiedBatchExecutor.Policy(250,1048576,1,Duration.ofSeconds(10),Duration.ofMillis(100)),codec,port).execute(rows.iterator());
        if(result.status()!=VerifiedBatchExecutor.Status.VERIFIED||result.receipts().getFirst().delivery()!=VerifiedBatchExecutor.Delivery.UNKNOWN_RECONCILED)throw new IllegalStateException("Actual lost acknowledgement was not reconciled");
        return result;
    }
}
