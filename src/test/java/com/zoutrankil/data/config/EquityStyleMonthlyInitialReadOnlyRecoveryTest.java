package com.zoutrankil.data.config;
import com.zoutrankil.data.derived.storage.QuestDbEquityStyleMonthlySourceReader;
import com.zoutrankil.data.derived.storage.QuestDbEquityStyleMonthlyTarget;

import com.zoutrankil.data.derived.application.EquityStyleMonthlyJobService;
import com.zoutrankil.data.derived.storage.EquityStyleMonthlyReadRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.config.EquityStyleMonthlyLiveAcceptanceTest.*;

/** Revalidates the already completed initial operations; never creates or publishes. */
@EnabledIfEnvironmentVariable(named="D103_READONLY_RECOVERY",matches="true")
class EquityStyleMonthlyInitialReadOnlyRecoveryTest {
    private static final Path DIRECTORY=Path.of("artifacts/java-migration/D103/commands");
    private static final Path LEDGER=Path.of("var/d103-java-acceptance.sqlite3");
    private static final LocalDate JUNE=LocalDate.of(2026,6,1),JULY=LocalDate.of(2026,7,1);
    private static final String TABLE="java_d103_equity_style_monthly_acceptance";
    private static final String SOURCE_RECEIPT_SHA="e2a2099b7c39526414594bb9816718481e40131a34c5d563161a600c6484f236";
    private static final String FAILURE_GATE_SHA="d449e9713a69d478e511f485b0a1bc52d24a0d68e86ecb72cac2dfc47227c893";

