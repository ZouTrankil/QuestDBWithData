import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.service.*;
import io.questdb.client.QuestDB;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class EtfShareRevisionOperation {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath(),root=input.getParent();var json=JobDefinitionJson.mapper();var a=json.readTree(Files.readAllBytes(input));
        String table=a.path("table").asText();Path ledger=Path.of(a.path("ledger").asText());
        if(!a.path("task").asText().equals("D016")||!a.path("result").asText().equals("VERIFIED")||!table.matches("java_d016_etf_share_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned D016 source-verified target required");
        var report=new LinkedHashMap<String,Object>();report.put("task","D016");report.put("table",table);report.put("ledger",ledger.toString());report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
        var app=new SpringApplication(local.market.EtfShareOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("share-revision",Map.of("app.sync.ledger-path",ledger.toString(),"app.sync.etf-share-table",table,"app.tushare.concurrency",1))));
        try(var context=app.run("list-sync-jobs")){
            var jdbc=context.getBean(JdbcTemplate.class);var service=context.getBean(EtfShareJobService.class);
            LocalDate start=LocalDate.parse(a.path("requestWindows").path("initial").get(0).asText()),end=LocalDate.parse(a.path("requestWindows").path("incrementalTo").asText()),logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            var before=EtfShareAcceptanceOperation.snapshot(jdbc,table);report.put("beforeNullRevision",before);
            var row=jdbc.queryForMap("SELECT ts_code,fd_share,market,cast(timestamp AS long) AS micros,cast(update_time AS long) AS observed FROM "+table+" WHERE fund_type IS NULL ORDER BY timestamp,ts_code LIMIT 1");
            long micros=((Number)row.get("observed")).longValue();
            var original=new EtfShare(new EtfShareKey(row.get("ts_code").toString(),LocalDate.ofEpochDay(((Number)row.get("micros")).longValue()/86_400_000_000L)),row.get("fd_share")==null?null:((Number)row.get("fd_share")).doubleValue(),null,row.get("market").toString(),Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000),Math.floorMod(micros,1_000_000)*1000));
            var port=new EtfShareWritePort(table,service.targetId(),jdbc,context.getBean(QuestDB.class));
            var executor=new VerifiedBatchExecutor<EtfShare,EtfShareKey>(new VerifiedBatchExecutor.Policy(250,1048576,1,Duration.ofSeconds(10),Duration.ofMillis(100)),EtfShareWritePort.CODEC,port);
            var seeded=new EtfShare(original.key(),original.fdShare(),"controlled-old-classification",original.market(),original.updateTime());
            report.put("originalVerifiedRow",original);
            var injected=executor.execute(List.of(seeded).iterator());report.put("explicitOwnedNullRevisionSeed",injected);
            var cleared=executor.execute(List.of(original).iterator());report.put("providerNullRestored",cleared);
            if(injected.status()!=VerifiedBatchExecutor.Status.VERIFIED||cleared.status()!=VerifiedBatchExecutor.Status.VERIFIED||!before.equals(EtfShareAcceptanceOperation.snapshot(jdbc,table)))throw new IllegalStateException("Explicit nonnull-to-provider-null revision changed other values");
            report.put("nullRevisionIndependentReadback",EtfShareIndependentReadback.verify(jdbc,ledger,table,a.path("runs").get(3).path("runId").asText()));
            var backfill=service.run(service.plan(SyncJobDefinition.Mode.BACKFILL,start,start,logical));EtfShareAcceptanceOperation.require(backfill);report.put("freshBackfill",backfill);report.put("backfillReadback",EtfShareAcceptanceOperation.compare(jdbc,ledger,table,backfill));
            var incrementPlan=service.plan(SyncJobDefinition.Mode.INCREMENTAL,null,end,logical);report.put("incrementalPlanAfterBackfill",incrementPlan);
            if(!end.equals(incrementPlan.checkpointBefore()))throw new IllegalStateException("Backfill changed the incremental checkpoint");
            var increment=service.run(incrementPlan);EtfShareAcceptanceOperation.require(increment);report.put("incrementalAfterBackfill",increment);report.put("incrementalReadback",EtfShareAcceptanceOperation.compare(jdbc,ledger,table,increment));
            report.put("finalSnapshot",EtfShareAcceptanceOperation.snapshot(jdbc,table));report.put("status","VERIFIED");
        }catch(Exception failure){report.put("status","FAILED");report.put("failure",failure.toString());throw failure;}
        finally{report.put("finishedAt",Instant.now().toString());Files.writeString(root.resolve("D016-revision-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
}
