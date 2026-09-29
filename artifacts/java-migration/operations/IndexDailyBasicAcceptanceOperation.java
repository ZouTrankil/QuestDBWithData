import com.zoutrankil.questdbwithdata.operations.IndexDailyBasicIndependentReadback;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;

/** Executes the two distinct index routes on one owned table, with independent per-code checkpoints. */
class IndexDailyBasicAcceptanceOperation {
    public static void main(String[] args)throws Exception {
        String nonce=UUID.randomUUID().toString().replace("-","");String table="java_d020_index_daily_basic_"+nonce;
        Path root=Path.of("artifacts/java-migration/market-live-"+nonce).toAbsolutePath();Files.createDirectories(root);Path ledger=root.resolve("sync-ledger.sqlite");
        var json=JobDefinitionJson.mapper();var report=new LinkedHashMap<String,Object>();
        report.put("task","D020");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
        var app=new SpringApplication(local.market.IndexDailyBasicOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("index-market-acceptance",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.index-daily-basic-table",table,"app.tushare.concurrency",1))));
        System.out.println("Evidence directory: "+root);
        try(var context=app.run("list-sync-jobs")){
            var jdbc=context.getBean(JdbcTemplate.class);var service=context.getBean(IndexDailyBasicJobService.class);
            jdbc.execute(IndexDailyBasicDataset.createIsolatedTableSql(table));
            LocalDate logical=LocalDate.now(ZoneId.of("Asia/Shanghai")),start=LocalDate.of(2026,9,24),initialEnd=LocalDate.of(2026,9,28),end=LocalDate.of(2026,9,29);
            report.put("targetId",service.targetId());report.put("requestWindows",Map.of("initial",List.of(start,initialEnd),"incrementalTo",end));
            var routes=new ArrayList<Map<String,Object>>();report.put("routes",routes);
            for(String code:List.of("000300.SH","000016.SH","399006.SZ","000905.SH","000852.SH")){
                var r=new LinkedHashMap<String,Object>();routes.add(r);r.put("code",code);
                var plan=service.plan(SyncJobDefinition.Mode.INCREMENTAL,code,start,initialEnd,logical);r.put("initialPlan",plan);
                if(plan.checkpointBefore()!=null||!plan.bootstrap())throw new IllegalStateException("Another code's checkpoint leaked into bootstrap");
                var runs=new ArrayList<SyncJobRunner.Result>();var comparisons=new ArrayList<Map<String,Object>>();r.put("runs",runs);r.put("independentReadbacks",comparisons);
                var first=service.run(plan);require(first);runs.add(first);comparisons.add(compare(jdbc,ledger,table,first));
                var baseline=snapshot(jdbc,table,code);r.put("readbackAfterFirst",baseline);
                var repeat=service.plan(SyncJobDefinition.Mode.BACKFILL,code,start,initialEnd,logical);
                for(int i=0;i<2;i++){
                    var run=service.run(repeat);require(run);runs.add(run);comparisons.add(compare(jdbc,ledger,table,run));
                    if(!baseline.equals(snapshot(jdbc,table,code)))throw new IllegalStateException("Frozen rerun changed full 12-column code snapshot");
                }
                var incrementPlan=service.plan(SyncJobDefinition.Mode.INCREMENTAL,code,null,end,logical);r.put("incrementalPlan",incrementPlan);
                if(!initialEnd.equals(incrementPlan.checkpointBefore()))throw new IllegalStateException("Wrong per-code checkpoint");
                var increment=service.run(incrementPlan);require(increment);runs.add(increment);comparisons.add(compare(jdbc,ledger,table,increment));
                r.put("readbackAfterIncremental",snapshot(jdbc,table,code));
            }
            report.put("wholeTarget",snapshot(jdbc,table,null));report.put("result","VERIFIED");
        }catch(Exception failure){report.put("result","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D020-live-acceptance"+("VERIFIED".equals(report.get("result"))?"":"-failure")+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
    static Map<String,Object> compare(JdbcTemplate jdbc,Path ledger,String table,SyncJobRunner.Result run)throws Exception {
        var result=IndexDailyBasicIndependentReadback.verify(jdbc,ledger,table,run.runId());
        if(!"MATCHED".equals(result.get("status"))){Files.writeString(ledger.getParent().resolve("D020-readback-failure-"+UUID.randomUUID()+".json"),JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));throw new IllegalStateException("Independent 12-column source readback failed");}return result;
    }
    static void require(SyncJobRunner.Result result){if(result.state()!=SyncRunState.VERIFIED||result.sourceRows()<1||result.sourceRows()!=result.verifiedRows())throw new IllegalStateException("Nonempty verified source required: "+result);}
    static Map<String,Object> snapshot(JdbcTemplate jdbc,String table,String code)throws Exception {
        if(!table.matches("java_d020_index_daily_basic_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned table required");
        String sql="SELECT ts_code,cast(trade_date AS long) AS trade_micros,total_mv,float_mv,total_share,float_share,free_share,turnover_rate,turnover_rate_f,pe,pe_ttm,pb FROM "+table+(code==null?"":" WHERE ts_code=?")+" ORDER BY ts_code,trade_date LIMIT 200001";
        var rows=code==null?jdbc.queryForList(sql):jdbc.queryForList(sql,code);
        if(rows.size()>200000)throw new IllegalStateException("Bounded target exceeded");
        var keys=new HashSet<String>();for(var row:rows)if(!keys.add(row.get("ts_code")+"/"+row.get("trade_micros")))throw new IllegalStateException("Duplicate full key");
        return Map.of("rows",rows.size(),"duplicateKeys",0,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JobDefinitionJson.mapper().writeValueAsBytes(rows))));
    }
}
