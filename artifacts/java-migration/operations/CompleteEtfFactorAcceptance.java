import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import com.zoutrankil.questdbwithdata.domain.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Continues the real first verified write after repairing the independent reader's date parameter. */
class CompleteEtfFactorAcceptance {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));
        String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
        if(!a.path("task").asText().equals("D017")||!table.matches("java_d017_etf_factor_[a-f0-9]{32}")||a.path("runs").size()!=1||!a.path("runs").get(0).path("state").asText().equals("VERIFIED"))throw new IllegalArgumentException("Owned D017 first VERIFIED source/write required");
        var report=new LinkedHashMap<String,Object>();for(String key:List.of("task","job","table","ledger","startedAt","formalTableMutated","humanReview","requestWindows","initialPlan","targetId"))report.put(key,a.path(key));
        report.put("completionBasis",input.toString());report.put("completionStartedAt",Instant.now().toString());
        var app=new SpringApplication(local.market.EtfFactorOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("factor-completion",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.etf-factor-table",table,"app.tushare.concurrency",1))));
        try(var context=app.run("list-sync-jobs")){
            var jdbc=context.getBean(JdbcTemplate.class);var cli=context.getBean(CommandLineRunner.class);
            var runs=new ArrayList<JsonNode>();var comparisons=new ArrayList<Map<String,Object>>();report.put("runs",runs);report.put("independentReadbacks",comparisons);
            var first=a.path("runs").get(0);runs.add(first);comparisons.add(EtfFactorAcceptanceOperation.compare(jdbc,ledger,table,first));
            var baseline=EtfFactorAcceptanceOperation.snapshot(jdbc,table,EtfFactorDataset.DEFINITION);report.put("readbackAfterFirst",baseline);
            String start=a.path("requestWindows").path("initial").get(0).asText(),end=a.path("requestWindows").path("incrementalTo").asText(),logical=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            for(int i=0;i<2;i++){
                var run=EtfFactorAcceptanceOperation.call(cli,new String[]{"run-etf-factor-job","--from",start,"--to",start,"--logical-date",logical,"--mode","BACKFILL"});require(run);runs.add(run);comparisons.add(EtfFactorAcceptanceOperation.compare(jdbc,ledger,table,run));
                if(!baseline.equals(EtfFactorAcceptanceOperation.snapshot(jdbc,table,EtfFactorDataset.DEFINITION)))throw new IllegalStateException("Same-date repeat changed full 89-column snapshot");
            }
            report.put("readbackAfterIdempotentRepeat",EtfFactorAcceptanceOperation.snapshot(jdbc,table,EtfFactorDataset.DEFINITION));
            report.put("incrementalPlan",EtfFactorAcceptanceOperation.call(cli,new String[]{"plan-etf-factor-job","--to",end,"--logical-date",logical}));
            var increment=EtfFactorAcceptanceOperation.call(cli,new String[]{"run-etf-factor-job","--to",end,"--logical-date",logical});require(increment);runs.add(increment);comparisons.add(EtfFactorAcceptanceOperation.compare(jdbc,ledger,table,increment));
            report.put("readbackAfterIncremental",EtfFactorAcceptanceOperation.snapshot(jdbc,table,EtfFactorDataset.DEFINITION));report.put("result","VERIFIED");
        }catch(Exception failure){report.put("result","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(input.getParent().resolve("VERIFIED".equals(report.get("result"))?"D017-live-acceptance.json":"D017-completion-failure-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
    static void require(JsonNode run){if(!run.path("state").asText().equals("VERIFIED")||run.path("sourceRows").asLong()<1||run.path("sourceRows").asLong()!=run.path("verifiedRows").asLong())throw new IllegalStateException("Verified nonempty run required");}
}
