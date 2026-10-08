package com.zoutrankil.batch;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import org.quartz.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real temporary SQLite files; no legacy data sources/components are loaded. */
class SqliteRuntimeTest {
    String url;
    @TempDir Path archive;
    @TempDir Path databaseDir;
    @BeforeEach void isolatedDatabase() { url="jdbc:sqlite:"+databaseDir.resolve("metadata.sqlite").toAbsolutePath(); }
    ConfigurableApplicationContext start() { return start(null); }
    ConfigurableApplicationContext start(StageExecutor fixture) {
        var application=new SpringApplication(BatchApplication.class);
        if (fixture!=null) application.addInitializers(context -> ((org.springframework.context.support.GenericApplicationContext)context)
                .registerBean("testOnlyFixtureExecutor",StageExecutor.class,() -> fixture,definition -> definition.setPrimary(true)));
        application.setDefaultProperties(Map.of("spring.config.name","batch-runtime","jdb.metadata-url",url,
                "jdb.api-token","test-token-with-at-least-24-characters",
                "jdb.api-port","0","jdb.archive-root",archive.toString(),"jdb.scheduling-enabled","false",
                "jdb.core-source-fanout-enabled",Boolean.toString(fixture==null)));
        return application.run("--jdb.metadata-url="+url,
                "--jdb.api-port=0","--jdb.api-token=test-token-with-at-least-24-characters","--jdb.archive-root="+archive,
                "--jdb.scheduling-enabled=false","--jdb.core-source-fanout-enabled="+(fixture==null));
    }
    private static JdbcTemplate jdbc(ConfigurableApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class));
    }
    @Test void sqliteLaunchRestartQuartzPauseAndApiAuth() throws Exception {
        String id=ContractsTest.request("sqlite-launch").instanceId();
        try (var context=start()) {
            var launches=context.getBean(LaunchService.class); var ledger=context.getBean(SqliteLedger.class);
            var result=launches.launch(ContractsTest.request("sqlite-launch"));
            assertEquals("BLOCKED",result.get("business_state"));
            assertTrue(result.get("steps").toString().contains("core-source-plan-invalid"));
            assertEquals(0,jdbc(context).queryForObject("SELECT count(*) FROM source_probe",Integer.class));
            assertEquals(0,jdbc(context).queryForObject("SELECT count(*) FROM batch_job_instance",Integer.class));
            assertEquals("BLOCKED",launches.launch(ContractsTest.request("sqlite-retry")).get("business_state"));
            assertEquals(0,jdbc(context).queryForObject("SELECT count(*) FROM batch_job_instance",Integer.class));
            Scheduler scheduler=context.getBean(Scheduler.class);
            assertTrue(scheduler.checkExists(new JobKey("post_close","jdb")));
            assertTrue(scheduler.checkExists(new JobKey("source_cyq_perf","jdb")));
            assertTrue(scheduler.checkExists(new JobKey("source_us_tbr","jdb")));
            assertEquals("0 10 21 ? * MON-FRI",((CronTrigger)scheduler.getTrigger(new TriggerKey("source_cyq_perf","jdb"))).getCronExpression());
            assertEquals(Trigger.TriggerState.PAUSED,scheduler.getTriggerState(new TriggerKey("source_cyq_perf","jdb")));
            assertEquals("0 35 21 ? * MON-FRI",((CronTrigger)scheduler.getTrigger(new TriggerKey("source_us_tbr","jdb"))).getCronExpression());
            assertEquals(Trigger.TriggerState.PAUSED,scheduler.getTriggerState(new TriggerKey("source_us_tbr","jdb")));
            assertTrue(scheduler.checkExists(new JobKey("post_close_recovery","jdb")));
            assertTrue(scheduler.checkExists(new JobKey("post_close_recovery_final","jdb")));
            assertEquals("0 0/15 19-23 ? * MON-FRI",((CronTrigger)scheduler.getTrigger(new TriggerKey("post_close_recovery","jdb"))).getCronExpression());
            assertEquals("0 55 23 ? * MON-FRI",((CronTrigger)scheduler.getTrigger(new TriggerKey("post_close_recovery_final","jdb"))).getCronExpression());
            assertEquals(Trigger.TriggerState.PAUSED,scheduler.getTriggerState(new TriggerKey("post_close_recovery","jdb")));
            assertEquals(Trigger.TriggerState.PAUSED,scheduler.getTriggerState(new TriggerKey("post_close_recovery_final","jdb")));
            scheduler.pauseJob(new JobKey("post_close","jdb"));
            int port=context.getBean(ManagementServer.class).port();
            var http=HttpClient.newHttpClient();
            var endpoint=URI.create("http://localhost:"+port+"/v1/runs/"+id);
            assertEquals(401,http.send(HttpRequest.newBuilder(endpoint).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var response=http.send(HttpRequest.newBuilder(endpoint).header("Authorization","Bearer test-token-with-at-least-24-characters").GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode()); assertTrue(response.body().contains("BLOCKED"));
            var auth="Bearer test-token-with-at-least-24-characters";
            var health=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/health")).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,health.statusCode());assertTrue(health.body().contains("\"status\":\"UP\""));assertTrue(health.body().contains("\"metadataStore\":\"SQLite\""));
            var tasks=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/tasks")).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,tasks.statusCode());assertTrue(tasks.body().contains("source_exchange_calendar"));assertTrue(tasks.body().contains("source_fina_mainbz"));assertTrue(tasks.body().contains("source_fina_audit"));assertTrue(tasks.body().contains("source_dividend"));assertTrue(tasks.body().contains("source_share_float"));assertTrue(tasks.body().contains("source_shibor"));assertTrue(tasks.body().contains("source_shibor_lpr"));assertTrue(tasks.body().contains("source_hibor"));assertTrue(tasks.body().contains("source_cn_cpi"));assertTrue(tasks.body().contains("source_cn_ppi"));assertTrue(tasks.body().contains("source_cn_pmi"));assertTrue(tasks.body().contains("source_cn_m"));assertTrue(tasks.body().contains("source_cn_gdp"));assertTrue(tasks.body().contains("source_fut_daily"));assertTrue(tasks.body().contains("source_fut_settle"));assertTrue(tasks.body().contains("source_fut_mapping"));assertTrue(tasks.body().contains("source_ft_limit"));assertTrue(tasks.body().contains("source_fut_holding"));assertTrue(tasks.body().contains("source_fut_basic"));assertTrue(tasks.body().contains("source_etf_basic"));assertTrue(tasks.body().contains("source_disclosure_date"));assertTrue(tasks.body().contains("source_ths_index"));assertTrue(tasks.body().contains("source_etf_share"));assertTrue(tasks.body().contains("source_us_tbr"));
            var sources=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/sources")).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,sources.statusCode());assertTrue(sources.body().contains("exchange_calendar-v1"));assertTrue(sources.body().contains("fina_mainbz-v1"));assertTrue(sources.body().contains("fina_audit-v1"));assertTrue(sources.body().contains("dividend-v1"));assertTrue(sources.body().contains("share_float-v1"));assertTrue(sources.body().contains("shibor-v1"));assertTrue(sources.body().contains("shibor_lpr-v1"));assertTrue(sources.body().contains("hibor-v1"));assertTrue(sources.body().contains("cn_cpi-v1"));assertTrue(sources.body().contains("cn_ppi-v1"));assertTrue(sources.body().contains("cn_pmi-v1"));assertTrue(sources.body().contains("cn_m-v1"));assertTrue(sources.body().contains("cn_gdp-v1"));assertTrue(sources.body().contains("fut_daily-v1"));assertTrue(sources.body().contains("fut_settle-v1"));assertTrue(sources.body().contains("fut_mapping-v1"));assertTrue(sources.body().contains("ft_limit-v1"));assertTrue(sources.body().contains("fut_holding-v1"));assertTrue(sources.body().contains("fut_basic-v1"));assertTrue(sources.body().contains("etf_basic-v1"));assertTrue(sources.body().contains("disclosure_date-v1"));assertTrue(sources.body().contains("ths_index-v1"));assertTrue(sources.body().contains("etf_share-v1"));assertTrue(sources.body().contains("us_tbr-v1"));
            var metrics=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/metrics")).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,metrics.statusCode());assertTrue(metrics.body().contains("businessInstancesByState"));assertTrue(metrics.body().contains("\"state\":\"BLOCKED\""));
            var prometheus=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/metrics/prometheus")).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,prometheus.statusCode());assertTrue(prometheus.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
            assertTrue(prometheus.body().contains("jdb_business_instances{state=\"BLOCKED\"} 1"));
            assertTrue(prometheus.body().contains("jdb_production_enabled 0"));
            assertTrue(prometheus.body().contains("jdb_verified_quarterly_observations 0"));
            scheduler.getContext().put("calendar",new TradingCalendar("calendar-fixture-v1",java.time.LocalDate.of(2026,9,28),
                    java.time.LocalDate.of(2026,9,30),new TreeSet<>(List.of(java.time.LocalDate.of(2026,9,28),java.time.LocalDate.of(2026,9,29),java.time.LocalDate.of(2026,9,30)))));
            int instancesBeforePlan=jdbc(context).queryForObject("SELECT count(*) FROM business_instance",Integer.class);
            var planRequest=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/backfills/plan")).header("Authorization",auth)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(
                            new ManagementServer.BackfillPlanRequest("source_daily","2026-09-28","2026-09-30",3,"calendar-fixture-v1")))).build();
            var planned=http.send(planRequest,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,planned.statusCode());assertTrue(planned.body().contains("\"dryRun\":true"));
            assertTrue(planned.body().contains("\"enqueued\":false"));assertTrue(planned.body().contains("2026-09-30"));
            assertEquals(instancesBeforePlan,jdbc(context).queryForObject("SELECT count(*) FROM business_instance",Integer.class));
            var monthlyPlanRequest=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/backfills/plan")).header("Authorization",auth)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(
                            new ManagementServer.BackfillPlanRequest("source_cn_m","2026-05-01","2026-07-01",3,RecoveryPolicy.MONTHLY_PERIOD_VERSION)))).build();
            var monthlyPlanned=http.send(monthlyPlanRequest,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,monthlyPlanned.statusCode());assertTrue(monthlyPlanned.body().contains("\"frequency\":\"MONTH\""));
            assertTrue(monthlyPlanned.body().contains("2026-05-01")&&monthlyPlanned.body().contains("2026-06-01")&&monthlyPlanned.body().contains("2026-07-01"));
            assertEquals(instancesBeforePlan,jdbc(context).queryForObject("SELECT count(*) FROM business_instance",Integer.class));
            var quarterlyPlan=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/backfills/plan")).header("Authorization",auth)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(
                            new ManagementServer.BackfillPlanRequest("source_cn_gdp","2025-12-31","2026-06-30",2000,RecoveryPolicy.QUARTERLY_PERIOD_VERSION)))).build();
            var quarterlyPlanned=http.send(quarterlyPlan,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,quarterlyPlanned.statusCode());assertTrue(quarterlyPlanned.body().contains("\"frequency\":\"QUARTER\""));
            assertTrue(quarterlyPlanned.body().contains("\"maxPartitions\":1000"));
            assertTrue(quarterlyPlanned.body().contains("2025-12-31")&&quarterlyPlanned.body().contains("2026-03-31")&&quarterlyPlanned.body().contains("2026-06-30"));
            assertEquals(instancesBeforePlan,jdbc(context).queryForObject("SELECT count(*) FROM business_instance",Integer.class));
            var stalePlan=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/backfills/plan")).header("Authorization",auth)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(
                            new ManagementServer.BackfillPlanRequest("source_daily","2026-09-28","2026-09-30",3,null)))).build();
            assertEquals(400,http.send(stalePlan,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(tasks.body().contains("l2_archive_integrity"));
            Path l2Archive=archive.resolve("20260928.zip");
            try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(l2Archive))) {
                for(String name:List.of("逐笔成交.csv","逐笔委托.csv","行情.csv")) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("20260928\\000001.SZ\\"+name));zip.write(L2ArchiveAdmissionTest.csv("20260928",name,"1",false).getBytes(DfcfCsvInspector.ENCODING));zip.closeEntry();
                }
            }
            Files.setLastModifiedTime(l2Archive,java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofMinutes(10))));
            var inspectUri=URI.create("http://localhost:"+port+"/v1/l2/archives/inspect");
            String inspectBody=Json.write(new ManagementServer.ArchiveInspectionRequest(l2Archive.toString()));
            var inspectRequest=HttpRequest.newBuilder(inspectUri).header("Authorization",auth).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(inspectBody)).build();
            assertEquals(409,http.send(inspectRequest,HttpResponse.BodyHandlers.ofString()).statusCode());
            jdbc(context).update("UPDATE l2_archive_observation SET observed_at_millis=? WHERE source_path=?",System.currentTimeMillis()-360_000,l2Archive.toString());
            var inspected=http.send(inspectRequest,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,inspected.statusCode()); assertTrue(inspected.body().contains("ZIP_CRC_AND_CSV_VERIFIED")); assertTrue(inspected.body().contains("\"sourceRows\":3"));
            assertTrue(inspected.body().contains("SEMANTICALLY_PARSED"));assertTrue(inspected.body().contains("dfcf-csv-mapper-v1"));
            var inspectionJson=Json.MAPPER.readTree(inspected.body()).path("result");
            Path semanticManifest=Path.of(inspectionJson.path("materializationManifest").asText());
            assertTrue(Files.isRegularFile(semanticManifest));
            assertTrue(inspectionJson.path("materializationReused").isBoolean());assertFalse(inspectionJson.path("materializationReused").asBoolean());
            assertEquals(1,inspectionJson.path("dealRows").asLong());assertEquals(1,inspectionJson.path("orderRows").asLong());assertEquals(1,inspectionJson.path("quoteRows").asLong());
            try(var deals=new java.util.zip.GZIPInputStream(Files.newInputStream(semanticManifest.getParent().resolve("deals.ndjson.gz")))) {
                assertTrue(new String(deals.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("\"side\":\"BUY\""));
            }
            assertEquals(1,jdbc(context).queryForObject("SELECT count(*) FROM l2_archive_ledger",Integer.class));
            assertEquals(3,jdbc(context).queryForObject("SELECT count(*) FROM l2_archive_member",Integer.class));
            assertEquals(2,jdbc(context).queryForObject("SELECT count(*) FROM BATCH_JOB_INSTANCE WHERE JOB_NAME='l2_archive_integrity'",Integer.class));
            assertTrue(response.body().contains("auditEvents"));
            var pause=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/schedules/source_cyq_perf/pause"))
                    .header("Authorization","Bearer test-token-with-at-least-24-characters").POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(200,http.send(pause,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(Trigger.TriggerState.PAUSED,scheduler.getTriggerState(new TriggerKey("source_cyq_perf","jdb")));
            var resume=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/schedules/source_cyq_perf/resume"))
                    .header("Authorization","Bearer test-token-with-at-least-24-characters").POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(400,http.send(resume,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        try (var context=start()) {
            assertEquals(BusinessState.BLOCKED,context.getBean(SqliteLedger.class).state(id));
            assertEquals(Trigger.TriggerState.PAUSED,context.getBean(Scheduler.class).getTriggerState(new TriggerKey("post_close","jdb")));
            assertEquals(Trigger.TriggerState.PAUSED,context.getBean(Scheduler.class).getTriggerState(new TriggerKey("source_cyq_perf","jdb")));
        }
    }
    @Test void monthlyCoveragePersistsOnlyReadyPeriodsAndKeepsGapsVisible() throws Exception {
        try(var context=start()) {
            var ledger=context.getBean(SqliteLedger.class);var server=context.getBean(ManagementServer.class);
            String scope=RunRequest.hash("cn_m","universe-v1","aggregate");var now=java.time.Instant.parse("2026-09-30T00:00:00Z");
            var first=monthlyCoverageRequest("coverage-jan-mar",LocalDate.of(2026,1,1),LocalDate.of(2026,3,1),scope,now);
            var later=monthlyCoverageRequest("coverage-may",LocalDate.of(2026,5,1),LocalDate.of(2026,5,1),scope,now);
            var incomplete=monthlyCoverageRequest("coverage-april-gap",LocalDate.of(2026,4,1),LocalDate.of(2026,4,1),scope,now);
            ledger.register(first);ledger.completeSource(first,monthlyCoverageResult(first,3,now),1L);
            ledger.register(later);ledger.completeSource(later,monthlyCoverageResult(later,1,now),2L);
            ledger.register(incomplete);ledger.completeSource(incomplete,new StageExecutor.Result(BusinessState.PARTIAL,null,"missing-source-month"),3L);
            var summaries=ledger.monthlyCoverage();assertEquals(1,summaries.size());
            var coverage=summaries.getFirst();assertEquals(4,coverage.verifiedMonths());assertEquals(2,coverage.verifiedSegments().size());
            assertEquals(new SqliteLedger.CoverageSegment(LocalDate.of(2026,1,1),LocalDate.of(2026,3,1),3),coverage.verifiedSegments().getFirst());
            assertEquals(new SqliteLedger.CoverageSegment(LocalDate.of(2026,5,1),LocalDate.of(2026,5,1),1),coverage.verifiedSegments().getLast());
            assertEquals(4L,ledger.metrics().get("verifiedMonthlyObservations"));
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+server.port()+"/v1/coverage/monthly"))
                    .header("Authorization","Bearer test-token-with-at-least-24-characters").GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("2026-01-01"));assertTrue(response.body().contains("2026-05-01"));
        }
    }
    @Test void quarterlyCoveragePersistsOnlyReadyPeriodsAndKeepsGapsVisible() throws Exception {
        try(var context=start()) {
            var ledger=context.getBean(SqliteLedger.class);var server=context.getBean(ManagementServer.class);
            String scope=RunRequest.hash("cn_gdp","universe-v1","aggregate");var now=java.time.Instant.parse("2026-09-30T00:00:00Z");
            var first=quarterlyCoverageRequest("coverage-gdp-q1-q2",LocalDate.of(2026,3,31),LocalDate.of(2026,6,30),scope,now);
            var later=quarterlyCoverageRequest("coverage-gdp-q4",LocalDate.of(2026,12,31),LocalDate.of(2026,12,31),scope,now);
            var gap=quarterlyCoverageRequest("coverage-gdp-q3-gap",LocalDate.of(2026,9,30),LocalDate.of(2026,9,30),scope,now);
            ledger.register(first);ledger.completeSource(first,quarterlyCoverageResult(first,2,now),1L);
            ledger.register(later);ledger.completeSource(later,quarterlyCoverageResult(later,1,now),2L);
            ledger.register(gap);ledger.completeSource(gap,new StageExecutor.Result(BusinessState.PARTIAL,null,"missing-source-quarter"),3L);
            var summaries=ledger.quarterlyCoverage();assertEquals(1,summaries.size());var coverage=summaries.getFirst();
            assertEquals(3,coverage.verifiedQuarters());assertEquals(2,coverage.verifiedSegments().size());
            assertEquals(new SqliteLedger.QuarterCoverageSegment(LocalDate.of(2026,3,31),LocalDate.of(2026,6,30),2),coverage.verifiedSegments().getFirst());
            assertEquals(new SqliteLedger.QuarterCoverageSegment(LocalDate.of(2026,12,31),LocalDate.of(2026,12,31),1),coverage.verifiedSegments().getLast());
            assertEquals(3L,ledger.metrics().get("verifiedQuarterlyObservations"));
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+server.port()+"/v1/coverage/quarterly"))
                    .header("Authorization","Bearer test-token-with-at-least-24-characters").GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("2026-03-31"));assertTrue(response.body().contains("2026-12-31"));
        }
    }
    private static RunRequest quarterlyCoverageRequest(String id,LocalDate start,LocalDate end,String scope,java.time.Instant now) {
        return new RunRequest(id,"source_cn_gdp",end,start,end,SourceContract.load("cn_gdp").version(),"0",null,null,
                RunRequest.hash(id),RecoveryPolicy.QUARTERLY_PERIOD_VERSION,"Asia/Shanghai",now,now,scope);
    }
    private static StageExecutor.Result quarterlyCoverageResult(RunRequest request,long rows,java.time.Instant now) {
        var evidence=new CompletionEvidence(1,"java-source:cn_gdp",request.instanceId(),"Source:cn_gdp",RunRequest.hash("batch",request.instanceId()),
                request.logicalDate(),request.rangeStart(),request.rangeEnd(),request.rangeStart(),request.rangeEnd(),request.definitionVersion(),
                request.inputFingerprint(),request.inputFingerprint(),rows,rows,true,true,true,true,true,false,now,BusinessState.VERIFIED,
                "fixture://verified-quarterly-evidence",null);
        return new StageExecutor.Result(BusinessState.VERIFIED,evidence,null);
    }
    private static RunRequest monthlyCoverageRequest(String id,java.time.LocalDate start,java.time.LocalDate end,String scope,java.time.Instant now) {
        return new RunRequest(id,"source_cn_m",end,start,end,SourceContract.load("cn_m").version(),"0",null,null,
                RunRequest.hash(id),"calendar-v1","Asia/Shanghai",now,now,scope);
    }
    private static StageExecutor.Result monthlyCoverageResult(RunRequest request,long rows,java.time.Instant now) {
        var evidence=new CompletionEvidence(1,"java-source:cn_m",request.instanceId(),"Source:cn_m",RunRequest.hash("batch",request.instanceId()),
                request.logicalDate(),request.rangeStart(),request.rangeEnd(),request.rangeStart(),request.rangeEnd(),request.definitionVersion(),
                request.inputFingerprint(),request.inputFingerprint(),rows,rows,true,true,true,true,true,false,now,BusinessState.VERIFIED,
                "fixture://verified-monthly-evidence",null);
        return new StageExecutor.Result(BusinessState.VERIFIED,evidence,null);
    }
    @Test void unknownDeliveryRetainsTargetAndDoesNotResend() throws Exception {
        try (var context=start()) {
            var ledger=context.getBean(SqliteLedger.class); var request=ContractsTest.request("writer"); ledger.register(request);
            var writer=new DurableWriter(ledger); Path file=archive.resolve("source.json"); Files.writeString(file,"frozen-source");
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            var intent=new DurableWriter.Intent("batch-1",request.instanceId(),"jdb_test_protocol","owner",hash,file.toString(),1);
            AtomicInteger sends=new AtomicInteger();
            var port=new DurableWriter.Port() {
                public void preflight(DurableWriter.Intent i) {}
                public void send(DurableWriter.Intent i) { sends.incrementAndGet(); throw new RuntimeException("ACK lost"); }
                public DurableWriter.Proof inspect(DurableWriter.Intent i) { return new DurableWriter.Proof(false,false,false,false,null); }
            };
            assertEquals(DurableWriter.Delivery.UNKNOWN,writer.execute(intent,port));
            var jdbc=jdbc(context);jdbc.update("INSERT INTO BATCH_JOB_INSTANCE(JOB_NAME,JOB_KEY) VALUES('source_daily','orphan-fixture')");
            long batchInstance=jdbc.queryForObject("SELECT JOB_INSTANCE_ID FROM BATCH_JOB_INSTANCE WHERE JOB_KEY='orphan-fixture'",Long.class);
            jdbc.update("INSERT INTO BATCH_JOB_EXECUTION(JOB_INSTANCE_ID,CREATE_TIME,STATUS,EXIT_CODE) VALUES(?,CURRENT_TIMESTAMP,'UNKNOWN','UNKNOWN')",batchInstance);
            long orphanExecution=jdbc.queryForObject("SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID=?",Long.class,batchInstance);
            jdbc.update("INSERT INTO BATCH_JOB_EXECUTION_PARAMS(JOB_EXECUTION_ID,PARAMETER_NAME,PARAMETER_TYPE,PARAMETER_VALUE,IDENTIFYING) VALUES(?,'instance','java.lang.String',?,'Y')",orphanExecution,"f".repeat(64));
            var pending=ledger.reconciliationQueue();assertTrue(Json.write(pending).contains("batch-1"));assertTrue(Json.write(pending).contains("jdb_test_protocol"));
            assertEquals(1,pending.get("orphanBatchExecutions").size());
            assertEquals("UNKNOWN",jdbc(context).queryForObject("SELECT delivery FROM write_intent WHERE batch_id='batch-1'",String.class));
            var server=context.getBean(ManagementServer.class);
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+server.port()+"/v1/reconciliation"))
                    .header("Authorization","Bearer test-token-with-at-least-24-characters").GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("batch-1"));
            assertEquals(DurableWriter.Delivery.UNKNOWN,writer.execute(intent,port)); assertEquals(1,sends.get());
            var other=new DurableWriter.Intent("batch-2",request.instanceId(),"jdb_test_protocol","owner",hash,file.toString(),1);
            assertThrows(IllegalStateException.class,() -> writer.execute(other,port));
            var reconciled=new DurableWriter.Port() {
                public void preflight(DurableWriter.Intent i) { fail("must not resend"); }
                public void send(DurableWriter.Intent i) { fail("must not resend"); }
                public DurableWriter.Proof inspect(DurableWriter.Intent i) { return new DurableWriter.Proof(true,true,true,false,"proof://exact-content"); }
            };
            assertEquals(DurableWriter.Delivery.VERIFIED,writer.execute(intent,reconciled));
            assertTrue(ledger.reconciliationQueue().get("writes").stream().noneMatch(row->"batch-1".equals(row.get("batch_id"))));
            assertEquals(0,jdbc(context).queryForObject("SELECT count(*) FROM target_reservation WHERE target='jdb_test_protocol'",Integer.class));
        }
    }

    @Test void externalChildReservationSurvivesParentRestartAndNeverCreatesSecondChild() throws Exception {
        var request=ContractsTest.request("external-child");
        String childId;
        try(var context=start()) {
            var ledger=context.getBean(SqliteLedger.class); ledger.register(request);
            var children=context.getBean(ExternalExecutionStore.class);
            var reserved=children.reserve(request,"FactorReady",archive.resolve("external/child.log"));
            childId=reserved.childId();
            assertEquals(ExternalExecutionStore.State.STARTING,reserved.state());
            assertEquals(childId,children.reserve(request,"FactorReady",archive.resolve("external/child.log")).childId());
            children.started(childId,ProcessHandle.current(),java.time.Instant.now());
            children.heartbeat(childId,java.time.Instant.now());
            assertTrue(children.observe(request,"FactorReady").alive());
            assertEquals(1,ledger.reconciliationQueue().get("externalExecutions").size());
            assertEquals(1,jdbc(context).queryForObject("SELECT count(*) FROM external_execution WHERE instance_id=?",Integer.class,request.instanceId()));
        }
        try(var context=start()) {
            var children=context.getBean(ExternalExecutionStore.class);
            var recovered=children.observe(request,"FactorReady");
            assertEquals(childId,recovered.childId());
            assertTrue(recovered.alive(),"parent restart must re-associate the still running child");
            var certificate=ContractsTest.evidence(request,"FactorReady");
            children.exited(childId,0,certificate,null);
            var completed=children.observe(request,"FactorReady");
            assertEquals(BusinessState.VERIFIED,ExternalComputation.validate(request,"FactorReady",completed));
            assertEquals(childId,children.reserve(request,"FactorReady",archive.resolve("external/child.log")).childId());
            assertEquals(3,jdbc(context).queryForObject("SELECT count(*) FROM external_execution_event WHERE child_id=?",Integer.class,childId));
        }
    }

    @Test void restrictedExternalExecutorAcceptsOnlyMatchingChildScopedEvidence() throws Exception {
        try(var context=start()) {
            var store=context.getBean(ExternalExecutionStore.class);
            var ledger=context.getBean(SqliteLedger.class);
            int caseNumber=0;
            for(var mode:List.of("verified","partial","wrong-date","wrong-version","stale-file")) {
                var base=ContractsTest.request("external-protocol-"+mode);var date=base.logicalDate().plusDays(caseNumber++);
                var request=new RunRequest(base.requestId(),base.job(),date,date,date,base.definitionVersion(),base.revision(),null,null,
                        base.inputFingerprint(),base.calendarVersion(),base.zone(),base.scheduledAt(),base.triggeredAt());ledger.register(request);
                Path argsFile=archive.resolve("java-args-"+mode+".txt");
                String classpath=System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"");
                Files.writeString(argsFile,"-cp\n\""+classpath+"\"\n"+ExternalProtocolFixture.class.getName()+"\n"+mode+"\n");
                var command=List.of(currentJavaExecutable().toString(),"@"+argsFile);
                var executor=new RestrictedExternalExecutor(store,archive,Map.of("FactorReady",command),Map.of(),java.time.Duration.ofSeconds(10));
                var first=executor.observe(request,"FactorReady");
                assertNotNull(first.childId());
                var execution=awaitExternalTerminal(store,request);
                var observed=store.observe(request,"FactorReady");
                if(mode.equals("verified")) {
                    assertEquals(ExternalExecutionStore.State.EXITED,execution.state(),execution.reason()+"; log="+Files.readString(Path.of(execution.logPath())));
                    assertEquals(BusinessState.VERIFIED,ExternalComputation.validate(request,"FactorReady",observed));
                    assertNotNull(execution.logPath());assertTrue(Files.size(Path.of(execution.logPath()))>0);
                } else if(mode.equals("partial")) {
                    assertEquals(ExternalExecutionStore.State.EXITED,execution.state());
                    assertEquals(BusinessState.PARTIAL,ExternalComputation.validate(request,"FactorReady",observed));
                } else {
                    assertEquals(ExternalExecutionStore.State.BLOCKED,execution.state());
                    assertEquals(BusinessState.BLOCKED,ExternalComputation.validate(request,"FactorReady",observed));
                }
                // Re-observation must retain the exact child reservation and never start another process.
                assertEquals(first.childId(),executor.observe(request,"FactorReady").childId());
            }
        }
    }

    @Test void externalTimeoutIsDiagnosticAndNeverKillsOrDuplicatesLiveChild() throws Exception {
        try(var context=start()) {
            var base=ContractsTest.request("external-timeout");var date=base.logicalDate().plusDays(20);
            var request=new RunRequest(base.requestId(),base.job(),date,date,date,base.definitionVersion(),base.revision(),null,null,
                    base.inputFingerprint(),base.calendarVersion(),base.zone(),base.scheduledAt(),base.triggeredAt());
            var store=context.getBean(ExternalExecutionStore.class);context.getBean(SqliteLedger.class).register(request);
            Path argsFile=archive.resolve("java-args-slow.txt");
            String classpath=System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"");
            Files.writeString(argsFile,"-cp\n\""+classpath+"\"\n"+ExternalProtocolFixture.class.getName()+"\nslow\n");
            var executor=new RestrictedExternalExecutor(store,archive,Map.of("FactorReady",List.of(
                    currentJavaExecutable().toString(),"@"+argsFile)),Map.of(),java.time.Duration.ofMillis(50));
            var first=executor.observe(request,"FactorReady");Thread.sleep(150);
            var second=executor.observe(request,"FactorReady");
            assertEquals(first.childId(),second.childId());assertTrue(second.alive());
            assertEquals(1,jdbc(context).queryForObject(
                    "SELECT count(*) FROM external_execution WHERE instance_id=? AND stage='FactorReady'",Integer.class,request.instanceId()));
            assertTrue(jdbc(context).queryForObject(
                    "SELECT count(*) FROM external_execution_event WHERE child_id=? AND event='DIAGNOSTIC'",Integer.class,first.childId())>0);
            assertEquals(ExternalExecutionStore.State.EXITED,awaitExternalTerminal(store,request).state());
        }
    }

    private static ExternalExecutionStore.Execution awaitExternalTerminal(ExternalExecutionStore store,RunRequest request) throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<deadline) {
            var execution=store.get(request.instanceId(),"FactorReady").orElseThrow();
            if(execution.state()==ExternalExecutionStore.State.EXITED||execution.state()==ExternalExecutionStore.State.BLOCKED)
                return execution;
            Thread.sleep(20);
        }
        fail("external fixture did not reach a terminal state");return null;
    }

    @Test void uncertainExternalReservationCannotBeReclaimedForSecondChild() {
        try(var context=start()) {
            var original=ContractsTest.request("lost-child");
            var request=new RunRequest(original.requestId(),original.job(),original.logicalDate(),original.rangeStart(),original.rangeEnd(),
                    "definition-v2",original.revision(),null,null,original.inputFingerprint(),original.calendarVersion(),original.zone(),
                    original.scheduledAt(),original.triggeredAt());
            context.getBean(SqliteLedger.class).register(request);
            var children=context.getBean(ExternalExecutionStore.class);
            var first=children.reserve(request,"FactorReady",archive.resolve("external/lost.log"));
            var observation=children.observe(request,"FactorReady");
            assertFalse(observation.alive());
            assertEquals(BusinessState.IN_DOUBT,ExternalComputation.validate(request,"FactorReady",observation));
            assertEquals(ExternalExecutionStore.State.IN_DOUBT,children.get(request.instanceId(),"FactorReady").orElseThrow().state());
            assertEquals(first.childId(),children.reserve(request,"FactorReady",archive.resolve("external/lost.log")).childId());
            assertThrows(IllegalStateException.class,() -> children.started(first.childId(),ProcessHandle.current(),java.time.Instant.now()));
        }
    }
    @Test void concurrentRegistrationHasOneIdentityAndConflictingInputsRejected() throws Exception {
        try (var context=start(); var pool=Executors.newFixedThreadPool(4)) {
            var ledger=context.getBean(SqliteLedger.class);
            var futures=new ArrayList<Future<RunRequest>>();
            for(int i=0;i<8;i++) { final int n=i; futures.add(pool.submit(() -> ledger.register(ContractsTest.request("race-"+n)))); }
            for(var future:futures) assertEquals(ContractsTest.request("race").instanceId(),future.get().instanceId());
            assertEquals(1,jdbc(context).queryForObject("SELECT count(*) FROM business_instance",Integer.class));
            var r=ContractsTest.request("conflict");
            var changed=new RunRequest(r.requestId(),r.job(),r.logicalDate(),r.rangeStart(),r.rangeEnd(),r.definitionVersion(),r.revision(),
                    null,null,"changed-source",r.calendarVersion(),r.zone(),r.scheduledAt(),r.triggeredAt());
            assertThrows(IllegalArgumentException.class,() -> ledger.register(changed));
        }
    }
    @Test void optionalFailurePreservesMainChainAndRecoveryDoesNotRecomputeReadyStages() throws Exception {
        var calls=new java.util.concurrent.ConcurrentHashMap<String,AtomicInteger>();
        StageExecutor fixture=(request,stage) -> {
            int count=calls.computeIfAbsent(stage.id(),unused -> new AtomicInteger()).incrementAndGet();
            if (stage.id().equals("FuturesMarket") && count==1)
                return new StageExecutor.Result(BusinessState.PARTIAL,null,"fixture-missing-futures");
            return new StageExecutor.Result(BusinessState.VERIFIED,ContractsTest.evidence(request,stage.id()),null);
        };
        try(var context=start(fixture)) {
            var launches=context.getBean(LaunchService.class); var ledger=context.getBean(SqliteLedger.class);
            var request=ContractsTest.request("fixture-first");
            assertEquals("PARTIAL",launches.launch(request).get("business_state"));
            assertEquals(BusinessState.VERIFIED,ledger.stages(request.instanceId()).get("StrategyPublished"));
            assertEquals("VERIFIED",launches.launch(ContractsTest.request("fixture-retry")).get("business_state"));
            assertEquals(2,calls.get("FuturesMarket").get());
            assertEquals(1,calls.get("DataReady").get());
            assertEquals(1,calls.get("StrategyPublished").get());
            assertEquals("VERIFIED",launches.launch(ContractsTest.request("fixture-repeat")).get("business_state"));
            assertEquals(1,calls.get("StrategyPublished").get());
            assertEquals(1,jdbc(context).queryForObject("SELECT count(*) FROM batch_job_instance",Integer.class));
        }
    }

    @Test void overlappingLaunchesExecuteOneBusinessInstance() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var executions=new AtomicInteger();
        StageExecutor fixture=(request,stage) -> {
            if(stage.id().equals("DataReady")) {
                executions.incrementAndGet(); entered.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
            return new StageExecutor.Result(BusinessState.VERIFIED,ContractsTest.evidence(request,stage.id()),null);
        };
        try(var context=start(fixture);var pool=Executors.newFixedThreadPool(2)) {
            var launch=context.getBean(LaunchService.class);
            var first=pool.submit(() -> launch.launch(ContractsTest.request("overlap-first")));
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                assertEquals("already-running",launch.launch(ContractsTest.request("overlap-second")).get("disposition"));
            } finally { release.countDown(); }
            assertEquals("VERIFIED",first.get(10,TimeUnit.SECONDS).get("business_state"));
            assertEquals(1,executions.get());
            assertEquals(1,jdbc(context).queryForObject("SELECT count(*) FROM batch_job_execution",Integer.class));
        }
    }
    @Test void ackDoesNotCertifyVisibilityAndSuspensionRetainsOwnership() throws Exception {
        try(var context=start()) {
            var ledger=context.getBean(SqliteLedger.class); var request=ContractsTest.request("wal-delay"); ledger.register(request);
            var writer=new DurableWriter(ledger); var sends=new AtomicInteger(); var suspended=new java.util.concurrent.atomic.AtomicBoolean();
            Path file=archive.resolve("wal.ilp");Files.writeString(file,"frozen-wal-batch");
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            var intent=new DurableWriter.Intent("wal-batch",request.instanceId(),"jdb_test_wal","owner",hash,file.toString(),1);
            var port=new DurableWriter.Port() {
                public void preflight(DurableWriter.Intent i) {}
                public void send(DurableWriter.Intent i) { sends.incrementAndGet(); }
                public DurableWriter.Proof inspect(DurableWriter.Intent i) {
                    return new DurableWriter.Proof(true,false,true,suspended.get(),"fixture://delayed-boundary");
                }
            };
            assertEquals(DurableWriter.Delivery.ACKNOWLEDGED,writer.execute(intent,port));
            assertEquals(DurableWriter.Delivery.ACKNOWLEDGED,writer.execute(intent,port));
            assertEquals(1,sends.get());
            suspended.set(true);
            assertEquals(DurableWriter.Delivery.BLOCKED,writer.execute(intent,port));
            assertEquals(1,jdbc(context).queryForObject("SELECT count(*) FROM target_reservation",Integer.class));
        }
    }

    @Test void sourceCollectionUsesOneProbePerIdempotencyKey() throws Exception {
        try(var context=start()) {
            var ledger=context.getBean(SqliteLedger.class);var source=org.mockito.Mockito.mock(com.zoutrankil.data.service.TusharePageService.class);
            var calls=new AtomicInteger();
            org.mockito.Mockito.when(source.fetcher(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                    .thenReturn(parameters -> {
                        calls.incrementAndGet();
                        return new com.zoutrankil.data.service.PageExecutor.Page(List.of(NativeSourceTest.row("daily","000001.SZ")),null,false,null);
                    });
            var service=new NativeSourceService(ledger,new SourceCollector(archive),source);
            var request=NativeSourceTest.request("daily",Set.of("000001.SZ"));
            assertEquals("VERIFYING",service.collect("source-once",request).get("state"));
            assertEquals("VERIFYING",service.collect("source-once",request).get("state"));
            assertEquals(1,calls.get());
            assertThrows(IllegalArgumentException.class,() -> service.collect("source-once",NativeSourceTest.request("daily",Set.of("000002.SZ"))));
        }
    }

    @Test void lateSourceRebindsSameBatchInstanceOnlyBeforeAnyWriteIntent() throws Exception {
        try(var context=start()) {
            var collector=context.getBean(SourceCollector.class);var ledger=context.getBean(SqliteLedger.class);var launch=context.getBean(LaunchService.class);
            var scope=NativeSourceTest.request("daily",Set.of("000001.SZ","000002.SZ"));
            var first=collector.collect(scope,p -> new com.zoutrankil.data.service.PageExecutor.Page(List.of(NativeSourceTest.row("daily","000001.SZ")),null,false,null));
            var date=scope.logicalDate();var instant=java.time.Instant.parse("2026-09-29T10:30:00Z");
            var request=new RunRequest("late-first","source_daily",date,date,date,"daily-v1","0",null,null,first.fingerprint(),"cal","Asia/Shanghai",instant,instant,first.scopeIdentity());
            assertEquals("PARTIAL",launch.launch(request).get("business_state"));
            var second=collector.collect(scope,p -> new com.zoutrankil.data.service.PageExecutor.Page(List.of(NativeSourceTest.row("daily","000001.SZ"),NativeSourceTest.row("daily","000002.SZ")),null,false,null));
            var retry=new RunRequest("late-second","source_daily",date,date,date,"daily-v1","0",null,null,second.fingerprint(),"cal","Asia/Shanghai",instant,instant,second.scopeIdentity());
            assertEquals("BLOCKED",launch.launch(retry).get("business_state")); // Complete input, but no QDB writer configured in this test.
            assertEquals(request.instanceId(),retry.instanceId());
            assertEquals(1,jdbc(context).queryForObject("SELECT count(*) FROM batch_job_instance",Integer.class));
            assertEquals(second.fingerprint(),ledger.request(request.instanceId()).inputFingerprint());
            jdbc(context).update("INSERT INTO write_intent(batch_id,instance_id,target,owner,source_fingerprint,artifact,expected_rows,delivery) VALUES('pending',?,'jdb_test_pending','test',?,'retained',1,'INTENT')",request.instanceId(),second.fingerprint());
            var third=new RunRequest("late-third","source_daily",date,date,date,"daily-v1","0",null,null,first.fingerprint(),"cal","Asia/Shanghai",instant,instant,first.scopeIdentity());
            assertThrows(IllegalArgumentException.class,() -> launch.launch(third));
        }
    }


    private static Path currentJavaExecutable() {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        Path executable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS), "Current JVM executable is required: " + executable);
        assertFalse(Files.isSymbolicLink(executable));
        assertTrue(Files.isExecutable(executable), "Current JVM executable permission is required");
        return executable;
    }

}
