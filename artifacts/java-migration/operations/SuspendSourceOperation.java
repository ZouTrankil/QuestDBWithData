import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
/** Bounded source-only observation; no target writes or completion declaration. */
class SuspendSourceOperation {
    public static void main(String[] args)throws Exception {
        Path root=Path.of("artifacts/java-migration/D011");Files.createDirectories(root);
        var app=new SpringApplication(local.market.MarketOperationApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        var reports=new ArrayList<Map<String,Object>>();
        try(var context=app.run("list-sync-jobs")){
            var source=new StockSuspendSource(context.getBean(TusharePageService.class),root.resolve("source-probe"));
            for(String argument:args){
                var date=LocalDate.parse(argument);var page=source.fetch(new StockSuspendSource.Query(date),()->false);
                reports.add(Map.of("date",date,"rows",page.rows().size(),"receipt",page.receipt(),
                    "fingerprint",page.fingerprint(),"sourceEndpoint","suspend_d","formalTableMutated",false));
            }
        }
        JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(root.resolve("source-probe-summary.json").toFile(),reports);
        System.out.println("D011 source probe complete: "+reports.stream().map(x->x.get("date")+":"+x.get("rows")).toList());
    }
}
