import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;

class EtfBasicAcceptanceOperation {
    public static void main(String[] args)throws Exception {
        String nonce=UUID.randomUUID().toString().replace("-","");String table="java_d013_etf_basic_"+nonce;
        Path root=Path.of("artifacts/java-migration/market-live-"+nonce).toAbsolutePath();Files.createDirectories(root);
        Path ledger=root.resolve("sync-ledger.sqlite");var json=JobDefinitionJson.mapper();
        var report=new LinkedHashMap<String,Object>();report.put("task","D013");report.put("table",table);report.put("ledger",ledger.toString());
        report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
        report.put("modeRationale","fund_basic has no change/date cursor; explicit whole-directory SNAPSHOT refresh, fixed epoch key, frozen observation time");
        var runs=new ArrayList<SyncJobRunner.Result>();var comparisons=new ArrayList<Map<String,Object>>();report.put("runs",runs);report.put("independentReadbacks",comparisons);
        var app=new SpringApplication(local.market.EtfBasicOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("d013-acceptance",
            Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.etf-basic-table",table,"app.tushare.concurrency",1))));
        System.out.println("Evidence directory: "+root);
        try(var context=app.run("list-sync-jobs")){
            var jdbc=context.getBean(JdbcTemplate.class);jdbc.execute(EtfBasicDataset.createIsolatedTableSql(table));
            var service=context.getBean(EtfBasicJobService.class);var cli=context.getBean(CommandLineRunner.class);
            LocalDate logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            report.put("cliPlanPreview",NextMarketAcceptanceOperation.call(cli,new String[]{"plan-etf-basic-job","--logical-date",logical.toString()}));
            var plan=service.plan(logical);report.put("targetId",plan.targetId());report.put("frozenRequest",json.readTree(SyncRequestIdentity.snapshotJson(plan.request())));
            Map<String,Object> initial=null;
            for(int i=0;i<3;i++){
                var result=service.run(plan);require(result);runs.add(result);
                comparisons.add(EtfBasicSourceReadback.verify(jdbc,ledger,table,result.runId()));
                var state=snapshot(jdbc,table);if(initial==null){initial=state;report.put("readbackAfterFirst",state);}
                else if(!initial.equals(state))throw new IllegalStateException("Frozen snapshot rerun changed count or any physical field");
            }
            report.put("readbackAfterIdempotentRepeat",snapshot(jdbc,table));
            var refreshJson=NextMarketAcceptanceOperation.call(cli,new String[]{"run-etf-basic-job","--logical-date",logical.toString()});
            var refresh=json.treeToValue(refreshJson,SyncJobRunner.Result.class);require(refresh);runs.add(refresh);
            comparisons.add(EtfBasicSourceReadback.verify(jdbc,ledger,table,refresh.runId()));
            report.put("readbackAfterRefresh",snapshot(jdbc,table));report.put("refreshBehavior","Fresh source observation replaces same epoch keys; source-only values independently compared immediately after each run");
            report.put("result","VERIFIED");
        }catch(Exception failure){report.put("result","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D013-live-acceptance"+(report.get("result").equals("VERIFIED")?"":"-failure")+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
        System.out.println("D013 first write, two frozen repeats and fresh snapshot: VERIFIED");
    }
    static void require(SyncJobRunner.Result result){if(result.state()!=SyncRunState.VERIFIED||result.sourceRows()<1||result.sourceRows()!=result.verifiedRows())throw new IllegalStateException("Incomplete real snapshot: "+result);}
    static Map<String,Object> snapshot(JdbcTemplate jdbc,String table)throws Exception{
        String fields=String.join(",",EtfBasicSourceReadback.FIELDS.stream().map(f->"\""+f+"\"").toList());
        var rows=jdbc.queryForList("SELECT "+fields+",cast(timestamp AS long) AS timestamp,cast(update_time AS long) AS update_time FROM "+table+" ORDER BY ts_code,timestamp LIMIT 15001");
        if(rows.size()>15000)throw new IllegalStateException("Bounded owned target exceeded");
        var keys=new HashSet<String>();for(var row:rows)if(!keys.add(row.get("ts_code")+"/"+row.get("timestamp")))throw new IllegalStateException("Duplicate full key");
        return Map.of("rows",rows.size(),"duplicateKeys",0,"sha256",EtfBasicSourceReadback.hash(JobDefinitionJson.mapper().writeValueAsBytes(rows)));
    }
}
