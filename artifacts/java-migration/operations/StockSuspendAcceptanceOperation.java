import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import com.zoutrankil.questdbwithdata.domain.*;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit operational acceptance on a fresh, owned isolated table. */
class StockSuspendAcceptanceOperation {
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !Set.of("D011").contains(args[0]))throw new IllegalArgumentException("D011 required");
        String task=args[0], nonce=UUID.randomUUID().toString().replace("-","");
        Path root=Path.of("artifacts/java-migration/market-live-"+nonce).toAbsolutePath(); Files.createDirectories(root);
        String table="java_d011_stk_suspend_"+nonce;
        String command="stk-suspend";
        var definition=StockSuspendDataset.DEFINITION;
        var json=JobDefinitionJson.mapper();var report=new LinkedHashMap<String,Object>();
        report.put("task",task);report.put("job","data."+definition.datasetId());report.put("table",table);
        report.put("startedAt",Instant.now().toString());report.put("formalTableMutated",false);report.put("humanReview","pending_review");
        Path ledger=root.resolve("sync-ledger.sqlite"); report.put("ledger",ledger.toString());
        var app=new SpringApplication(local.market.StockSuspendOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("next-market-operation",Map.of(
            "app.sync.ledger-path",ledger.toString(),"app.sync.stk-suspend-table",table,"app.tushare.concurrency",1))));
        System.out.println("Evidence directory: "+root);
        try(var context=app.run("list-sync-jobs")) {
            var jdbc=context.getBean(JdbcTemplate.class);var cli=context.getBean(CommandLineRunner.class);
            var columns=definition.columns().stream().map(c->"\""+c.storageName()+"\" "+c.storageType()).toList();
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",columns)+") TIMESTAMP(timestamp) PARTITION BY DAY WAL DEDUP UPSERT KEYS(ts_code,timestamp)");
            LocalDate logical=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            var dates=jdbc.query("SELECT cast(cal_date as long) AS micros FROM exchange_calendar WHERE exchange='SSE' AND is_open=1 AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date DESC LIMIT 2",
                (rs,index)->com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp.fromStorageEpoch(rs.getLong("micros"),com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.MICROS).date(),logical.toString());
            if(dates.size()!=2)throw new IllegalStateException("Two completed exchange sessions required");
            LocalDate start=dates.get(1),end=dates.get(0);
            report.put("requestWindows",Map.of("initial",List.of(start,start),"incrementalTo",end));
            var planned=call(cli,options("plan-"+command+"-job",start,start,logical,"INCREMENTAL"));
            report.put("initialPlan",planned); report.put("targetId",planned.path("targetId").asText());
            var runs=new ArrayList<JsonNode>();report.put("runs",runs);
            var independent=new ArrayList<Map<String,Object>>();report.put("independentReadbacks",independent);
            var first=call(cli,options("run-"+command+"-job",start,start,logical,"INCREMENTAL"));requireVerified(first);runs.add(first);independent.add(StockSuspendIndependentReadback.verify(jdbc,ledger,table,first.path("runId").asText()));
            var initial=snapshot(jdbc,table,definition);report.put("readbackAfterFirst",initial);
            for(int i=0;i<2;i++){
                var repeated=call(cli,options("run-"+command+"-job",start,start,logical,"BACKFILL"));requireVerified(repeated);runs.add(repeated);independent.add(StockSuspendIndependentReadback.verify(jdbc,ledger,table,repeated.path("runId").asText()));
                if(!initial.equals(snapshot(jdbc,table,definition)))throw new IllegalStateException("Same window rerun changed target count/digest");
            }
            report.put("readbackAfterIdempotentRepeat",snapshot(jdbc,table,definition));
            report.put("incrementalPlan",call(cli,options("plan-"+command+"-job",null,end,logical,"INCREMENTAL")));
            var increment=call(cli,options("run-"+command+"-job",null,end,logical,"INCREMENTAL"));requireVerified(increment);runs.add(increment);independent.add(StockSuspendIndependentReadback.verify(jdbc,ledger,table,increment.path("runId").asText()));
            if(independent.stream().anyMatch(r->!"MATCHED".equals(r.get("status"))))throw new IllegalStateException("Independent source readback differs");
            report.put("readbackAfterIncremental",snapshot(jdbc,table,definition));report.put("allPhysicalColumnsSelected",true);
            report.put("result","VERIFIED");report.put("finishedAt",Instant.now().toString());
            Files.writeString(root.resolve(task+"-live-acceptance.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println(task+" nonempty, twice repeated, incremental: VERIFIED");
        } catch(Exception failure){
            report.put("result","FAILED");report.put("failure",failure.toString());report.put("finishedAt",Instant.now().toString());
            Files.writeString(root.resolve(task+"-live-acceptance-failure.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));throw failure;
        }
    }
    private static String[] options(String cmd,LocalDate from,LocalDate to,LocalDate logical,String mode){
        var values=new ArrayList<String>(List.of(cmd,"--to",to.toString(),"--logical-date",logical.toString(),"--mode",mode));
        if(from!=null){values.add("--from");values.add(from.toString());}return values.toArray(String[]::new);
    }
    static JsonNode call(CommandLineRunner cli,String[] args)throws Exception{
        var bytes=new ByteArrayOutputStream();var original=System.out;
        try(var capture=new PrintStream(bytes,true,StandardCharsets.UTF_8)){
            System.setOut(capture);cli.run(new DefaultApplicationArguments(args));
        }finally{System.setOut(original);}
        String output=bytes.toString(StandardCharsets.UTF_8).trim();
        return JobDefinitionJson.mapper().readTree(output);
    }
    private static void requireVerified(JsonNode run){
        if(!run.path("state").asText().equals("VERIFIED") || run.path("sourceRows").asLong()<1
            || run.path("sourceRows").asLong()!=run.path("verifiedRows").asLong())throw new IllegalStateException("Nonempty source and complete field readback required: "+run);
    }
    static Map<String,Object> snapshot(JdbcTemplate jdbc,String table,DatasetDefinition definition)throws Exception{
        String columns=String.join(",",definition.columns().stream().map(c->c.storageName().equals("timestamp")?"cast(timestamp as long) AS trade_date":"\""+c.storageName()+"\"").toList());
        var rows=jdbc.queryForList("SELECT "+columns+" FROM "+table+" ORDER BY ts_code,trade_date LIMIT 200001");
        if(rows.size()>200000)throw new IllegalStateException("Acceptance table exceeds bounded snapshot");
        var keys=new HashSet<String>();var digest=MessageDigest.getInstance("SHA-256");
        for(var row:rows){
            String key=row.get("ts_code")+"/"+row.get("trade_date");if(!keys.add(key))throw new IllegalStateException("Duplicate complete key");
            byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(row);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
        }
        return Map.of("rows",rows.size(),"duplicateKeys",0,"sha256",HexFormat.of().formatHex(digest.digest()));
    }
}
