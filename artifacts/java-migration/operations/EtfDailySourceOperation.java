import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.mapper.EtfDailyMapper;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Source-only bounded inspection; does not create or write QuestDB tables. */
class EtfDailySourceOperation {
    public static void main(String[] args)throws Exception {
        Path root=Path.of("artifacts/java-migration/D014");Files.createDirectories(root);
        var app=new SpringApplication(local.market.MarketOperationApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        var reports=new ArrayList<Map<String,Object>>();
        try(var context=app.run("list-sync-jobs")){
            var source=new EtfDailySource(context.getBean(TusharePageService.class),new EtfDailyMapper(),root.resolve("source-probe"));
            for(String argument:args){
                var date=LocalDate.parse(argument);var page=source.fetch(date,()->false);
                reports.add(Map.of("date",date,"rows",page.rows().size(),"receipt",page.responseEvidence(),
                    "fingerprint",page.sourceFingerprint(),"sourceEndpoint","fund_daily","rowCap",EtfDailySource.API_ROW_CAP,
                    "formalTableMutated",false,"firstKey",page.rows().isEmpty()?"EMPTY":page.rows().getFirst().key().toString()));
            }
        }
        JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(root.resolve("source-probe-summary.json").toFile(),reports);
        System.out.println("D014 source probe complete: "+reports.stream().map(x->x.get("date")+":"+x.get("rows")).toList());
    }
}
