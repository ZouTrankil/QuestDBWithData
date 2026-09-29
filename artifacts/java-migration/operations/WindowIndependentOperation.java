import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
class WindowIndependentOperation {
    public static void main(String[] args)throws Exception {
        Path file=Path.of(args[0]).toAbsolutePath();var json=JobDefinitionJson.mapper();var input=json.readTree(Files.readAllBytes(file));
        String task=input.path("task").asText(),table=input.path("table").asText();Path ledger=Path.of(input.path("ledger").asText());
        if(!task.equals("D012")||!input.path("result").asText().equals("VERIFIED"))throw new IllegalArgumentException("Verified D012 report required");
        var app=new SpringApplication(local.market.StockStOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("window-independent",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.stk-st-daily-table",table))));
        try(var context=app.run("list-sync-jobs")) {
            var results=new ArrayList<Map<String,Object>>();
            for(var run:input.path("runs"))results.add(StockStIndependentReadback.verify(context.getBean(JdbcTemplate.class),ledger,table,run.path("runId").asText()));
            var report=Map.of("task",task,"table",table,"comparisons",results,"formalTableMutated",false);
            Files.writeString(file.getParent().resolve(task+"-independent-source-readback.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            if(results.stream().anyMatch(r->!"MATCHED".equals(r.get("status"))))throw new IllegalStateException("Independent source mismatch");
            System.out.println(task+" all four raw-source/physical-window comparisons MATCHED");
        }
    }
}
