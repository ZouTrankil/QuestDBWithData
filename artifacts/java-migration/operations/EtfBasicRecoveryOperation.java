import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import io.questdb.client.QuestDB;
import java.nio.file.*;
import java.time.*;
import java.util.*;

class EtfBasicRecoveryOperation {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("D013 verified acceptance report required");
        Path acceptance=Path.of(args[0]).toAbsolutePath(),root=acceptance.getParent();var json=JobDefinitionJson.mapper();
        var accepted=json.readTree(Files.readAllBytes(acceptance));String table=accepted.path("table").asText();
        if(!accepted.path("result").asText().equals("VERIFIED")||!table.matches("java_d013_etf_basic_[0-9a-f]{32}"))throw new IllegalArgumentException("Owned verified table required");
        String nonce=UUID.randomUUID().toString();Path ledger=root.resolve("recovery-"+nonce+".sqlite");
        var report=new LinkedHashMap<String,Object>();report.put("task","D013");report.put("table",table);report.put("ledger",ledger.toString());
        report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
        var app=new SpringApplication(local.market.EtfBasicOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("d013-recovery",
            Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.etf-basic-table",table,"app.tushare.concurrency",1))));
        try(var context=app.run("list-sync-jobs")){
            var jdbc=context.getBean(JdbcTemplate.class);var service=context.getBean(EtfBasicJobService.class);
            var plan=service.plan(LocalDate.now(ZoneId.of("Asia/Shanghai")));
            var adapter=new EtfBasicSyncAdapter(context.getBean(TusharePageService.class),
                new EtfBasicWritePort(table,plan.targetId(),jdbc,context.getBean(QuestDB.class)),root.resolve("sync-evidence/recovery-"+nonce));
            long before=jdbc.queryForObject("SELECT count() FROM "+table,Long.class);report.put("rowsBefore",before);
            MarketRecoveryOperation.exercise("D013",table,jdbc,ledger,plan.request(),plan.targetId(),adapter,report);
            var cancelled=(SyncJobRunner.Result)report.get("cancelAfterVerifiedPage");
            var restored=service.restorePlan(cancelled.runId());
            if(!restored.targetId().equals(plan.targetId()) || !restored.observedAt().equals(plan.observedAt())
                    || !SyncRequestIdentity.snapshotJson(restored.request()).equals(SyncRequestIdentity.snapshotJson(plan.request())))
                throw new IllegalStateException("Restored observation/target/request differ");
            var resumed=NextMarketAcceptanceOperation.call(context.getBean(CommandLineRunner.class),
                new String[]{"run-etf-basic-job","--resume-from",cancelled.runId()});
            report.put("cliResume",resumed);
            if(!resumed.path("state").asText().equals("VERIFIED")||resumed.path("reusedRows").asLong()!=before)
                throw new IllegalStateException("CLI must restore frozen plan and ignore prior cancellation flag");
            report.put("independentCliResume",EtfBasicSourceReadback.verify(jdbc,ledger,table,resumed.path("runId").asText()));
            long after=jdbc.queryForObject("SELECT count() FROM "+table,Long.class);report.put("rowsAfter",after);
            if(before!=after)throw new IllegalStateException("Recovery changed same-key row count");
            report.put("status","VERIFIED");
        }catch(Exception failure){report.put("status","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D013-recovery-"+nonce+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
        System.out.println("D013 cancellation, zero-send recovery, CLI exact-plan recovery and unknown acknowledgement: VERIFIED");
    }
}
