import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Bounded actual backfill followed by an incremental run, preserving the original cursor. */
class EtfPortfolioRevisionOperation {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));
        String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
        if(!a.path("task").asText().equals("D018")||!a.path("result").asText().equals("VERIFIED")||!table.matches("java_d018_etf_portfolio_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned verified D018 target required");
        var report=new LinkedHashMap<String,Object>();report.put("task","D018");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
        var app=new SpringApplication(local.market.EtfPortfolioOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("portfolio-revision",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.etf-portfolio-table",table,"app.tushare.concurrency",1))));
        try(var context=app.run("list-sync-jobs")) {
            var jdbc=context.getBean(JdbcTemplate.class);var service=context.getBean(EtfPortfolioJobService.class);
            LocalDate start=LocalDate.parse(a.path("requestWindows").path("initial").get(0).asText()),end=LocalDate.parse(a.path("requestWindows").path("incrementalTo").asText()),logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            var backfill=service.run(service.plan(SyncJobDefinition.Mode.BACKFILL,start,start,logical));EtfPortfolioAcceptanceOperation.require(backfill);report.put("freshBackfill",backfill);report.put("backfillReadback",EtfPortfolioAcceptanceOperation.compare(jdbc,ledger,table,backfill));
            var plan=service.plan(SyncJobDefinition.Mode.INCREMENTAL,null,end,logical);report.put("incrementalPlanAfterBackfill",plan);
            if(!end.equals(plan.checkpointBefore()))throw new IllegalStateException("Backfill advanced or reset the incremental checkpoint");
            var increment=service.run(plan);EtfPortfolioAcceptanceOperation.require(increment);report.put("incrementalAfterBackfill",increment);report.put("incrementalReadback",EtfPortfolioAcceptanceOperation.compare(jdbc,ledger,table,increment));
            report.put("finalSnapshot",EtfPortfolioAcceptanceOperation.snapshot(jdbc,table));report.put("status","VERIFIED");
        }catch(Exception failure){report.put("status","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D018-revision-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
}
