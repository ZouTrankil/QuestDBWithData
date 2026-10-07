package com.zoutrankil.data.config;
import com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData;
import com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData.*;
import com.zoutrankil.data.derived.port.MacroCoreMonthlySourceReadPort;
import com.zoutrankil.data.derived.storage.QuestDbMacroCoreMonthlySourceReader;
import com.zoutrankil.data.derived.storage.QuestDbMacroCoreMonthlyTarget;

import com.zoutrankil.data.derived.application.MacroCoreMonthlyJobService;
import com.zoutrankil.data.derived.application.MacroCoreMonthlyMaterializeAdapter;
import com.zoutrankil.data.derived.application.MacroCoreMonthlySource;
import com.zoutrankil.data.derived.storage.MacroCoreMonthlyReadRepository;
import com.zoutrankil.data.derived.storage.MacroCoreMonthlyWritePort;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyMapper;
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
@EnabledIfEnvironmentVariable(named="D104_LIVE_STAGE",matches="initial|increment")
class MacroCoreMonthlyLiveAcceptanceTest {
    private static final Path DIRECTORY=Path.of("artifacts/java-migration/D104/commands");
    private static final Path LEDGER=Path.of("var/d104-java-acceptance.sqlite3");
    static final List<String> CODE_PATHS=List.of(
        "src/main/java/com/zoutrankil/data/domain/MacroCoreMonthly.java",
        "src/main/java/com/zoutrankil/data/domain/MacroCoreMonthlyKey.java",
        "src/main/java/com/zoutrankil/data/domain/MacroCoreMonthlyDataset.java",
        "src/main/java/com/zoutrankil/data/domain/JobDefinitionJson.java",
        "src/main/java/com/zoutrankil/data/derived/mapper/MacroCoreMonthlyMapper.java",
        "src/main/java/com/zoutrankil/data/derived/storage/MacroCoreMonthlyReadRepository.java",
        "src/main/java/com/zoutrankil/data/derived/storage/MacroCoreMonthlyWritePort.java",
        "src/main/java/com/zoutrankil/data/repository/QuestDbMacroCoreReadGuard.java",
        "src/main/java/com/zoutrankil/data/repository/QuestDbBoundedReader.java",
        "src/main/java/com/zoutrankil/data/derived/application/MacroCoreMonthlySource.java",
        "src/main/java/com/zoutrankil/data/derived/application/MacroCoreMonthlyMaterializeAdapter.java",
        "src/main/java/com/zoutrankil/data/derived/application/MacroCoreMonthlyJobService.java",
        "src/main/java/com/zoutrankil/data/service/StockBasicWriteGroupService.java",
        "src/main/java/com/zoutrankil/data/config/ReadGroupConfiguration.java",
        "src/main/java/com/zoutrankil/data/config/DatasetConfiguration.java",
        "src/main/java/com/zoutrankil/data/cli/MacroCoreMonthlyCommands.java",
        "src/main/java/com/zoutrankil/data/cli/CommandLineRunner.java",
        "src/test/java/com/zoutrankil/data/derived/mapper/MacroCoreMonthlyMappingTest.java",
        "src/test/java/com/zoutrankil/data/derived/storage/MacroCoreMonthlyReadRepositoryTest.java",
        "src/test/java/com/zoutrankil/data/derived/storage/MacroCoreMonthlyWritePortTest.java",
        "src/test/java/com/zoutrankil/data/repository/QuestDbMacroCoreReadGuardTest.java",
        "src/test/java/com/zoutrankil/data/derived/application/MacroCoreMonthlySourceTest.java",
        "src/test/java/com/zoutrankil/data/derived/application/MacroCoreMonthlyMaterializeAdapterTest.java",
        "src/test/java/com/zoutrankil/data/derived/application/MacroCoreMonthlyJobServiceTest.java",
        "src/test/java/com/zoutrankil/data/derived/application/MacroCoreMonthlyWriteGroupPreflightTest.java",
        "src/test/java/com/zoutrankil/data/derived/application/MacroCoreMonthlyCatalogStartupTest.java",
        "src/test/java/com/zoutrankil/data/cli/MacroCoreMonthlyCommandsTest.java",
        "src/test/java/com/zoutrankil/data/config/MacroCoreMonthlyLiveAcceptanceTest.java");
    private static final String TABLE="java_d104_macro_core_monthly_acceptance";
    private static final LocalDate JUNE=LocalDate.of(2026,6,1),JULY=LocalDate.of(2026,7,1),AUGUST=LocalDate.of(2026,8,1),LOGICAL=LocalDate.of(2026,10,7);
    private static final String PREFLIGHT_SHA=required("D104_PREFLIGHT_SHA");

