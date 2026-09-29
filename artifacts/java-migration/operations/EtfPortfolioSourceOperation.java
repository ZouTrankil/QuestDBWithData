import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.mapper.EtfPortfolioMapper;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class EtfPortfolioSourceOperation {
    public static void main(String[] args)throws Exception {
        Path root=Path.of("artifacts/java-migration/D018/provider-diagnostic/"+UUID.randomUUID());Files.createDirectories(root);
        var report=new LinkedHashMap<String,Object>();report.put("task","D018");report.put("startedAt",Instant.now().toString());report.put("databaseWrites",0);
        var dates=new ArrayList<Map<String,Object>>();report.put("dates",dates);
        var app=new SpringApplication(local.market.EtfPortfolioOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        try(var context=app.run("list-sync-jobs")){
            var source=new EtfPortfolioSource(context.getBean(TusharePageService.class),new EtfPortfolioMapper(),root.resolve("source"));
            var requestedDates=args.length==0?List.of(LocalDate.of(2026,8,27),LocalDate.of(2026,8,28)):Arrays.stream(args).map(LocalDate::parse).toList();
            if(requestedDates.size()>2)throw new IllegalArgumentException("At most two explicit source dates required");
            for(var date:requestedDates){
                var result=source.fetch(date,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
                dates.add(Map.of("date",date,"rows",result.rows().size(),"sourcePages",result.sourcePages(),"runnerChunks",result.chunks().size(),"receipt",result.receipt(),"fingerprint",result.fingerprint()));
            }
            report.put("status","SOURCE_COMPLETE");
        }catch(Exception failure){report.put("status","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("source-operation.json"),JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
}
