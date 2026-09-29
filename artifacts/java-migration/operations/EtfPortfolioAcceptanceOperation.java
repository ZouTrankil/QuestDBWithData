import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
class EtfPortfolioAcceptanceOperation {
    public static void main(String[] args)throws Exception {
        String nonce=UUID.randomUUID().toString().replace("-","");String table="java_d018_etf_portfolio_"+nonce;
        Path root=Path.of("artifacts/java-migration/market-live-"+nonce).toAbsolutePath();Files.createDirectories(root);Path ledger=root.resolve("sync-ledger.sqlite");
        var json=JobDefinitionJson.mapper();var report=new LinkedHashMap<String,Object>();
        report.put("task","D018");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
        var app=new SpringApplication(local.market.EtfPortfolioOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("share-acceptance",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.etf-portfolio-table",table,"app.tushare.concurrency",1))));
        System.out.println("Evidence directory: "+root);
        try(var context=app.run("list-sync-jobs")){
            var jdbc=context.getBean(JdbcTemplate.class);var service=context.getBean(EtfPortfolioJobService.class);
            jdbc.execute(EtfPortfolioDataset.createIsolatedTableSql(table));
            LocalDate logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            var dates=List.of(LocalDate.of(2026,8,27),LocalDate.of(2026,8,26));
            if(dates.size()!=2)throw new IllegalStateException("Two completed sessions required");
            LocalDate start=dates.get(1),end=dates.get(0);report.put("requestWindows",Map.of("initial",List.of(start,start),"incrementalTo",end));
            var firstPlan=service.plan(SyncJobDefinition.Mode.INCREMENTAL,start,start,logical);report.put("initialPlan",firstPlan);report.put("targetId",firstPlan.targetId());
            var runs=new ArrayList<SyncJobRunner.Result>();var comparisons=new ArrayList<Map<String,Object>>();report.put("runs",runs);report.put("independentReadbacks",comparisons);
            var first=service.run(firstPlan);require(first);runs.add(first);comparisons.add(compare(jdbc,ledger,table,first));
            var baseline=snapshot(jdbc,table);report.put("readbackAfterFirst",baseline);
            var repeat=withObservation(service.plan(SyncJobDefinition.Mode.BACKFILL,start,start,logical),firstPlan.request().parameters().get("observedAt").toString());
            report.put("repeatPlan",repeat);report.put("observationPolicy","Two repeats reuse the first frozen observation; fresh incremental run freezes its own timestamp. All nine columns independently compared immediately after each run.");
            for(int i=0;i<2;i++){
                var run=service.run(repeat);require(run);runs.add(run);comparisons.add(compare(jdbc,ledger,table,run));
                if(!baseline.equals(snapshot(jdbc,table)))throw new IllegalStateException("Frozen repeat changed full nine-field snapshot");
            }
            report.put("readbackAfterIdempotentRepeat",snapshot(jdbc,table));
            var incrementPlan=service.plan(SyncJobDefinition.Mode.INCREMENTAL,null,end,logical);report.put("incrementalPlan",incrementPlan);
            var increment=service.run(incrementPlan);require(increment);runs.add(increment);comparisons.add(compare(jdbc,ledger,table,increment));
            report.put("readbackAfterIncremental",snapshot(jdbc,table));report.put("result","VERIFIED");
        }catch(Exception failure){report.put("result","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D018-live-acceptance"+("VERIFIED".equals(report.get("result"))?"":"-failure")+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
    static EtfPortfolioJobService.Plan withObservation(EtfPortfolioJobService.Plan plan,String observed)throws Exception{
        var r=plan.request();var params=new LinkedHashMap<String,Object>(r.parameters());params.put("observedAt",observed);
        var frozen=r.definition().freeze(r.mode(),params,r.from(),r.to(),r.logicalDate());
        return new EtfPortfolioJobService.Plan(frozen,plan.targetId(),plan.checkpointBefore(),plan.checkpointAnchor(),plan.targetMinDate(),plan.targetMaxDate(),plan.bootstrap());
    }
    static Map<String,Object> compare(JdbcTemplate jdbc,Path ledger,String table,SyncJobRunner.Result run)throws Exception {
        var result=EtfPortfolioIndependentReadback.verify(jdbc,ledger,table,run.runId());
        if(!"MATCHED".equals(result.get("status")))throw new IllegalStateException("Independent nine-column source readback failed");return result;
    }
    static void require(SyncJobRunner.Result result){if(result.state()!=SyncRunState.VERIFIED||result.sourceRows()<1||result.sourceRows()!=result.verifiedRows())throw new IllegalStateException("Nonempty verified source required: "+result);}
    static Map<String,Object> snapshot(JdbcTemplate jdbc,String table)throws Exception {
        if(!table.matches("java_d018_etf_portfolio_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned table required");
        var rows=jdbc.queryForList("SELECT ts_code,cast(ann_date AS long) AS ann_micros,cast(end_date AS long) AS end_micros,symbol,mkv,amount,stk_mkv_ratio,stk_float_ratio,cast(update_time AS long) AS observed_micros FROM "+table+" ORDER BY ann_date,end_date,ts_code,symbol LIMIT 512001");
        if(rows.size()>512000)throw new IllegalStateException("Two-date bounded target exceeded");
        var keys=new HashSet<String>();for(var row:rows)if(!keys.add(row.get("ts_code")+"/"+row.get("ann_micros")+"/"+row.get("end_micros")+"/"+row.get("symbol")))throw new IllegalStateException("Duplicate full key");
        return Map.of("rows",rows.size(),"duplicateKeys",0,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JobDefinitionJson.mapper().writeValueAsBytes(rows))));
    }
}
