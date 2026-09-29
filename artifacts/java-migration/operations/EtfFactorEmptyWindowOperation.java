import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import com.zoutrankil.questdbwithdata.domain.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class EtfFactorEmptyWindowOperation {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));
        String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
        if(!a.path("task").asText().equals("D017")||!a.path("result").asText().equals("VERIFIED")||!table.matches("java_d017_etf_factor_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned verified D017 required");
        var report=new LinkedHashMap<String,Object>();report.put("task","D017");report.put("table",table);report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);
        var app=new SpringApplication(local.market.EtfFactorOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("factor-empty",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.etf-factor-table",table))));
        try(var context=app.run("list-sync-jobs")){
            var jdbc=context.getBean(JdbcTemplate.class);var before=EtfFactorAcceptanceOperation.snapshot(jdbc,table,EtfFactorDataset.DEFINITION);report.put("before",before);
            String date=LocalDate.parse(a.path("requestWindows").path("initial").get(0).asText()).minusDays(1).toString();
            var run=EtfFactorAcceptanceOperation.call(context.getBean(CommandLineRunner.class),new String[]{"run-etf-factor-job","--from",date,"--to",date,"--logical-date",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),"--mode","BACKFILL"});report.put("run",run);
            if(!run.path("state").asText().equals("VERIFIED_EMPTY")||run.path("sourceRows").asLong()!=0||run.path("verifiedRows").asLong()!=0)throw new IllegalStateException("Frozen closed-session window must be VERIFIED_EMPTY");
            var readback=EtfFactorIndependentReadback.verify(jdbc,ledger,table,run.path("runId").asText());report.put("independentReadback",readback);
            var after=EtfFactorAcceptanceOperation.snapshot(jdbc,table,EtfFactorDataset.DEFINITION);report.put("after",after);
            if(!before.equals(after)||!"MATCHED".equals(readback.get("status")))throw new IllegalStateException("Empty range changed existing values");report.put("status","VERIFIED");
        }catch(Exception failure){report.put("status","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(input.getParent().resolve("D017-empty-window-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
}
