import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.mapper.EtfShareMapper;
import io.questdb.client.QuestDB;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class EtfShareRecoveryOperation {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));
        String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
        if(!a.path("task").asText().equals("D016")||!a.path("result").asText().equals("VERIFIED")||!table.matches("java_d016_etf_share_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned verified D016 report required");
        var report=new LinkedHashMap<String,Object>();report.put("task","D016");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
        var app=new SpringApplication(local.market.EtfShareOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("share-recovery",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.etf-share-table",table,"app.tushare.concurrency",1))));
        try(var context=app.run("list-sync-jobs")){
            var jdbc=context.getBean(JdbcTemplate.class);var service=context.getBean(EtfShareJobService.class);
            String priorId=a.path("runs").get(3).path("runId").asText();
            var prior=json.readTree(SyncRunLedger.openReadOnly(ledger).getRun(priorId).frozenJson());
            var day=LocalDate.parse(a.path("requestWindows").path("initial").get(0).asText());
            var plan=EtfShareAcceptanceOperation.withObservation(service.plan(SyncJobDefinition.Mode.BACKFILL,day,day,LocalDate.now(ZoneId.of("Asia/Shanghai"))),prior.path("parameters").path("observedAt").asText());
            var before=EtfShareAcceptanceOperation.snapshot(jdbc,table);report.put("before",before);report.put("rowsBefore",before.get("rows"));
            Path evidence=root.resolve("sync-evidence").resolve("recovery-"+UUID.randomUUID());
            var adapter=new EtfShareSyncAdapter(new EtfShareSource(context.getBean(TusharePageService.class),new EtfShareMapper(),evidence.resolve("source")),
                new EtfShareTradingDates(context.getBean(ExchangeCalendarReadRepository.class)),new EtfShareWritePort(table,plan.targetId(),jdbc,context.getBean(QuestDB.class)),evidence);
            MarketRecoveryOperation.exercise("D016",table,jdbc,ledger,plan.request(),plan.targetId(),adapter,report);
            var after=EtfShareAcceptanceOperation.snapshot(jdbc,table);report.put("after",after);report.put("rowsAfter",after.get("rows"));
            if(!before.equals(after))throw new IllegalStateException("Recovery changed source or frozen observation values");
            report.put("status","VERIFIED");
        }catch(Exception failure){report.put("status","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D016-recovery-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
}
