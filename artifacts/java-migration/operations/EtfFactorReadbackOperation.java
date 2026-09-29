import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
class EtfFactorReadbackOperation {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));
        String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
        if(!a.path("task").asText().equals("D017")||!table.matches("java_d017_etf_factor_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned D017 report required");
        var app=new SpringApplication(local.market.EtfFactorOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("factor-readback",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.etf-factor-table",table))));
        try(var context=app.run("list-sync-jobs")){
            var run=a.path("runs").get(0);var result=EtfFactorIndependentReadback.verify(context.getBean(JdbcTemplate.class),ledger,table,run.path("runId").asText());
            Files.writeString(input.getParent().resolve("D017-independent-diagnostic-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            System.out.println(result.get("status")+" source="+result.get("sourceRows")+" actual="+result.get("actualRows")+" mismatched="+result.get("mismatchedValues")+" missing="+result.get("missingKeys"));
        }
    }
}
