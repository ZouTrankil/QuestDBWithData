package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataReadinessTest {
    @TempDir Path archive;
    private static final LocalDate DAY=LocalDate.of(2026,9,28);
    private static final Instant AT=Instant.parse("2026-09-28T12:00:00Z");

    @Test void dataReadyBlocksUntilEveryCoreSourceHasMatchingVerifiedCertificate() {
        var ledger=mock(SqliteLedger.class);var root=rootRequest();
        when(ledger.latestSourceCertificates(DAY)).thenReturn(Map.of());
        var stage=PostCloseGraph.STAGES.getFirst();
        var result=new DataReadiness(ledger,archive,(request,ignored)->new StageExecutor.Result(BusinessState.BLOCKED,null,"not-used"))
                .execute(root,stage);
        assertEquals(BusinessState.BLOCKED,result.state());
        assertNull(result.evidence());
        assertTrue(result.reason().contains("daily:missing"));
    }

    @Test void dataReadyEmitsImmutableAggregateCertificateOnlyForDateAndCalendarAlignedSources() throws Exception {
        var ledger=mock(SqliteLedger.class);var root=rootRequest();
        when(ledger.environmentNamespace()).thenReturn("fixture");
        var certificates=new LinkedHashMap<String,SqliteLedger.SourceCertificateState>();
        for(String dataset:DataReadiness.REQUIRED_SOURCES) {
            byte[] frozenBytes=Json.write(Map.of("dataset",dataset)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String inputFingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozenBytes));
            var source=sourceRequest(dataset,root.calendarVersion(),inputFingerprint);
            var artifact=archive.resolve("writes").resolve(dataset).resolve("certificate.json");
            Files.createDirectories(artifact.getParent());Files.writeString(artifact,"{}");
            var frozen=archive.resolve("sources").resolve("raw").resolve(dataset+".json");
            Files.createDirectories(frozen.getParent());Files.write(frozen,frozenBytes);
            boolean verifiedEmpty=dataset.equals("stk_st_daily");
            var evidence=new CompletionEvidence(1,"java-source:"+dataset,source.instanceId(),"Source:"+dataset,
                    "batch-"+dataset,DAY,DAY,DAY,DAY,DAY,SourceContract.load(dataset).version(),"source-v1",
                    source.inputFingerprint(),verifiedEmpty?0:1,verifiedEmpty?0:1,true,true,true,true,true,verifiedEmpty,
                    AT,BusinessState.valueOf(verifiedEmpty?"VERIFIED_EMPTY":"VERIFIED"),artifact.toString(),null);
            Files.writeString(artifact,Json.write(Map.of("schemaVersion",1,"target","jdb_test_fixture_"+dataset+"_"+source.instanceId(),
                    "sourceArtifact",frozen.toString(),"sourceFingerprint",source.inputFingerprint(),
                    "definitionVersion",SourceContract.load(dataset).version(),"verifiedRows",verifiedEmpty?0:1,"batches",List.of("batch-"+dataset))));
            certificates.put(dataset,new SqliteLedger.SourceCertificateState(source.job(),evidence.state(),Json.write(source),Json.write(evidence)));
        }
        when(ledger.latestSourceCertificates(DAY)).thenReturn(certificates);
        var result=new DataReadiness(ledger,archive,(request,stage)->new StageExecutor.Result(BusinessState.BLOCKED,null,"not-used"))
                .execute(root,PostCloseGraph.STAGES.getFirst());
        assertEquals(BusinessState.VERIFIED,result.state());assertNotNull(result.evidence());
        assertEquals(PostCloseGraph.STAGES.getFirst().id(),result.evidence().stage());
        assertTrue(result.evidence().matches(root,"DataReady"));
        assertEquals(DataReadiness.REQUIRED_SOURCES.size()-1,result.evidence().writtenRows());
        assertTrue(Files.isRegularFile(Path.of(result.evidence().artifact())));
        var revisedCalendar=new RunRequest("post-close-v2","post_close",DAY,DAY,DAY,"post-close-v1","0",null,null,"root-input","calendar-v2",
                "Asia/Shanghai",AT,AT,root.scopeIdentity());
        var stale=new DataReadiness(ledger,archive,(r,s)->null).execute(revisedCalendar,PostCloseGraph.STAGES.getFirst());
        assertEquals(BusinessState.BLOCKED,stale.state());assertTrue(stale.reason().contains("certificate-scope-or-artifact-mismatch"));
    }

    private static RunRequest rootRequest() {
        return new RunRequest("post-close","post_close",DAY,DAY,DAY,"post-close-v1","0",null,null,"root-input","calendar-v1",
                "Asia/Shanghai",AT,AT,RunRequest.hash("post-close-scope"));
    }
    private static RunRequest sourceRequest(String dataset,String calendar,String inputFingerprint) {
        return new RunRequest("source-"+dataset,"source_"+dataset,DAY,DAY,DAY,SourceContract.load(dataset).version(),"0",null,null,
                inputFingerprint,calendar,"Asia/Shanghai",AT,AT,RunRequest.hash(dataset,"scope"));
    }
}
