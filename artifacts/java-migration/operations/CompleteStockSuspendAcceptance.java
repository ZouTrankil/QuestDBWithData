import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;

class CompleteStockSuspendAcceptance {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Explicit prior market report required");
        Path previous=Path.of(args[0]).toAbsolutePath().normalize(),root=previous.getParent();
        var json=JobDefinitionJson.mapper();var report=(ObjectNode)json.readTree(Files.readAllBytes(previous));
        String task=report.path("task").asText(),table=report.path("table").asText();
        if(!task.equals("D011") || !table.matches("java_d011_stk_suspend_[0-9a-f]{32}") || report.path("runs").size()!=1)
            throw new IllegalArgumentException("Expected owned target and exactly three terminal runs");
        String command="stk-suspend";
        var definition=StockSuspendDataset.DEFINITION;
        Path ledger=root.resolve("sync-ledger.sqlite");var store=SyncRunLedger.openReadOnly(ledger);
        for(var run:report.path("runs"))if(store.get(run.path("runId").asText()).state()!=SyncRunState.VERIFIED)
            throw new IllegalStateException("Prior writer has not reached verified terminal state");
        var app=new SpringApplication(local.market.StockSuspendOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("continue-market",Map.of(
            "app.sync.ledger-path",ledger.toString(),"app.sync.stk-suspend-table",table,"app.tushare.concurrency",1))));
        try(var context=app.run("list-sync-jobs")){
            var jdbc=context.getBean(JdbcTemplate.class);
            String targetId=context.getBean(StockSuspendJobService.class).targetId();
            if(!targetId.equals(report.path("targetId").asText()))throw new IllegalStateException("Physical target changed");
            var snapshot=StockSuspendAcceptanceOperation.snapshot(jdbc,table,definition);
            if(!json.valueToTree(snapshot).equals(report.path("readbackAfterFirst")))throw new IllegalStateException("Prior verified target changed");
            LocalDate end=LocalDate.parse(report.path("requestWindows").path("incrementalTo").asText());
            var cli=context.getBean(CommandLineRunner.class);
            LocalDate start=LocalDate.parse(report.path("requestWindows").path("initial").get(0).asText());
            var independent=(ArrayNode)report.path("independentReadbacks");
            for(int repeat=0;repeat<2;repeat++){
                var repeated=NextMarketAcceptanceOperation.call(cli,new String[]{"run-"+command+"-job","--from",start.toString(),"--to",start.toString(),"--logical-date","2026-09-30","--mode","BACKFILL"});
                if(!repeated.path("state").asText().equals("VERIFIED"))throw new IllegalStateException("D011 repeat incomplete");
                ((ArrayNode)report.path("runs")).add(repeated);
                var comparison=StockSuspendIndependentReadback.verify(jdbc,ledger,table,repeated.path("runId").asText());
                if(!"MATCHED".equals(comparison.get("status")))throw new IllegalStateException("D011 raw comparison mismatch");
                independent.add(json.valueToTree(comparison));
                if(!snapshot.equals(StockSuspendAcceptanceOperation.snapshot(jdbc,table,definition)))throw new IllegalStateException("D011 repeated snapshot changed");
            }
            report.set("readbackAfterIdempotentRepeat",json.valueToTree(snapshot));
            var plan=NextMarketAcceptanceOperation.call(cli,new String[]{"plan-"+command+"-job","--to",end.toString(),"--logical-date","2026-09-30","--mode","INCREMENTAL"});
            report.set("incrementalPlan",plan);
            var result=NextMarketAcceptanceOperation.call(cli,new String[]{"run-"+command+"-job","--to",end.toString(),"--logical-date","2026-09-30","--mode","INCREMENTAL"});
            if(!result.path("state").asText().equals("VERIFIED") || result.path("sourceRows").asInt()<1 || result.path("sourceRows").asInt()!=result.path("verifiedRows").asInt())throw new IllegalStateException("Incremental source/readback incomplete");
            ((ArrayNode)report.path("runs")).add(result);
            var comparison=StockSuspendIndependentReadback.verify(jdbc,ledger,table,result.path("runId").asText());
            if(!"MATCHED".equals(comparison.get("status")))throw new IllegalStateException("D011 incremental raw mismatch");
            independent.add(json.valueToTree(comparison));
            report.set("readbackAfterIncremental",json.valueToTree(StockSuspendAcceptanceOperation.snapshot(jdbc,table,definition)));
            report.remove("failure");report.put("result","VERIFIED");report.put("finishedAt",Instant.now().toString());report.put("priorFailureReport",previous.toString());report.put("allPhysicalColumnsSelected",true);
            Files.writeString(root.resolve(task+"-live-acceptance.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println(task+" continuation VERIFIED: "+result);
        }catch(Exception failure){
            report.put("result","FAILED");report.put("failure",failure.toString());report.put("finishedAt",Instant.now().toString());
            Files.writeString(root.resolve(task+"-increment-failure-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));throw failure;
        }
    }
}