    @Test void actualBoundedMaterializationUsesCanonicalRunnerTypedReadsAndComposition() throws Exception {
        String stage=System.getenv("D104_LIVE_STAGE");boolean initial=stage.equals("initial");
        Path output=DIRECTORY.resolve("java-"+stage+"-acceptance-20261007.json");assertFalse(Files.exists(output));
        Path preflight=DIRECTORY.resolve("macro-core-readonly-preflight-20261007.json");assertEquals(PREFLIGHT_SHA,sha(preflight));
        JsonNode baseline=JobDefinitionJson.mapper().readTree(preflight.toFile());
        assertEquals("VERIFIED_BOUNDED_SOURCE_ORACLE",baseline.required("status").asText());
        Path fixture=Path.of(required("D104_FIXTURE_RECEIPT"));assertEquals(required("D104_FIXTURE_SHA"),sha(fixture));
        JsonNode fixtureEvidence=JobDefinitionJson.mapper().readTree(fixture.toFile());
        assertEquals("D104",fixtureEvidence.required("task_id").asText());
        assertFalse(fixtureEvidence.required("formal_mutated").asBoolean());
        assertEquals(initial?"VERIFIED_ISOLATED_SOURCE_INITIAL":"VERIFIED_ISOLATED_SOURCE_INCREMENT",
                fixtureEvidence.required("status").asText());
        assertFalse(fixtureEvidence.required("automatic_retry").asBoolean());
        assertEquals(0,fixtureEvidence.required("formal_writes").asInt());
        assertEquals(0,fixtureEvidence.required("private_output_writes").asInt());
        var startup=DIRECTORY.resolve("private-server-start-20261007.json");
        assertEquals(required("D104_STARTUP_SHA"),sha(startup));
        var privateIdentity=JobDefinitionJson.mapper().readTree(startup.toFile());
        assertEquals("D104",privateIdentity.required("task_id").asText());
        Path admission=DIRECTORY.resolve("coordinator-java-"+stage+"-admission-20261007.json");
        assertEquals(required("D104_JAVA_ADMISSION_SHA"),sha(admission));
        var gate=JobDefinitionJson.mapper().readTree(admission.toFile());
        assertEquals(1,gate.required("protocol_version").asInt());assertEquals("D104",gate.required("task_id").asText());
        assertEquals(initial?"accepted_for_bounded_java_initial_materialization":"accepted_for_bounded_java_incremental_materialization",
                gate.required("decision").asText());
        for(String name:List.of("source_fixture","preflight","startup","source_review"))assertEvidence(gate.required(name));
        assertEquals(fixture.toAbsolutePath().normalize(),Path.of(gate.required("source_fixture").required("path").asText()).toAbsolutePath().normalize());
        assertEquals(sha(fixture),gate.required("source_fixture").required("sha256").asText());
        assertEquals(PREFLIGHT_SHA,gate.required("preflight").required("sha256").asText());
        assertEquals(sha(startup),gate.required("startup").required("sha256").asText());
        assertEquals(TABLE,gate.required("target_table").asText());assertEquals(2,gate.required("bounded_java_write_rows").asInt());
        assertEquals(0,gate.required("source_writes").asInt());assertEquals(0,gate.required("formal_writes").asInt());
        assertFalse(gate.required("automatic_retry").asBoolean());
        var codeBindings=gate.required("code_bindings");assertTrue(codeBindings.isArray());assertEquals(CODE_PATHS.size(),codeBindings.size());
        var expectedPaths=CODE_PATHS.stream().map(name->Path.of(name).toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
        var actualPaths=new HashSet<Path>();
        for(var code:codeBindings){
            assertTrue(actualPaths.add(Path.of(code.required("path").asText()).toAbsolutePath().normalize()),"Duplicate admitted code file");
            assertEvidence(code);
        }
        assertEquals(expectedPaths,actualPaths,"Exact compiled D104 implementation and acceptance code set required");
        var sourceReview=JobDefinitionJson.mapper().readTree(Path.of(gate.required("source_review").required("path").asText()).toFile());
        assertEquals("D104",sourceReview.required("task_id").asText());assertEquals(1,sourceReview.required("protocol_version").asInt());
        assertEquals(initial?"VERIFIED_COMPLETE_INITIAL_SOURCE_BY_INDEPENDENT_READONLY_REVIEW":"VERIFIED_COMPLETE_INCREMENT_SOURCE_BY_INDEPENDENT_READONLY_REVIEW",
                sourceReview.required("status").asText());
        assertEvidence(sourceReview.required(initial?"initial_fixture":"increment_fixture"));
        assertEquals(sha(fixture),sourceReview.required(initial?"initial_fixture":"increment_fixture").required("sha256").asText());
        var evidence=new LinkedHashMap<String,Object>();evidence.put("task_id","D104");evidence.put("stage",stage);evidence.put("started_at",Instant.now());
        evidence.put("java_admission",Map.of("path",admission.toAbsolutePath().toString(),"sha256",sha(admission)));
        evidence.put("source_review",gate.required("source_review"));
        evidence.put("jvm_pid",ProcessHandle.current().pid());evidence.put("jvm_birth_utc",ProcessHandle.current().info().startInstant().orElseThrow());
        evidence.put("preflight_sha256",PREFLIGHT_SHA);evidence.put("fixture_receipt",fixture.toAbsolutePath().toString());evidence.put("fixture_sha256",sha(fixture));
        var runs=new ArrayList<String>();
        var nativeAttestations=new ArrayList<Object>();evidence.put("native_attestations",nativeAttestations);
        var firstNativeAttestation=nativeIdentity("before_connections",privateIdentity);nativeAttestations.add(firstNativeAttestation);
        evidence.put("jvm_birth_utc",firstNativeAttestation.get("jvm_birth_utc"));
        evidence.put("jvm_birth_source","Win32_Process.CreationDate exact UTC; ProcessHandle startInstant is retained separately");
        evidence.put("jvm_process_handle_start",ProcessHandle.current().info().startInstant().orElseThrow());
        Path jvmIdentity=DIRECTORY.resolve("java-"+stage+"-jvm-identity-20261007.json");
        Files.writeString(jvmIdentity,JobDefinitionJson.mapper().writeValueAsString(Map.of("task_id","D104","stage",stage,"jvm_pid",ProcessHandle.current().pid(),"jvm_birth_utc",firstNativeAttestation.get("jvm_birth_utc"),"native",firstNativeAttestation)),StandardOpenOption.CREATE_NEW);
        evidence.put("jvm_identity_evidence",Map.of("path",jvmIdentity.toAbsolutePath().toString(),"sha256",sha(jvmIdentity)));
        try(var privatePool=pool("d104-private",18852,"admin","quest");
            var formalPool=pool("d104-formal",8812,required("APP_QUESTDB_USERNAME"),required("APP_QUESTDB_PASSWORD"))) {
            var jdbc=jdbc(privatePool);var formal=jdbc(formalPool);var formalBefore=formalSnapshot(formal);evidence.put("formal_before",formalBefore);
            var properties=new QuestDbProperties();properties.setHost("127.0.0.1");properties.setPgPort(18852);properties.setQwpPort(19040);
            properties.setUsername("admin");properties.setPassword("quest");
            var owner=new MacroCoreMonthlyJobService(new QuestDbMacroCoreMonthlySourceReader(jdbc),new QuestDbMacroCoreMonthlyTarget(jdbc,properties,TABLE),LEDGER);
            var admittedSource=owner.source().read(JUNE,initial?JULY:AUGUST);
            assertRows(oracle(baseline,initial?2:3),admittedSource.rows());assertEquals(initial?23:28,admittedSource.rawRows());
            var formalSource=new MacroCoreMonthlySource(new QuestDbMacroCoreMonthlySourceReader(formal)).read(JUNE,initial?JULY:AUGUST);
            assertEquals(formalSource.rawFingerprint(),admittedSource.rawFingerprint(),
                    "Complete six-source raw window and SF context must equal real formal capture");
            assertRows(admittedSource.rows(),formalSource.rows());
            evidence.put("formal_source",formalSource);
            evidence.put("admitted_source",admittedSource);
            if(initial) {
                assertFalse(Files.exists(LEDGER));
                Path claim=DIRECTORY.resolve("java-output-create-once-20261007.json");
                nativeAttestations.add(nativeIdentity("before_create",privateIdentity));
                assertEquals(admittedSource,owner.source().read(JUNE,JULY));
                Files.writeString(claim,"{\"task_id\":\"D104\",\"ack\":\"UNKNOWN\",\"automatic_retry\":false}",StandardOpenOption.CREATE_NEW);
                var installed=owner.installIsolated();assertEquals(0,installed.rowCount());assertTrue(installed.settled());
                Files.writeString(claim,JobDefinitionJson.mapper().writeValueAsString(Map.of("task_id","D104","ack","ACKNOWLEDGED","automatic_retry",false,"snapshot",installed)),StandardOpenOption.TRUNCATE_EXISTING);
                var emptyRepository=new MacroCoreMonthlyReadRepository(new QuestDbBoundedReader(jdbc),TABLE);
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
                var cancelledForResume="d104-cancelled-resume-"+UUID.randomUUID();
                var cancelledResult=new SyncJobRunner<MacroCoreMonthly,YearMonth>(new SyncRunLedger(LEDGER),new DatasetIntervalLock(LEDGER))
                        .run(cancelledForResume,null,resumePlan.targetId(),resumePlan.request(),
                                new MacroCoreMonthlyMaterializeAdapter(owner.source(),owner.writePort(),resumePlan.source()),()->true);
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
            assertEquals(initial?23:28,sourceBefore.rawRows());assertRows(expected,sourceBefore.rows());
            var reader=new QuestDbBoundedReader(jdbc);var repository=new MacroCoreMonthlyReadRepository(reader,TABLE);
            YearMonth end=YearMonth.of(2026,initial?8:9);var actual=new ArrayList<MacroCoreMonthly>();DatasetReadCursor cursor=null;
            var firstPage=repository.findRange(YearMonth.of(2026,6),end,1,null);assertNotNull(firstPage.nextCursor());
            assertThrows(IllegalArgumentException.class,()->repository.findRange(YearMonth.of(2026,6),end.plusMonths(1),1,firstPage.nextCursor()));
            do {var page=repository.findRange(YearMonth.of(2026,6),end,1,cursor);actual.addAll(page.rows());cursor=page.nextCursor();}while(cursor!=null);
            assertRows(expected,actual);for(var row:expected)assertRows(List.of(row),repository.findForMonth(row.month()).rows());
            assertTrue(repository.findForMonth(YearMonth.of(2026,9)).rows().isEmpty());
            var datasets=new DatasetRegistry(List.of(repository));
            var group=new ReadGroupConfiguration().readGroupReader(datasets,reader);
            Path request=DIRECTORY.resolve("read-group-"+stage+"-20261007.json");
            var query=new LinkedHashMap<String,Object>();query.put("columns",MacroCoreMonthlyDataset.STORAGE_COLUMNS);query.put("equalities",Map.of());query.put("rangeColumn","month");
            query.put("fromInclusive",JUNE);query.put("toExclusive",end.atDay(1));query.put("pageSize",12);query.put("cursor",null);
            Files.writeString(request,JobDefinitionJson.mapper().writeValueAsString(Map.of("timeoutMillis",30000,"members",List.of(Map.of(
                    "memberId","styles","datasetId","macro_core_monthly","definitionVersion",1,"query",query)))),StandardOpenOption.CREATE_NEW);
            var groupResult=group.read(group.readRequest(request),()->false);assertTrue(groupResult.complete());assertFalse(groupResult.atomicSnapshot());
            assertRows(expected,groupResult.require("styles").typedPage(MacroCoreMonthly.class).rows());evidence.put("configured_read_group",groupResult);
            var cancelled=group.read(group.readRequest(request),()->true);assertEquals(ReadGroupReader.Status.CANCELLED,cancelled.require("styles").status());
            if(initial) {
                var write=new StockBasicWriteGroupService(datasets,null,jdbc,null,LEDGER.toString());
                var ownerField=StockBasicWriteGroupService.class.getDeclaredField("macroCoreMonthlyTarget");ownerField.setAccessible(true);ownerField.set(write,owner);
                Path writeRequest=DIRECTORY.resolve("write-group-initial-20261007.json");
                Files.writeString(writeRequest,JobDefinitionJson.mapper().writeValueAsString(Map.of("batchId","d104-actual-june","logicalDate",LOGICAL,"members",List.of(Map.of(
                        "memberId","styles","datasetId","macro_core_monthly","definitionVersion",1,"batchId","d104-actual-june-member",
                        "rows",List.of(new MacroCoreMonthlyMapper().values(expected.getFirst()).asMap()))))),StandardOpenOption.CREATE_NEW);
                nativeAttestations.add(nativeIdentity("before_typed_write_group",privateIdentity));
                assertEquals(admittedSource,owner.source().read(JUNE,JULY));
                var written=write.run(writeRequest,null);assertEquals(SyncRunState.VERIFIED,written.state());evidence.put("configured_write_group",written);runs.add(written.runId());
                assertRows(expected,owner.writePort().readActualRange(YearMonth.of(2026,6),YearMonth.of(2026,7)));
                var plan=owner.plan(JUNE,JULY,LOGICAL,SyncJobDefinition.Mode.MATERIALIZE);
                var stoppedRun="d104-cancelled-"+UUID.randomUUID();var ledger=new SyncRunLedger(LEDGER);
                var adapter=new MacroCoreMonthlyMaterializeAdapter(owner.source(),owner.writePort(),plan.source());
                var stopped=new SyncJobRunner<MacroCoreMonthly,YearMonth>(ledger,new DatasetIntervalLock(LEDGER))
                        .run(stoppedRun,null,plan.targetId(),plan.request(),adapter,()->true);
                assertEquals(SyncRunState.CANCELLED,stopped.state());assertEquals(0,stopped.verifiedRows());runs.add(stoppedRun);evidence.put("cancelled_before_source",stopped);
            }
            assertRows(expected,owner.writePort().readActualRange(YearMonth.of(2026,6),YearMonth.of(2026,initial?7:8)));
            assertEquals(sourceBefore,owner.source().read(JUNE,initial?JULY:AUGUST));
            assertEquals(formalSource,new MacroCoreMonthlySource(new QuestDbMacroCoreMonthlySourceReader(formal)).read(JUNE,initial?JULY:AUGUST));
            var formalAfter=formalSnapshot(formal);assertEquals(formalBefore,formalAfter);evidence.put("formal_after",formalAfter);
            var ledger=SyncRunLedger.openReadOnly(LEDGER);for(var id:runs) {
                var state=ledger.get(id).state();assertTrue(Set.of(SyncRunState.VERIFIED,SyncRunState.CANCELLED).contains(state));
                if(ledger.getRun(id).jobId().equals(MacroCoreMonthlyJobService.JOB_ID))assertEquals(state,owner.status(id).state());
            }
            owner.requireNoPendingPublication();evidence.put("actual_target",owner.writePort().targetSnapshot());
            evidence.put("source",sourceBefore);evidence.put("expected_rows",expected.stream().map(new MacroCoreMonthlyMapper()::values).toList());
            evidence.put("key_and_full_field_comparisons",expected.size()*9);
            evidence.put("nullable_double_slot_comparisons",expected.size()*8);
            long nonNullBits=expected.stream().map(new MacroCoreMonthlyMapper()::values)
                    .flatMap(values->values.asMap().values().stream()).filter(value->value instanceof Double).count();
            evidence.put("exact_double_bit_comparisons",nonNullBits);
            nativeAttestations.add(nativeIdentity("after_all_verification",privateIdentity));
        }
        evidence.put("run_ids",runs);evidence.put("ledger",LEDGER.toAbsolutePath().toString());evidence.put("formal_mutated",false);evidence.put("reference_project_mutated",false);
        evidence.put("actual_source_revision",false);evidence.put("actual_source_increment",!initial);evidence.put("double_tolerance",0);
        evidence.put("finished_at",Instant.now());evidence.put("status",initial?"VERIFIED_ISOLATED_INITIAL_REPLAY":"VERIFIED_ISOLATED_INCREMENTAL");
        Files.writeString(output,JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence),StandardOpenOption.CREATE_NEW);
    }
    private static void verified(MacroCoreMonthlyJobService.MaterializationResult result,int rows) {
        assertEquals(SyncRunState.VERIFIED,result.result().state());assertEquals(rows,result.result().verifiedRows());assertNull(result.targetSnapshotError());
    }
    static List<MacroCoreMonthly> oracle(JsonNode preflight,int count) {
        var result=new ArrayList<MacroCoreMonthly>();var mapper=new MacroCoreMonthlyMapper();
        for(var row:preflight.required("expected_oracle_rows")) {
            if(result.size()==count)break;var values=new LinkedHashMap<String,Object>();
            for(String column:MacroCoreMonthlyDataset.STORAGE_COLUMNS) {
                JsonNode value=row.required(column);values.put(column,column.equals("month")?LocalDate.parse(value.asText().substring(0,10)):value.isNull()?null:value.doubleValue());
            }
            result.add(mapper.fromValues(values));
        }
        assertEquals(count,result.size());return result;
    }
    static void assertRows(List<MacroCoreMonthly> expected,List<MacroCoreMonthly> actual) {
        assertEquals(expected.size(),actual.size());for(int i=0;i<expected.size();i++)assertTrue(MacroCoreMonthlyWritePort.CODEC.equivalent(expected.get(i),actual.get(i)),"Exact month/null/8-double-slot values differ at "+i);
    }
    static HikariDataSource pool(String name,int port,String user,String password) {
        var config=new HikariConfig();config.setPoolName(name);config.setJdbcUrl("jdbc:postgresql://127.0.0.1:"+port+"/qdb?socketTimeout=20&connectTimeout=10");
        config.setUsername(user);config.setPassword(password);config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(10000);config.setInitializationFailTimeout(-1);return new HikariDataSource(config);
    }
    static JdbcTemplate jdbc(HikariDataSource source) {var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(20);jdbc.setMaxRows(64);return jdbc;}
    static Map<String,Object> formalSnapshot(JdbcTemplate jdbc) {
        var output=new LinkedHashMap<String,Object>();for(String table:List.of("cn_cpi","cn_ppi","cn_pmi","cn_m","cn_gdp","sf_month","macro_core_monthly")) {
            output.put(table,Map.of("physical",jdbc.queryForList("SELECT id,directoryName,table_txn,table_row_count,partitionBy,designatedTimestamp,walEnabled,dedup,table_suspended,wal_pending_row_count FROM tables() WHERE table_name=?",table),
                    "wal",jdbc.queryForList("SELECT sequencerTxn,writerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name=?",table),
                    "count",jdbc.queryForObject("SELECT count() FROM \""+table+"\"",Long.class),"schema",jdbc.queryForList("SELECT * FROM table_columns('"+table+"') LIMIT 61")));
        }return output;
    }
    static String required(String name) {String value=System.getenv(name);assertNotNull(value,name+" required");assertFalse(value.isBlank());return value;}
    static void assertEvidence(JsonNode item) throws Exception {assertEquals(item.required("sha256").asText(),sha(Path.of(item.required("path").asText())));}
    static Map<String,Object> nativeIdentity(String phase,JsonNode expected) throws Exception {
        String script="""
                $ErrorActionPreference='Stop'
                $taskListeners=@(Get-NetTCPConnection -LocalPort 19040,18852 -State Listen -ErrorAction Stop)
                $taskRecords=@(foreach($taskPort in @(19040,18852)) {
                  $taskMatches=@($taskListeners | Where-Object LocalPort -eq $taskPort)
                  if($taskMatches.Count -ne 1) { throw 'Ambiguous D104 listener' }
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
            var ports=new HashSet<Integer>();String root=Path.of("var/d104-isolated-questdb").toAbsolutePath().normalize().toString();
            assertEquals(root.toLowerCase(Locale.ROOT),expected.required("data_root").asText().toLowerCase(Locale.ROOT));
            for(var record:records) {
                ports.add(record.required("port").asInt());assertEquals("127.0.0.1",record.required("address").asText());
                assertEquals(expected.required("pid").asLong(),record.required("pid").asLong());
                assertEquals(Instant.parse(expected.required("birth_utc").asText()),Instant.parse(record.required("birth").asText()));
                assertEquals("java.exe",record.required("name").asText().toLowerCase(Locale.ROOT));
                String command=record.required("command").asText().replace('/','\\').toLowerCase(Locale.ROOT);
                assertTrue(command.contains(root.toLowerCase(Locale.ROOT)));assertTrue(command.contains("io.questdb\\io.questdb.servermain"));
            }
            assertEquals(Set.of(19040,18852),ports);
            return Map.of("phase",phase,"observed_at",Instant.now(),"records",records,"attestation_child_pid",process.pid(),"attestation_child_stopped",true,"jvm_birth_utc",exactJvmBirth);
        } finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}process.getInputStream().close();}
    }
    static String sha(Path path) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
}
