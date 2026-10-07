package com.zoutrankil.data.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.EquityStyleMonthlyMapper;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual real-source initial/replay and separately admitted third-month append; no formal writes. */
@EnabledIfEnvironmentVariable(named="D103_LIVE_STAGE",matches="initial|increment")
class EquityStyleMonthlyLiveAcceptanceTest {
    private static final Path DIRECTORY=Path.of("artifacts/java-migration/D103/commands");
    private static final Path LEDGER=Path.of("var/d103-java-acceptance.sqlite3");
    private static final String TABLE="java_d103_equity_style_monthly_acceptance";
    private static final LocalDate JUNE=LocalDate.of(2026,6,1),JULY=LocalDate.of(2026,7,1),AUGUST=LocalDate.of(2026,8,1),LOGICAL=LocalDate.of(2026,10,6);
    private static final String PREFLIGHT_SHA="61a3e91753074598b6db0d5bdc7867ff45d90d144628629a36316a0bf091b0db";

    @Test void actualBoundedMaterializationUsesCanonicalRunnerTypedReadsAndComposition() throws Exception {
        String stage=System.getenv("D103_LIVE_STAGE");boolean initial=stage.equals("initial");
        Path output=DIRECTORY.resolve("java-"+stage+"-acceptance-20261006.json");assertFalse(Files.exists(output));
        Path preflight=DIRECTORY.resolve("equity-style-readonly-preflight-20261006.json");assertEquals(PREFLIGHT_SHA,sha(preflight));
        JsonNode baseline=JobDefinitionJson.mapper().readTree(preflight.toFile());
        assertEquals("VERIFIED_BOUNDED_SOURCE_ORACLE",baseline.required("status").asText());
        Path fixture=Path.of(required("D103_FIXTURE_RECEIPT"));assertEquals(required("D103_FIXTURE_SHA"),sha(fixture));
        JsonNode fixtureEvidence=JobDefinitionJson.mapper().readTree(fixture.toFile());
        assertEquals("D103",fixtureEvidence.required("task_id").asText());
        assertFalse(fixtureEvidence.required("formal_mutated").asBoolean());
        if(initial) {
            assertEquals("VERIFIED_INITIAL_SOURCE_BY_READONLY_RECONCILIATION",fixtureEvidence.required("status").asText());
            assertFalse(fixtureEvidence.required("diagnostic_only").asBoolean());
            for(String field:List.of("source_delivery_exact_verified","stable_physical_and_wal_versions","java_source_read_admission_recommended"))
                assertTrue(fixtureEvidence.required(field).asBoolean(),field);
            assertEquals("UNKNOWN",fixtureEvidence.required("original_insert_ack").asText());
            assertEquals(0,fixtureEvidence.required("new_source_submissions").asInt());
            assertEquals(0,fixtureEvidence.required("retries").asInt());
            var completion=fixtureEvidence.required("sender_completion_proof");
            assertEvidence(completion.required("review"));assertEvidence(completion.required("executor_completion"));
            assertEquals("UNKNOWN",completion.required("original_insert_ack").asText());assertTrue(completion.required("retry_forbidden").asBoolean());
            assertEvidence(fixtureEvidence.required("preflight_evidence"));
            assertEquals(PREFLIGHT_SHA,fixtureEvidence.required("preflight_evidence").required("sha256").asText());
            assertEquals(41560,fixtureEvidence.required("private_target_attestation").required("pid").asInt());
            assertEquals(18842,fixtureEvidence.required("private_target_attestation").required("pg_port").asInt());
            assertEquals(19030,fixtureEvidence.required("private_target_attestation").required("http_port").asInt());
            assertEquals(fixtureEvidence.required("private_target_attestation"),fixtureEvidence.required("private_target_attestation_after"));
        } else {
            assertEquals("VERIFIED_ISOLATED_SOURCE_INCREMENT",fixtureEvidence.required("status").asText());
            assertEquals(2,fixtureEvidence.required("protocol_version").asInt());
            assertEquals("UNKNOWN",fixtureEvidence.required("original_initial_insert_ack").asText());
            assertFalse(fixtureEvidence.required("automatic_retry").asBoolean());
            assertEquals(16,fixtureEvidence.required("attempted_source_rows").asInt());assertEquals(16,fixtureEvidence.required("acknowledged_source_rows").asInt());
            assertEquals(1,fixtureEvidence.required("acknowledged_operations").asInt());
            for(String field:List.of("DDL","formal_writes","private_output_writes","initial_resubmissions"))assertEquals(0,fixtureEvidence.required(field).asInt());
            assertFalse(fixtureEvidence.required("private_output_mutated").asBoolean());
            for(var item:fixtureEvidence.required("immutable_input_evidence"))assertEvidence(item);
            assertEvidence(fixtureEvidence.required("initial_recovery"));assertEvidence(fixtureEvidence.required("java_readonly_recovery"));assertEvidence(fixtureEvidence.required("terminal_admission"));
            var gate=JobDefinitionJson.mapper().readTree(Path.of(fixtureEvidence.required("terminal_admission").required("path").asText()).toFile());
            assertEquals("accepted_for_bounded_source_increment_after_readonly_recovery",gate.required("decision").asText());assertTrue(gate.required("retry_forbidden").asBoolean());
            assertEquals(fixtureEvidence.required("private_target_attestation"),fixtureEvidence.required("private_target_attestation_after"));
        }
        assertEvidence(fixtureEvidence.required("startup_evidence"));
        var privateIdentity=fixtureEvidence.required("private_target_attestation");
        var evidence=new LinkedHashMap<String,Object>();evidence.put("task_id","D103");evidence.put("stage",stage);evidence.put("started_at",Instant.now());
        evidence.put("jvm_pid",ProcessHandle.current().pid());evidence.put("jvm_birth_utc",ProcessHandle.current().info().startInstant().orElseThrow());
        evidence.put("preflight_sha256",PREFLIGHT_SHA);evidence.put("fixture_receipt",fixture.toAbsolutePath().toString());evidence.put("fixture_sha256",sha(fixture));
        var runs=new ArrayList<String>();
        var nativeAttestations=new ArrayList<Object>();evidence.put("native_attestations",nativeAttestations);
        var firstNativeAttestation=nativeIdentity("before_connections",privateIdentity);nativeAttestations.add(firstNativeAttestation);
        evidence.put("jvm_birth_utc",firstNativeAttestation.get("jvm_birth_utc"));
        evidence.put("jvm_birth_source","Win32_Process.CreationDate exact UTC; ProcessHandle startInstant is retained separately");
        evidence.put("jvm_process_handle_start",ProcessHandle.current().info().startInstant().orElseThrow());
        Path jvmIdentity=DIRECTORY.resolve("java-"+stage+"-jvm-identity-20261006.json");
        Files.writeString(jvmIdentity,JobDefinitionJson.mapper().writeValueAsString(Map.of("task_id","D103","stage",stage,"jvm_pid",ProcessHandle.current().pid(),"jvm_birth_utc",firstNativeAttestation.get("jvm_birth_utc"),"native",firstNativeAttestation)),StandardOpenOption.CREATE_NEW);
        evidence.put("jvm_identity_evidence",Map.of("path",jvmIdentity.toAbsolutePath().toString(),"sha256",sha(jvmIdentity)));
        try(var privatePool=pool("d103-private",18842,"admin","quest");
            var formalPool=pool("d103-formal",8812,required("APP_QUESTDB_USERNAME"),required("APP_QUESTDB_PASSWORD"))) {
            var jdbc=jdbc(privatePool);var formal=jdbc(formalPool);var formalBefore=formalSnapshot(formal);evidence.put("formal_before",formalBefore);
            assertEquals(initial?32L:48L,jdbc.queryForObject("SELECT count() FROM index_monthly",Long.class));
            var properties=new QuestDbProperties();properties.setHost("127.0.0.1");properties.setPgPort(18842);properties.setQwpPort(19030);
            properties.setUsername("admin");properties.setPassword("quest");
            var owner=new EquityStyleMonthlyJobService(jdbc,properties,"index_monthly",TABLE,LEDGER);
            var admittedSource=owner.source().read(JUNE,initial?JULY:AUGUST);
            assertRows(oracle(baseline,initial?2:3),admittedSource.rows());assertEquals(initial?32:48,admittedSource.rawRows());
            var admittedState=fixtureEvidence.required("private_after").required("index_monthly");
            assertEquals(admittedState.required("physical").required("id").asLong(),admittedSource.snapshot().tableId());
            assertEquals(admittedState.required("physical").required("directoryName").asText(),admittedSource.snapshot().directory());
            assertEquals(admittedState.required("physical").required("table_txn").asLong(),admittedSource.snapshot().physicalTxn());
            assertEquals(admittedState.required("wal").required("sequencerTxn").asLong(),admittedSource.snapshot().sequenceTxn());
            evidence.put("admitted_source",admittedSource);
            if(initial) {
                assertFalse(Files.exists(LEDGER));
                Path claim=DIRECTORY.resolve("java-output-create-once-20261006.json");
                nativeAttestations.add(nativeIdentity("before_create",privateIdentity));
                assertEquals(admittedSource,owner.source().read(JUNE,JULY));
                Files.writeString(claim,"{\"task_id\":\"D103\",\"ack\":\"UNKNOWN\",\"automatic_retry\":false}",StandardOpenOption.CREATE_NEW);
                var installed=owner.installIsolated();assertEquals(0,installed.rowCount());assertTrue(installed.settled());
                Files.writeString(claim,JobDefinitionJson.mapper().writeValueAsString(Map.of("task_id","D103","ack","ACKNOWLEDGED","automatic_retry",false,"snapshot",installed)),StandardOpenOption.TRUNCATE_EXISTING);
                var emptyRepository=new EquityStyleMonthlyReadRepository(new QuestDbBoundedReader(jdbc),TABLE);
                assertTrue(emptyRepository.findForMonth(YearMonth.of(2026,6)).rows().isEmpty());
                evidence.put("fresh_empty_target_typed_read",true);
                nativeAttestations.add(nativeIdentity("before_first",privateIdentity));
                assertEquals(admittedSource,owner.source().read(JUNE,JULY));
                var first=owner.run(owner.plan(JUNE,JULY,LOGICAL,SyncJobDefinition.Mode.MATERIALIZE));verified(first,2);runs.add(first.result().runId());
                nativeAttestations.add(nativeIdentity("before_replay",privateIdentity));
                assertEquals(admittedSource,owner.source().read(JUNE,JULY));
                var replay=owner.run(owner.plan(JUNE,JULY,LOGICAL,SyncJobDefinition.Mode.MATERIALIZE));verified(replay,2);runs.add(replay.result().runId());
                var beforeResumeRejection=owner.writePort().targetSnapshot();
                assertThrows(IllegalStateException.class,()->owner.resume(first.result().runId()));
                assertEquals(beforeResumeRejection,owner.writePort().targetSnapshot());evidence.put("verified_resume_rejected_without_write",true);
                var resumePlan=owner.plan(JUNE,JULY,LOGICAL,SyncJobDefinition.Mode.MATERIALIZE);
                var cancelledForResume="d103-cancelled-resume-"+UUID.randomUUID();
                var cancelledResult=new SyncJobRunner<EquityStyleMonthly,YearMonth>(new SyncRunLedger(LEDGER),new DatasetIntervalLock(LEDGER))
                        .run(cancelledForResume,null,resumePlan.targetId(),resumePlan.request(),
                                new EquityStyleMonthlyMaterializeAdapter(owner.source(),owner.writePort(),resumePlan.source()),()->true);
                assertEquals(SyncRunState.CANCELLED,cancelledResult.state());assertEquals(0,cancelledResult.verifiedRows());
                runs.add(cancelledForResume);evidence.put("cancelled_for_resume",cancelledResult);
                nativeAttestations.add(nativeIdentity("before_cancelled_resume",privateIdentity));
                assertEquals(admittedSource,owner.source().read(JUNE,JULY));
                var resume=owner.resume(cancelledForResume);verified(resume,2);runs.add(resume.result().runId());
                var reconciled=owner.run(owner.plan(JUNE,JULY,LOGICAL,SyncJobDefinition.Mode.RECONCILE));verified(reconciled,2);runs.add(reconciled.result().runId());
                evidence.put("first",first);evidence.put("same_range_replay",replay);evidence.put("exact_resume",resume);evidence.put("readonly_reconcile",reconciled);
                assertEquals(2L,jdbc.queryForObject("SELECT count() FROM \""+TABLE+"\"",Long.class));
            } else {
                var plan=owner.plan(JUNE,AUGUST,LOGICAL,SyncJobDefinition.Mode.INCREMENTAL);
                assertEquals(JULY,plan.request().from());assertEquals("VERIFIED_PREFIX_APPEND",plan.request().parameters().get("checkpoint_reason"));assertNotNull(plan.checkpointParent());
                nativeAttestations.add(nativeIdentity("before_incremental",privateIdentity));
                assertEquals(admittedSource,owner.source().read(JUNE,AUGUST));
                var incremental=owner.run(plan);verified(incremental,2);runs.add(incremental.result().runId());
                evidence.put("increment_plan",plan);evidence.put("incremental",incremental);
                assertEquals(3L,jdbc.queryForObject("SELECT count() FROM \""+TABLE+"\"",Long.class));
            }
            var expected=oracle(baseline,initial?2:3);var sourceBefore=owner.source().read(JUNE,initial?JULY:AUGUST);
            assertEquals(initial?32:48,sourceBefore.rawRows());assertRows(expected,sourceBefore.rows());
            var reader=new QuestDbBoundedReader(jdbc);var repository=new EquityStyleMonthlyReadRepository(reader,TABLE);
            YearMonth end=YearMonth.of(2026,initial?8:9);var actual=new ArrayList<EquityStyleMonthly>();DatasetReadCursor cursor=null;
            var firstPage=repository.findRange(YearMonth.of(2026,6),end,1,null);assertNotNull(firstPage.nextCursor());
            assertThrows(IllegalArgumentException.class,()->repository.findRange(YearMonth.of(2026,6),end.plusMonths(1),1,firstPage.nextCursor()));
            do {var page=repository.findRange(YearMonth.of(2026,6),end,1,cursor);actual.addAll(page.rows());cursor=page.nextCursor();}while(cursor!=null);
            assertRows(expected,actual);for(var row:expected)assertRows(List.of(row),repository.findForMonth(row.month()).rows());
            assertTrue(repository.findForMonth(YearMonth.of(2026,9)).rows().isEmpty());
            var datasets=new DatasetRegistry(List.of(repository,(DatasetImplementation)()->IndexMonthlyDataset.DEFINITION,(DatasetImplementation)()->IndexCatalogDataset.DEFINITION));
            var group=new ReadGroupConfiguration().readGroupReader(datasets,reader);
            Path request=DIRECTORY.resolve("read-group-"+stage+"-20261006.json");
            var query=new LinkedHashMap<String,Object>();query.put("columns",EquityStyleMonthlyDataset.STORAGE_COLUMNS);query.put("equalities",Map.of());query.put("rangeColumn","month");
            query.put("fromInclusive",JUNE);query.put("toExclusive",end.atDay(1));query.put("pageSize",12);query.put("cursor",null);
            Files.writeString(request,JobDefinitionJson.mapper().writeValueAsString(Map.of("timeoutMillis",30000,"members",List.of(Map.of(
                    "memberId","styles","datasetId","equity_style_monthly","definitionVersion",1,"query",query)))),StandardOpenOption.CREATE_NEW);
            var groupResult=group.read(group.readRequest(request),()->false);assertTrue(groupResult.complete());assertFalse(groupResult.atomicSnapshot());
            assertRows(expected,groupResult.require("styles").typedPage(EquityStyleMonthly.class).rows());evidence.put("configured_read_group",groupResult);
            var cancelled=group.read(group.readRequest(request),()->true);assertEquals(ReadGroupReader.Status.CANCELLED,cancelled.require("styles").status());
            if(initial) {
                var write=new StockBasicWriteGroupService(datasets,null,jdbc,null,LEDGER.toString());
                var ownerField=StockBasicWriteGroupService.class.getDeclaredField("equityStyleMonthlyTarget");ownerField.setAccessible(true);ownerField.set(write,owner);
                Path writeRequest=DIRECTORY.resolve("write-group-initial-20261006.json");
                Files.writeString(writeRequest,JobDefinitionJson.mapper().writeValueAsString(Map.of("batchId","d103-actual-june","logicalDate",LOGICAL,"members",List.of(Map.of(
                        "memberId","styles","datasetId","equity_style_monthly","definitionVersion",1,"batchId","d103-actual-june-member",
                        "rows",List.of(new EquityStyleMonthlyMapper().values(expected.getFirst()).asMap()))))),StandardOpenOption.CREATE_NEW);
                nativeAttestations.add(nativeIdentity("before_typed_write_group",privateIdentity));
                assertEquals(admittedSource,owner.source().read(JUNE,JULY));
                var written=write.run(writeRequest,null);assertEquals(SyncRunState.VERIFIED,written.state());evidence.put("configured_write_group",written);runs.add(written.runId());
                assertRows(expected,owner.writePort().readActualRange(YearMonth.of(2026,6),YearMonth.of(2026,7)));
                var plan=owner.plan(JUNE,JULY,LOGICAL,SyncJobDefinition.Mode.MATERIALIZE);
                var stoppedRun="d103-cancelled-"+UUID.randomUUID();var ledger=new SyncRunLedger(LEDGER);
                var adapter=new EquityStyleMonthlyMaterializeAdapter(owner.source(),owner.writePort(),plan.source());
                var stopped=new SyncJobRunner<EquityStyleMonthly,YearMonth>(ledger,new DatasetIntervalLock(LEDGER))
                        .run(stoppedRun,null,plan.targetId(),plan.request(),adapter,()->true);
                assertEquals(SyncRunState.CANCELLED,stopped.state());assertEquals(0,stopped.verifiedRows());runs.add(stoppedRun);evidence.put("cancelled_before_source",stopped);
            }
            assertRows(expected,owner.writePort().readActualRange(YearMonth.of(2026,6),YearMonth.of(2026,initial?7:8)));
            assertEquals(sourceBefore,owner.source().read(JUNE,initial?JULY:AUGUST));
            var formalAfter=formalSnapshot(formal);assertEquals(formalBefore,formalAfter);evidence.put("formal_after",formalAfter);
            var ledger=SyncRunLedger.openReadOnly(LEDGER);for(var id:runs) {
                var state=ledger.get(id).state();assertTrue(Set.of(SyncRunState.VERIFIED,SyncRunState.CANCELLED).contains(state));
                if(ledger.getRun(id).jobId().equals(EquityStyleMonthlyJobService.JOB_ID))assertEquals(state,owner.status(id).state());
            }
            owner.requireNoPendingPublication();evidence.put("actual_target",owner.writePort().targetSnapshot());
            evidence.put("source",sourceBefore);evidence.put("expected_rows",expected.stream().map(new EquityStyleMonthlyMapper()::values).toList());
            evidence.put("key_and_full_field_comparisons",expected.size()*30);evidence.put("exact_double_bit_comparisons",expected.size()*29);
            nativeAttestations.add(nativeIdentity("after_all_verification",privateIdentity));
        }
        evidence.put("run_ids",runs);evidence.put("ledger",LEDGER.toAbsolutePath().toString());evidence.put("formal_mutated",false);evidence.put("reference_project_mutated",false);
        evidence.put("actual_source_revision",false);evidence.put("actual_source_increment",!initial);evidence.put("double_tolerance",0);
        evidence.put("finished_at",Instant.now());evidence.put("status",initial?"VERIFIED_ISOLATED_INITIAL_REPLAY":"VERIFIED_ISOLATED_INCREMENTAL");
        Files.writeString(output,JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence),StandardOpenOption.CREATE_NEW);
    }
    private static void verified(EquityStyleMonthlyJobService.MaterializationResult result,int rows) {
        assertEquals(SyncRunState.VERIFIED,result.result().state());assertEquals(rows,result.result().verifiedRows());assertNull(result.targetSnapshotError());
    }
    static List<EquityStyleMonthly> oracle(JsonNode preflight,int count) {
        var result=new ArrayList<EquityStyleMonthly>();var mapper=new EquityStyleMonthlyMapper();
        for(var row:preflight.required("expected_oracle_rows")) {
            if(result.size()==count)break;var values=new LinkedHashMap<String,Object>();
            for(String column:EquityStyleMonthlyDataset.STORAGE_COLUMNS) {
                JsonNode value=row.required(column);values.put(column,column.equals("month")?LocalDate.parse(value.asText().substring(0,10)):value.isNull()?null:value.doubleValue());
            }
            result.add(mapper.fromValues(values));
        }
        assertEquals(count,result.size());return result;
    }
    static void assertRows(List<EquityStyleMonthly> expected,List<EquityStyleMonthly> actual) {
        assertEquals(expected.size(),actual.size());for(int i=0;i<expected.size();i++)assertTrue(EquityStyleMonthlyWritePort.CODEC.equivalent(expected.get(i),actual.get(i)),"Exact month/null/29-bit values differ at "+i);
    }
    static HikariDataSource pool(String name,int port,String user,String password) {
        var config=new HikariConfig();config.setPoolName(name);config.setJdbcUrl("jdbc:postgresql://127.0.0.1:"+port+"/qdb?socketTimeout=20&connectTimeout=10");
        config.setUsername(user);config.setPassword(password);config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(10000);config.setInitializationFailTimeout(-1);return new HikariDataSource(config);
    }
    static JdbcTemplate jdbc(HikariDataSource source) {var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(20);jdbc.setMaxRows(64);return jdbc;}
    static Map<String,Object> formalSnapshot(JdbcTemplate jdbc) {
        var output=new LinkedHashMap<String,Object>();for(String table:List.of("index_monthly","equity_style_monthly")) {
            output.put(table,Map.of("physical",jdbc.queryForList("SELECT id,directoryName,table_txn,table_row_count,partitionBy,designatedTimestamp,walEnabled,dedup,table_suspended,wal_pending_row_count FROM tables() WHERE table_name=?",table),
                    "wal",jdbc.queryForList("SELECT sequencerTxn,writerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name=?",table),
                    "count",jdbc.queryForObject("SELECT count() FROM \""+table+"\"",Long.class),"schema",jdbc.queryForList("SELECT * FROM table_columns('"+table+"') LIMIT 31")));
        }return output;
    }
    static String required(String name) {String value=System.getenv(name);assertNotNull(value,name+" required");assertFalse(value.isBlank());return value;}
    static void assertEvidence(JsonNode item) throws Exception {assertEquals(item.required("sha256").asText(),sha(Path.of(item.required("path").asText())));}
    static Map<String,Object> nativeIdentity(String phase,JsonNode expected) throws Exception {
        String script="""
                $ErrorActionPreference='Stop'
                $taskListeners=@(Get-NetTCPConnection -LocalPort 19030,18842 -State Listen -ErrorAction Stop)
                $taskRecords=@(foreach($taskPort in @(19030,18842)) {
                  $taskMatches=@($taskListeners | Where-Object LocalPort -eq $taskPort)
                  if($taskMatches.Count -ne 1) { throw 'Ambiguous D103 listener' }
                  $taskListener=$taskMatches[0]
                  $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($taskListener.OwningProcess)"
                  [pscustomobject]@{port=$taskPort;address=$taskListener.LocalAddress;pid=$taskProcess.ProcessId;name=$taskProcess.Name;command=$taskProcess.CommandLine;birth=$taskProcess.CreationDate.ToUniversalTime().ToString('o')}
                })
                $taskExecutor=Get-CimInstance Win32_Process -Filter 'ProcessId=TASK_EXECUTOR_PID'
                [pscustomobject]@{listeners=$taskRecords;executor=[pscustomobject]@{pid=$taskExecutor.ProcessId;birth=$taskExecutor.CreationDate.ToUniversalTime().ToString('o')}} | ConvertTo-Json -Depth 5 -Compress
                """;
        var executable=Path.of(required("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe");
        var process=new ProcessBuilder(executable.toString(),"-NoProfile","-NonInteractive","-Command",script.replace("TASK_EXECUTOR_PID",Long.toString(ProcessHandle.current().pid()))).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Native attestation must finish in bounded time");
            var output=process.getInputStream().readNBytes(65537);assertTrue(output.length<=65536);assertEquals(0,process.exitValue());
            var nativeProof=JobDefinitionJson.mapper().readTree(output);var records=nativeProof.required("listeners");assertTrue(records.isArray());assertEquals(2,records.size());
            assertEquals(ProcessHandle.current().pid(),nativeProof.required("executor").required("pid").asLong());
            var exactJvmBirth=Instant.parse(nativeProof.required("executor").required("birth").asText());
            var ports=new HashSet<Integer>();String root=Path.of("var/d103-isolated-questdb").toAbsolutePath().normalize().toString();
            assertEquals(root.toLowerCase(Locale.ROOT),expected.required("data_root").asText().toLowerCase(Locale.ROOT));
            for(var record:records) {
                ports.add(record.required("port").asInt());assertEquals("127.0.0.1",record.required("address").asText());
                assertEquals(expected.required("pid").asLong(),record.required("pid").asLong());
                assertEquals(Instant.parse(expected.required("birth_utc").asText()),Instant.parse(record.required("birth").asText()));
                assertEquals("java.exe",record.required("name").asText().toLowerCase(Locale.ROOT));
                String command=record.required("command").asText().replace('/','\\').toLowerCase(Locale.ROOT);
                assertTrue(command.contains(root.toLowerCase(Locale.ROOT)));assertTrue(command.contains("io.questdb\\io.questdb.servermain"));
            }
            assertEquals(Set.of(19030,18842),ports);
            return Map.of("phase",phase,"observed_at",Instant.now(),"records",records,"attestation_child_pid",process.pid(),"attestation_child_stopped",true,"jvm_birth_utc",exactJvmBirth);
        } finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}process.getInputStream().close();}
    }
    static String sha(Path path) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
}
