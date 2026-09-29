import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.*;
class EtfShareFieldProbeOperation {
    public static void main(String[] args)throws Exception {
        var app=new SpringApplication(local.market.EtfShareOperationApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        try(var context=app.run("list-sync-jobs")) {
            var path=new EtfShareProviderFieldsDiagnostic(context.getBean(TusharePageService.class),
                Path.of("artifacts/java-migration/D016/provider-diagnostic"))
                .probe(LocalDate.of(2026,9,28),()->Thread.currentThread().isInterrupted());
            System.out.println("Bounded provider field evidence: "+path);
        }
    }
}
