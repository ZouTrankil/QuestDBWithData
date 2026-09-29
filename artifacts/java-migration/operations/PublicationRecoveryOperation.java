import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Injects acknowledgement loss immediately after an actual owned stage-to-target rename. */
class PublicationRecoveryOperation {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var accepted=json.readTree(Files.readAllBytes(input));
        String task=accepted.path("task").asText(),table=accepted.path("table").asText();Path ledger=Path.of(accepted.path("ledger").asText());
        if(!Set.of("D011","D012").contains(task)||!accepted.path("result").asText().equals("VERIFIED")||!table.matches("java_"+task.toLowerCase()+"_[a-z_]+_[0-9a-f]{32}"))throw new IllegalArgumentException("Owned verified target required");
        var output=new LinkedHashMap<String,Object>();output.put("task",task);output.put("table",table);output.put("startedAt",Instant.now().toString());output.put("formalTableMutated",false);output.put("humanReview","pending_review");
        output.put("injectedFault","Lost rename acknowledgement after the actual stage-to-owned-target rename; private JDBC datasource wrapper");
        var app=new SpringApplication(task.equals("D011")?local.market.StockSuspendOperationApplication.class:local.market.StockStOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("publication-recovery",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync."+(task.equals("D011")?"stk-suspend":"stk-st-daily")+"-table",table,"app.tushare.concurrency",1))));
        try(var context=app.run("list-sync-jobs")) {
            WindowRecoveryOperation.task=task;WindowRecoveryOperation.table=table;WindowRecoveryOperation.ledger=ledger;WindowRecoveryOperation.root=root;WindowRecoveryOperation.context=context;
            var jdbc=context.getBean(JdbcTemplate.class);var before=WindowRecoveryOperation.snapshot(jdbc);output.put("before",before);
            LocalDate start=LocalDate.parse(accepted.path("requestWindows").path("initial").get(0).asText());
            var scenarios=new ArrayList<Map<String,Object>>();output.put("scenarios",scenarios);
            for(boolean empty:List.of(false,true)) {
                LocalDate day=empty?start.minusDays(1):start;var request=WindowRecoveryOperation.plan(day,LocalDate.now(ZoneId.of("Asia/Shanghai")));
                String run="publication-fault-"+UUID.randomUUID();var fired=new AtomicBoolean();var ds=new DelegatingDataSource(jdbc.getDataSource()){
                    @Override public Connection getConnection()throws SQLException{return wrap(super.getConnection(),table,fired);}
                    @Override public Connection getConnection(String user,String password)throws SQLException{return wrap(super.getConnection(user,password),table,fired);}
                };
                var faulty=new JdbcTemplate(ds);var durable=new SyncRunLedger(ledger);
                var result=new SyncJobRunner<Object,Object>(durable,new DatasetIntervalLock(ledger)).run(run,null,request.parameters().get("targetId").toString(),request,WindowRecoveryOperation.adapter(run,request,faulty),()->false);
                var scenario=new LinkedHashMap<String,Object>();scenarios.add(scenario);scenario.put("empty",empty);scenario.put("runId",run);scenario.put("resultBeforeRecovery",result);scenario.put("durableStateBeforeRecovery",durable.get(run).state());scenario.put("faultFired",fired.get());
                if(!fired.get()||durable.get(run).state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("Lost rename acknowledgement must remain IN_DOUBT");
                if(task.equals("D011"))context.getBean(StockSuspendJobService.class).finishPublication(run,true);
                else scenario.put("finishResult",context.getBean(StockStDailyJobService.class).finishInterrupted(run,true));
                var expected=empty?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
                scenario.put("durableStateAfterRecovery",durable.get(run).state());
                if(durable.get(run).state()!=expected||!before.equals(WindowRecoveryOperation.snapshot(jdbc)))throw new IllegalStateException("Recovered publication state or target contents differ");
                var independent=task.equals("D011")?StockSuspendIndependentReadback.verify(jdbc,ledger,table,run):StockStIndependentReadback.verify(jdbc,ledger,table,run);scenario.put("independentReadback",independent);
                if(!"MATCHED".equals(independent.get("status")))throw new IllegalStateException("Recovered publication raw-source mismatch");
                if(task.equals("D011"))context.getBean(StockSuspendJobService.class).requireNoPendingPublication();else context.getBean(StockStDailyJobService.class).requireNoPendingPublication();
                if(new DatasetIntervalLock(ledger).findOwned(run,new DatasetIntervalLock.Scope(request.definition().datasetId(),request.from(),request.to()))!=null)throw new IllegalStateException("Recovered run still owns its interval lease");
            }
            output.put("status","VERIFIED");
        }catch(Exception failure){output.put("status","FAILED");output.put("failure",failure.toString());throw failure;}
        finally{output.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve(task+"-publication-recovery-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(output));}
        System.out.println(task+" nonempty and empty interrupted-publication recovery VERIFIED");
    }
    static Connection wrap(Connection actual,String table,AtomicBoolean fired){
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            try{Object value=method.invoke(actual,args);
                if(value instanceof Statement statement && method.getName().equals("createStatement"))return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(ignored,operation,parameters)->{
                    try{Object result=operation.invoke(statement,parameters);
                        if(operation.getName().equals("execute")&&parameters!=null&&parameters.length>0&&parameters[0] instanceof String sql
                                &&sql.toUpperCase(Locale.ROOT).startsWith("RENAME TABLE ")&&sql.endsWith(" TO \""+table+"\"")&&fired.compareAndSet(false,true))
                            throw new SQLException("Injected lost acknowledgement after stage rename", "08006");
                        return result;
                    }catch(InvocationTargetException failure){throw failure.getCause();}
                });return value;
            }catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
}
