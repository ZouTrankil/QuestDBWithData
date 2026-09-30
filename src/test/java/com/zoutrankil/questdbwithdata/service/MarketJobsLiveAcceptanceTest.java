package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.QuestDbWriteChecks;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.config.TushareProperties;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, bounded live acceptance for D007-D009; targets are always newly-owned java_d00x tables. */
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class MarketJobsLiveAcceptanceTest {
    private static final ObjectMapper JSON = JobDefinitionJson.mapper();
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    @Test void dailyDailyBasicAndStockFactorUseIsolatedTablesAndVerifyIncrementalReadback() throws Exception {
        String startTask=Optional.ofNullable(System.getenv("MARKET_ACCEPTANCE_FROM")).orElse("D007");
        List<String> selectedTasks=switch(startTask) {
            case "D007" -> List.of("D007","D008","D009");
            case "D008" -> List.of("D008","D009");
            case "D009" -> List.of("D009");
            default -> throw new IllegalArgumentException("MARKET_ACCEPTANCE_FROM must be D007, D008 or D009");
        };
        String nonce=UUID.randomUUID().toString().replace("-","");
        Path evidenceRoot=Path.of("artifacts/java-migration","market-live-"+nonce).toAbsolutePath();
        Files.createDirectories(evidenceRoot);
        String dailyTable="java_d007_daily_"+nonce;
        String basicTable="java_d008_basic_"+nonce;
        String factorTable="java_d009_factor_"+nonce;
        Path ledgerPath=evidenceRoot.resolve("sync-ledger.sqlite");
        var app=new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "market-live-acceptance",Map.of(
                        "app.sync.ledger-path",ledgerPath.toString(),
                        "app.sync.daily-table",dailyTable,
                        "app.sync.daily-basic-table",basicTable,
                        "app.sync.stk-factor-table",factorTable,
                        "app.tushare.concurrency",1))));

        var report=new LinkedHashMap<String,Object>();
        report.put("acceptanceId",nonce);
        report.put("selectedTasks",selectedTasks);
        report.put("startedAt",Instant.now().toString());
        report.put("formalTablesMutated",false);
        report.put("humanReview","pending_review");
        report.put("isolatedTables",List.of(dailyTable,basicTable,factorTable));
        var tablesCreated=new ArrayList<String>();
        report.put("isolatedTablesCreated",tablesCreated);
        report.put("sourceSyncMayHaveStarted",false);
        report.put("ledger",evidenceRoot.relativize(ledgerPath).toString());

        try(var context=app.run("list-sync-jobs")) {
            var jdbc=context.getBean(JdbcTemplate.class);
            var cli=context.getBean(CommandLineRunner.class);
            var tushare=context.getBean(TushareProperties.class);
            assertNotNull(tushare.getToken());assertFalse(tushare.getToken().isBlank(),"Tushare credential must be configured");
            long connectedObjects=jdbc.queryForObject("SELECT count() FROM tables()",Long.class);
            var endpointRates=tushare.effectiveEndpointLimits();
            var rateEvidence=Map.of("globalPerMinute",tushare.getGlobalPerMinute(),
                    "endpointPerMinute",tushare.getEndpointPerMinute(),
                    "daily",endpointRates.get("daily"),"daily_basic",endpointRates.get("daily_basic"),
                    "stk_factor_pro",endpointRates.get("stk_factor_pro"));
            assertTrue(endpointRates.get("daily")<=450 && endpointRates.get("daily_basic")<=20
                    && endpointRates.get("stk_factor_pro")<=25,"configured rates must respect audited source ceilings");
            report.put("preflight",Map.of("questdbObjectsReadable",connectedObjects,
                    "tushareCredentialConfigured",true,"effectiveRateLimitsPerMinute",rateEvidence,
                    "liveEndpoints","daily, daily_basic, stk_factor_pro"));
            if(selectedTasks.contains("D007")) {
                createIsolatedTable(jdbc,dailyTable,DailyDataset.DEFINITION);
                tablesCreated.add(dailyTable);
            }
            if(selectedTasks.contains("D008")) {
                createIsolatedTable(jdbc,basicTable,DailyBasicDataset.definition(basicTable));
                tablesCreated.add(basicTable);
            }
            createIsolatedTable(jdbc,factorTable,StockFactorDataset.DEFINITION);
            tablesCreated.add(factorTable);

            LocalDate logicalDate=LocalDate.now(MARKET_ZONE);
            var dailyDates=recentOpenDates(jdbc,logicalDate,2);
            report.put("sourceSyncMayHaveStarted",true);
            if(selectedTasks.contains("D007")) {
                var dailyReport=runDaily(cli,jdbc,ledgerPath,evidenceRoot,dailyTable,dailyDates,logicalDate);
                report.put("D007",dailyReport);
                writeReport(evidenceRoot,"D007-live-acceptance.json",dailyReport);
            }

            var basicDates=recentOpenDates(jdbc,logicalDate,2);
            if(selectedTasks.contains("D008")) {
                var basicReport=runDailyBasic(cli,jdbc,ledgerPath,evidenceRoot,basicTable,basicDates,logicalDate);
                report.put("D008",basicReport);
                writeReport(evidenceRoot,"D008-live-acceptance.json",basicReport);
            }

            var factorDates=recentOpenDates(jdbc,logicalDate,2);
            var factorReport=runStockFactor(cli,jdbc,ledgerPath,evidenceRoot,factorTable,factorDates,logicalDate);
            report.put("D009",factorReport);
            writeReport(evidenceRoot,"D009-live-acceptance.json",factorReport);

            report.put("finishedAt",Instant.now().toString());
            writeReport(evidenceRoot,"batch-live-acceptance.json",report);
        } catch(Exception | AssertionError failure) {
            report.put("failedAt",Instant.now().toString());
            report.put("result",Boolean.FALSE.equals(report.get("sourceSyncMayHaveStarted"))
                    ?"blocked_before_source_or_ddl":"failed_during_or_after_live_acceptance");
            report.put("failureType",failure.getClass().getName());
            report.put("failure",String.valueOf(failure.getMessage()));
            report.put("failureCategory",failureCategory(failure));
            report.put("isolatedTablesCreated",List.copyOf(tablesCreated));
            if(Boolean.FALSE.equals(report.get("sourceSyncMayHaveStarted"))) report.put("sourceRequestsSent",0);
            writeReport(evidenceRoot,"batch-live-acceptance-failure.json",report);
            throw failure;
        }
    }

    private static Map<String,Object> runDaily(CommandLineRunner cli,JdbcTemplate jdbc,Path ledger,
            Path root,String table,List<LocalDate> dates,LocalDate logicalDate) throws Exception {
        var definition=DailyDataset.DEFINITION;
        return runDataset(cli,jdbc,ledger,root,"D007","data.daily",table,definition,dates,logicalDate,
                "plan-daily-job","run-daily-job",true);
    }

    private static Map<String,Object> runDailyBasic(CommandLineRunner cli,JdbcTemplate jdbc,Path ledger,
            Path root,String table,List<LocalDate> dates,LocalDate logicalDate) throws Exception {
        var definition=DailyBasicDataset.definition(table);
        return runDataset(cli,jdbc,ledger,root,"D008","data.daily_basic",table,definition,dates,logicalDate,
                "plan-daily-basic-job","run-daily-basic-job",false);
    }

    private static Map<String,Object> runStockFactor(CommandLineRunner cli,JdbcTemplate jdbc,Path ledger,
            Path root,String table,List<LocalDate> dates,LocalDate logicalDate) throws Exception {
        var definition=StockFactorDataset.DEFINITION;
        return runDataset(cli,jdbc,ledger,root,"D009","data.stk_factor",table,definition,dates,logicalDate,
                "plan-stk-factor-job","run-stk-factor-job",false);
    }

    private static Map<String,Object> runDataset(CommandLineRunner cli,JdbcTemplate jdbc,Path ledger,
            Path root,String task,String job,String table,DatasetDefinition definition,List<LocalDate> dates,
            LocalDate logicalDate,String planCommand,String runCommand,boolean dailyFromAnchor)
            throws Exception {
        LocalDate initial=dates.get(0),later=dates.get(1);
        var base=new ArrayList<String>();
        base.add("--from");base.add(initial.toString());
        base.addAll(List.of("--to",initial.toString(),"--logical-date",logicalDate.toString(),"--mode","INCREMENTAL"));
        JsonNode planned=invoke(cli,planCommand,base.toArray(String[]::new));
        assertEquals("PLANNED",planned.path("status").asText(),planned.toPrettyString());
        assertFalse(planned.path("executed").asBoolean());
        assertFalse(planned.path("dataVerified").asBoolean());
        String targetId=planned.path("targetId").asText();
        assertFalse(targetId.isBlank());

        JsonNode first=invoke(cli,runCommand,base.toArray(String[]::new));
        assertEquals("VERIFIED",first.path("state").asText(),first.toPrettyString());
        assertTrue(first.path("sourceRows").asInt()>0,first.toPrettyString());
        assertEquals(first.path("sourceRows").asInt(),first.path("verifiedRows").asInt(),first.toPrettyString());
        String firstRun=first.path("runId").asText();
        var afterFirst=readback(jdbc,table,definition);
        assertEquals(first.path("verifiedRows").asInt(),afterFirst.rows(),"full-key full-column QuestDB readback");
        assertEquals(0,afterFirst.duplicateKeys());
        assertTrue(QuestDbWriteChecks.walSettled(jdbc,table));

        var backfill=new ArrayList<String>();
        backfill.add("--from");backfill.add(initial.toString());
        backfill.addAll(List.of("--to",initial.toString(),"--logical-date",logicalDate.toString(),"--mode","BACKFILL"));
        JsonNode repeat=invoke(cli,runCommand,backfill.toArray(String[]::new));
        assertEquals("VERIFIED",repeat.path("state").asText(),repeat.toPrettyString());
        assertTrue(repeat.path("sourceRows").asInt()>0,repeat.toPrettyString());
        assertEquals(repeat.path("sourceRows").asInt(),repeat.path("verifiedRows").asInt());
        var afterRepeat=readback(jdbc,table,definition);
        assertEquals(afterFirst,afterRepeat,"same-range real-source backfill must be idempotent");
        JsonNode repeatAgain=invoke(cli,runCommand,backfill.toArray(String[]::new));
        assertEquals("VERIFIED",repeatAgain.path("state").asText(),repeatAgain.toPrettyString());
        assertEquals(repeatAgain.path("sourceRows").asInt(),repeatAgain.path("verifiedRows").asInt());
        assertEquals(afterRepeat,readback(jdbc,table,definition),"second same-range rerun must preserve all values");

        var next=new ArrayList<String>();
        if(dailyFromAnchor) { next.add("--from");next.add(initial.toString()); }
        next.addAll(List.of("--to",later.toString(),"--logical-date",logicalDate.toString(),"--mode","INCREMENTAL"));
        JsonNode nextResult=invoke(cli,runCommand,next.toArray(String[]::new));
        assertTrue(Set.of("VERIFIED","VERIFIED_EMPTY").contains(nextResult.path("state").asText()),nextResult.toPrettyString());
        assertEquals(nextResult.path("sourceRows").asInt(),nextResult.path("verifiedRows").asInt(),nextResult.toPrettyString());
        var afterIncrement=readback(jdbc,table,definition);
        assertTrue(afterIncrement.rows()>=afterRepeat.rows());
        assertEquals(0,afterIncrement.duplicateKeys());

        var ledgerStore=SyncRunLedger.openReadOnly(ledger);
        for(String id:List.of(firstRun,repeat.path("runId").asText(),repeatAgain.path("runId").asText(),nextResult.path("runId").asText()))
            assertEquals(SyncRunState.VERIFIED,ledgerStore.get(id).state());

        var result=new LinkedHashMap<String,Object>();
        result.put("task",task);result.put("job",job);result.put("table",table);result.put("targetId",targetId);
        result.put("requestWindows",Map.of("initial",List.of(initial.toString(),initial.toString()),
                "incrementalTo",later.toString()));
        result.put("runs",List.of(first,repeat,repeatAgain,nextResult));
        result.put("readbackAfterFirst",afterFirst);result.put("readbackAfterIdempotentRepeat",afterRepeat);
        result.put("readbackAfterIncremental",afterIncrement);result.put("allPhysicalColumnsSelected",true);
        result.put("fullRowVerification","VERIFIED slice readback matched every returned key and physical column");
        result.put("isolatedEvidenceDirectory",root.resolve("sync-evidence").toString());
        result.put("formalTableMutated",false);result.put("humanReview","pending_review");
        return result;
    }

    private static JsonNode invoke(CommandLineRunner cli,String command,String... options) throws Exception {
        var args=new ArrayList<String>();args.add(command);args.addAll(List.of(options));
        var prior=System.out;var bytes=new ByteArrayOutputStream();
        try(var capture=new PrintStream(bytes,true,StandardCharsets.UTF_8)) {
            System.setOut(capture);
            cli.run(new DefaultApplicationArguments(args.toArray(String[]::new)));
        } finally { System.setOut(prior); }
        return JSON.readTree(bytes.toString(StandardCharsets.UTF_8));
    }

    private static List<LocalDate> recentOpenDates(JdbcTemplate jdbc,LocalDate logicalDate,int count) {
        var rows=jdbc.query("SELECT cal_date FROM exchange_calendar WHERE exchange='SSE' AND is_open=1 "
                        +"AND cal_date<? ORDER BY cal_date DESC LIMIT ?",
                (rs,n)->rs.getTimestamp(1).toLocalDateTime().toLocalDate(),
                Timestamp.valueOf(logicalDate.atStartOfDay()),count);
        if(rows.size()!=count) throw new IllegalStateException("Recent complete SSE calendar coverage required for live acceptance");
        var dates=new ArrayList<>(rows);Collections.reverse(dates);return List.copyOf(dates);
    }

    private static void createIsolatedTable(JdbcTemplate jdbc,String table,DatasetDefinition definition) throws Exception {
        DatasetDefinition.identifier(table);
        Integer exists=jdbc.queryForObject("SELECT count() FROM tables() WHERE table_name=?",Integer.class,table);
        assertEquals(0,exists,"refusing to reuse an existing QuestDB table");
        String columns=String.join(",",definition.columns().stream()
                .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList());
        jdbc.execute("CREATE TABLE \""+table+"\" ("+columns+") TIMESTAMP("+definition.designatedTimestamp()+") "
                +"PARTITION BY YEAR WAL DEDUP UPSERT KEYS("+String.join(",",definition.dedupKey())+")");
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        while(!QuestDbWriteChecks.walSettled(jdbc,table)) {
            if(System.nanoTime()>deadline) throw new IllegalStateException("Isolated target WAL did not settle: "+table);
            Thread.sleep(50);
        }
    }

    private static Readback readback(JdbcTemplate jdbc,String table,DatasetDefinition definition) {
        String columns=String.join(",",definition.columns().stream().map(c->"\""+c.storageName()+"\"").toList());
        String sql="SELECT "+columns+" FROM \""+table+"\" ORDER BY "+String.join(",",definition.dedupKey());
        var digest=sha256();long[] count={0};long[] duplicates={0};String[] priorKey={null};
        jdbc.query(sql,rs->{
            String key=String.valueOf(rs.getObject(definition.dedupKey().get(0)))+"\u0000"
                    +canonical(rs.getObject(definition.dedupKey().get(1)));
            if(key.equals(priorKey[0])) duplicates[0]++;
            priorKey[0]=key;
            for(var column:definition.columns()) {
                Object value=rs.getObject(column.storageName());
                digest.update(canonical(value).getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            }
            digest.update((byte)'\n');count[0]++;
        });
        return new Readback(count[0],HexFormat.of().formatHex(digest.digest()),duplicates[0]);
    }

    private static String canonical(Object value) {
        if(value==null) return "<null>";
        if(value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if(value instanceof java.math.BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }
    private static String failureCategory(Throwable failure) {
        for(Throwable current=failure;current!=null;current=current.getCause()) {
            if(current.getClass().getName().equals("org.postgresql.util.PSQLException")
                    && current.getMessage()!=null
                    && current.getMessage().toLowerCase(Locale.ROOT).contains("invalid username/password"))
                return "questdb_authentication_rejected";
        }
        return failure.getClass().getSimpleName();
    }
    private static MessageDigest sha256() {
        try{return MessageDigest.getInstance("SHA-256");}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void writeReport(Path root,String name,Object value) throws Exception {
        JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve(name).toFile(),value);
    }
    private record Readback(long rows,String sha256,long duplicateKeys) {}
}