    @Test void actualInitialPrefixIsRecoveredWithoutPublication() throws Exception {
        var json=JobDefinitionJson.mapper();var result=new LinkedHashMap<String,Object>();
        result.put("task_id","D103");result.put("stage","readonly_recovery");result.put("started_at",Instant.now());result.put("status","FAILED");
        String attempt=System.getenv().getOrDefault("D103_RECOVERY_ATTEMPT","");assertTrue(attempt.isEmpty()||attempt.matches("[a-z0-9]{1,24}"));
        String suffix=attempt.isEmpty()?"":"-"+attempt;result.put("attempt_label",attempt);
        Path output=DIRECTORY.resolve("java-initial-readonly-recovery"+suffix+"-20261006.json");assertFalse(Files.exists(output));
        Path sourceReceipt=DIRECTORY.resolve("source-fixture-initial-readonly-reconciliation-20261006.json");assertEquals(SOURCE_RECEIPT_SHA,sha(sourceReceipt));
        var fixture=json.readTree(sourceReceipt.toFile());assertEquals("VERIFIED_INITIAL_SOURCE_BY_READONLY_RECONCILIATION",fixture.required("status").asText());
        Path gatePath=DIRECTORY.resolve("coordinator-initial-java-receipt-failure-recovery-20261006.json");assertEquals(FAILURE_GATE_SHA,sha(gatePath));
        var gate=json.readTree(gatePath.toFile());assertEquals("accepted_for_readonly_receipt_recovery",gate.required("decision").asText());
        assertTrue(gate.required("original_jvm_pid_birth_not_recorded").asBoolean());assertTrue(gate.required("retry_forbidden").asBoolean());
        for(String item:List.of("failed_junit","log","executed_test_source","port_source","adapter_source","runner_source","typed_adapter_source","original_executor_completion","ledger_snapshot","create_claim","initial_source_recovery"))assertEvidence(gate.required(item));
        var original=json.readTree(Path.of(gate.required("ledger_snapshot").required("path").asText()).toFile());
        assertEquals(8,original.required("runs").size());assertEquals(18,original.required("entries").size());assertTrue(original.required("leases").isEmpty());
        var nativeStart=nativeIdentity("readonly_recovery_start",fixture.required("private_target_attestation"));
        result.put("jvm_pid",ProcessHandle.current().pid());result.put("jvm_birth_utc",nativeStart.get("jvm_birth_utc"));
        Path identity=DIRECTORY.resolve("java-readonly-recovery-jvm-identity"+suffix+"-20261006.json");
        Files.writeString(identity,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("task_id","D103","purpose","SELECT-only initial receipt recovery","jvm_pid",ProcessHandle.current().pid(),"jvm_birth_utc",nativeStart.get("jvm_birth_utc"),"native",nativeStart)),StandardOpenOption.CREATE_NEW);
        result.put("jvm_identity_evidence",binding(identity));result.put("failed_initial_gate",binding(gatePath));
        result.put("fixture_receipt",sourceReceipt.toAbsolutePath().toString());result.put("fixture_sha256",SOURCE_RECEIPT_SHA);
        result.put("preflight_sha256",fixture.required("preflight_evidence").required("sha256").asText());
        try {
            assertEvidence(fixture.required("preflight_evidence"));var preflight=json.readTree(Path.of(fixture.required("preflight_evidence").required("path").asText()).toFile());
            var expected=oracle(preflight,2);var ledgerBefore=ledgerSnapshot();assertSnapshot(original,ledgerBefore);
            try(var privatePool=pool("d103-private-recovery",18842,"admin","quest");var formalPool=pool("d103-formal-recovery",8812,required("APP_QUESTDB_USERNAME"),required("APP_QUESTDB_PASSWORD"))) {
                var privateJdbc=jdbc(privatePool);var formalJdbc=jdbc(formalPool);var formalBefore=formalSnapshot(formalJdbc);
                var properties=new QuestDbProperties();properties.setHost("127.0.0.1");properties.setPgPort(18842);properties.setQwpPort(19030);properties.setUsername("admin");properties.setPassword("quest");
                var owner=new EquityStyleMonthlyJobService(new QuestDbEquityStyleMonthlySourceReader(privateJdbc,"index_monthly"),new QuestDbEquityStyleMonthlyTarget(privateJdbc,properties,TABLE),LEDGER);
                owner.requireNoPendingPublication();var batch=owner.source().read(JUNE,JULY);assertEquals(32,batch.rawRows());assertRows(expected,batch.rows());
                var sourceState=fixture.required("private_after").required("index_monthly");
                assertEquals(sourceState.required("physical").required("id").asLong(),batch.snapshot().tableId());assertEquals(sourceState.required("physical").required("directoryName").asText(),batch.snapshot().directory());
                assertEquals(1,batch.snapshot().physicalTxn());assertEquals(1,batch.snapshot().sequenceTxn());
                var port=owner.writePort();var before=port.targetSnapshot();assertEquals(2,before.rowCount());assertTrue(before.settled());
                var created=json.readTree(Path.of(gate.required("create_claim").required("path").asText()).toFile());assertEquals("ACKNOWLEDGED",created.required("ack").asText());assertEquals(created.required("snapshot").required("targetId").asText(),before.targetId());
                assertRows(expected,port.readActualRange(YearMonth.of(2026,6),YearMonth.of(2026,7)));
                var operations=new ArrayList<Map<String,Object>>();var originalIds=new ArrayList<String>();
                for(var operation:original.required("original_operations")) {
                    String id=operation.required("run_id").asText(),role=operation.required("role").asText();originalIds.add(id);
                    var saved=find(ledgerBefore.get("runs"),"id",id);var entry=find(ledgerBefore.get("entries"),"id",id);
                    assertEquals(operation.required("ledger_entry"),json.valueToTree(entry));assertEquals(operation.required("state").asText(),entry.get("state"));
                    var request=json.readTree((String)saved.get("frozen_json"));assertEquals(operation.get("mode"),request.has("mode")?request.get("mode"):json.valueToTree((Object)null));
                    assertEquals(operation.get("parent_run_id"),json.valueToTree(saved.get("parent_run_id")));
                    var receipts=new ArrayList<Map<String,Object>>();
                    for(var event:ledgerBefore.get("events")) {
                        var child=find(ledgerBefore.get("entries"),"id",(String)event.get("entry_id"));
                        if(!id.equals(child.get("run_id")))continue;
                        if(operation.required("state").asText().equals("CANCELLED"))assertFalse(Set.of("FETCHED","SUBMITTED","ACKNOWLEDGED").contains(event.get("state")));
                        if("ACKNOWLEDGED".equals(event.get("state"))&&"SLICE".equals(child.get("kind"))) {
                            var write=json.readTree((String)event.get("payload_json")).required("writeResult");assertEquals("VERIFIED",write.required("status").asText());
                            assertEquals(operation.required("verified_rows").asInt(),write.required("verifiedRows").asInt());
                            for(var receipt:write.required("receipts"))assertEquals("ACKNOWLEDGED",receipt.required("delivery").asText());receipts.add(event);
                        }
                        if("FETCHED".equals(event.get("state"))&&"data.equity_style_monthly".equals(saved.get("job_id"))) {
                            var evidence=json.readTree(json.readTree((String)event.get("payload_json")).required("responseEvidence").asText());
                            assertEquals(batch.snapshot().version(),evidence.required("sourceVersion").asText());assertEquals(batch.rawFingerprint(),evidence.required("rawSourceFingerprint").asText());
                            assertEquals(32,evidence.required("rawSourceRows").asInt());assertTrue(evidence.required("complete").asBoolean());
                            var expectedMaps=expected.stream().map(row->new EquityStyleMonthlyMapper().values(row).asMap()).toList();assertEquals(json.valueToTree(expectedMaps),evidence.required("fullPrefixExpected"));
                        }
                    }
                    if("data.equity_style_monthly".equals(saved.get("job_id"))) {
                        assertEquals(JUNE.toString(),request.required("from").asText());assertEquals(JULY.toString(),request.required("to").asText());
                        assertEquals(batch.snapshot().version(),request.required("parameters").required("source_version").asText());assertEquals(batch.rawFingerprint(),request.required("parameters").required("source_hash").asText());
                        assertEquals(operation.required("state").asText(),owner.status(id).state().name());
                    }
                    if(operation.required("state").asText().equals("VERIFIED")) {
                        var proof=json.readTree((String)entry.get("payload_json")).required("verification");assertTrue(proof.required("passed").asBoolean());
                        assertEquals(operation.required("verified_rows").asInt(),proof.required("matchedRows").asInt());assertEquals(0,proof.required("mismatchedRows").asInt());assertEquals(0,proof.required("missingKeys").asInt());assertEquals(0,proof.required("duplicateKeys").asInt());
                        if(!role.equals("configured_write_group"))assertEquals(1,receipts.size());
                    }
                    var recovered=new LinkedHashMap<String,Object>();operation.fields().forEachRemaining(e->recovered.put(e.getKey(),json.convertValue(e.getValue(),Object.class)));
                    recovered.put("acked_slice_receipts",receipts);recovered.put("proof_origin","Original immutable SQLite events revalidated by SELECT; no replay");operations.add(recovered);
                }
                var repository=new EquityStyleMonthlyReadRepository(new QuestDbBoundedReader(privateJdbc),TABLE);
                var first=repository.findRange(YearMonth.of(2026,6),YearMonth.of(2026,8),1,null);assertNotNull(first.nextCursor());
                var second=repository.findRange(YearMonth.of(2026,6),YearMonth.of(2026,8),1,first.nextCursor());assertNull(second.nextCursor());
                var pages=new ArrayList<EquityStyleMonthly>(first.rows());pages.addAll(second.rows());assertRows(expected,pages);
                for(var row:expected)assertRows(List.of(row),repository.findForMonth(row.month()).rows());assertTrue(repository.findForMonth(YearMonth.of(2026,9)).rows().isEmpty());
                var datasets=new DatasetRegistry(List.of(repository,(DatasetImplementation)()->IndexMonthlyDataset.DEFINITION,(DatasetImplementation)()->IndexCatalogDataset.DEFINITION));
                var group=new ReadGroupConfiguration().readGroupReader(datasets,new QuestDbBoundedReader(privateJdbc));
                var readRequest=DIRECTORY.resolve("read-group-initial-20261006.json");var read=group.read(group.readRequest(readRequest),()->false);assertTrue(read.complete());assertRows(expected,read.require("styles").typedPage(EquityStyleMonthly.class).rows());
                assertEquals(ReadGroupReader.Status.CANCELLED,group.read(group.readRequest(readRequest),()->true).require("styles").status());
                var writeRequest=DIRECTORY.resolve("write-group-initial-20261006.json");var writeRow=json.readTree(writeRequest.toFile()).required("members").get(0).required("rows").get(0);
                assertEquals(json.valueToTree(new EquityStyleMonthlyMapper().values(expected.getFirst()).asMap()),writeRow);
                assertEquals(batch,owner.source().read(JUNE,JULY));assertEquals(before,port.targetSnapshot());assertRows(expected,port.readActualRange(YearMonth.of(2026,6),YearMonth.of(2026,7)));
                var formalAfter=formalSnapshot(formalJdbc);assertEquals(formalBefore,formalAfter);
                result.put("source",batch);result.put("actual_target",before);result.put("expected_rows",expected.stream().map(new EquityStyleMonthlyMapper()::values).toList());
                result.put("original_operations",operations);result.put("original_run_ids",originalIds);result.put("configured_read_group",read);result.put("read_request_evidence",binding(readRequest));result.put("original_write_request_evidence",binding(writeRequest));
                result.put("formal_before",formalBefore);result.put("formal_after",formalAfter);result.put("native_start",nativeStart);result.put("native_finish",nativeIdentity("readonly_recovery_finish",fixture.required("private_target_attestation")));
            }
            var ledgerAfter=ledgerSnapshot();assertEquals(ledgerBefore,ledgerAfter);result.put("ledger_snapshot",ledgerAfter);result.put("ledger",LEDGER.toAbsolutePath().toString());
            result.put("key_and_full_field_comparisons",60);result.put("exact_double_bit_comparisons",58);result.put("double_tolerance",0);result.put("status","VERIFIED_ISOLATED_INITIAL_BY_READONLY_RECOVERY");
        } catch(Throwable failure) {result.put("error",Map.of("type",failure.getClass().getName(),"message",String.valueOf(failure.getMessage())));throw failure;}
        finally {
            result.put("finished_at",Instant.now());result.put("new_ddl",0);result.put("new_dml",0);result.put("new_ilp_batches",0);result.put("new_materialization_runs",0);result.put("ledger_mutated",false);result.put("formal_mutated",false);result.put("reference_project_mutated",false);result.put("original_sender_pid_birth_not_recorded",true);result.put("original_junit_status","FAILED_RECEIPT_SERIALIZATION");
            Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
        }
    }
    private static Map<String,Object> binding(Path path) throws Exception {return Map.of("path",path.toAbsolutePath().toString(),"sha256",sha(path));}
    private static Map<String,Object> find(List<Map<String,Object>> rows,String key,String value){return rows.stream().filter(r->value.equals(r.get(key))).findFirst().orElseThrow();}
    private static void assertSnapshot(JsonNode original,Map<String,List<Map<String,Object>>> actual){for(var entry:actual.entrySet())assertEquals(original.required(entry.getKey()),JobDefinitionJson.mapper().valueToTree(entry.getValue()));}
    private static Map<String,List<Map<String,Object>>> ledgerSnapshot() throws Exception {
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+LEDGER.toAbsolutePath().toUri().toASCIIString()+"?mode=ro");var statement=db.createStatement()) {
            statement.execute("PRAGMA query_only=ON");statement.execute("PRAGMA busy_timeout=5000");statement.setQueryTimeout(5);statement.setMaxRows(1001);
            for(var item:List.of(new String[]{"runs","sync_runs","id"},new String[]{"entries","sync_entries","id"},new String[]{"events","sync_events","entry_id,revision"},new String[]{"groups","sync_group_members","parent_run_id,ordinal"},new String[]{"leases","sync_interval_locks","id"})) {
                var rows=new ArrayList<Map<String,Object>>();try(var rs=statement.executeQuery("SELECT * FROM "+item[1]+" ORDER BY "+item[2]+" LIMIT 1001")) {
                    var metadata=rs.getMetaData();while(rs.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=metadata.getColumnCount();i++)row.put(metadata.getColumnName(i),rs.getObject(i));rows.add(row);assertTrue(rows.size()<=1000);}
                }result.put(item[0],rows);
            }
        }return result;
    }
}
