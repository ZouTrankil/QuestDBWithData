import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Restores a previously cancelled real-source operation in a fresh JVM through the CLI. */
class MarketCliResumeOperation {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("One successful recovery report required");
        Path input=Path.of(args[0]).toAbsolutePath().normalize(),root=input.getParent();
        var json=JobDefinitionJson.mapper();var prior=json.readTree(Files.readAllBytes(input));
        String task=prior.path("task").asText(),table=prior.path("table").asText();
        var commands=Map.of("D007","daily","D008","daily-basic","D009","stk-factor","D010","stk-limit","D013","etf-basic","D014","etf-daily","D015","etf-adj","D016","etf-share");
        if(!prior.path("status").asText().equals("VERIFIED")||!commands.containsKey(task)
                ||!table.matches("java_"+task.toLowerCase()+"_[a-z_]+_[a-f0-9]{32}"))throw new IllegalArgumentException("Verified owned target required");
        Path ledger=Path.of(prior.path("ledger").asText());String cancelled=prior.path("cancelAfterVerifiedPage").path("runId").asText();
        var report=new LinkedHashMap<String,Object>();report.put("task",task);report.put("table",table);report.put("ledger",ledger.toString());
        report.put("priorRunId",cancelled);report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
        var app=new SpringApplication(task.equals("D016")?local.market.EtfShareOperationApplication.class:task.equals("D013")?local.market.EtfBasicOperationApplication.class:
                task.equals("D015")?local.market.EtfAdjOperationApplication.class:task.equals("D014")?local.market.EtfDailyOperationApplication.class:local.market.MarketOperationApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("cli-recovery",
                Map.of("app.sync.ledger-path",ledger.toString(),"app.sync."+commands.get(task)+"-table",table,"app.tushare.concurrency",1))));
        try(var context=app.run("list-sync-jobs")) {
            var jdbc=context.getBean(JdbcTemplate.class);long before=jdbc.queryForObject("SELECT count() FROM "+table,Long.class);
            var old=SyncRunLedger.openReadOnly(ledger).getRun(cancelled);
            report.put("rowsBefore",before);
            var result=NextMarketAcceptanceOperation.call(context.getBean(CommandLineRunner.class),new String[]{"run-"+commands.get(task)+"-job","--resume-from",cancelled});
            report.put("result",result);
            if(!result.path("state").asText().equals("VERIFIED")||result.path("reusedRows").asLong()!=prior.path("cancelAfterVerifiedPage").path("verifiedRows").asLong())
                throw new IllegalStateException("CLI did not reuse the entire previously verified page");
            String resumed=result.path("runId").asText();var restored=SyncRunLedger.openReadOnly(ledger).getRun(resumed);
            if(!old.targetId().equals(restored.targetId())||!SyncRequestIdentity.fingerprint(old.frozenJson(),old.targetId()).equals(SyncRequestIdentity.fingerprint(restored.frozenJson(),restored.targetId())))
                throw new IllegalStateException("CLI changed original frozen request");
            var independent=task.equals("D016")?EtfShareIndependentReadback.verify(jdbc,ledger,table,resumed):task.equals("D013")?EtfBasicSourceReadback.verify(jdbc,ledger,table,resumed):MarketSourceReadbackVerifier.verify(jdbc,task,table,ledger,resumed);
            report.put("independentReadback",independent);
            long after=jdbc.queryForObject("SELECT count() FROM "+table,Long.class);report.put("rowsAfter",after);
            if(before!=after||!"MATCHED".equals(independent.get("status")))throw new IllegalStateException("CLI recovery readback mismatch");
            report.put("status","VERIFIED");
        } catch(Exception failure){report.put("status","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve(task+"-cli-recovery-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
        System.out.println(task+" fresh-process CLI frozen-request recovery VERIFIED");
    }
}
