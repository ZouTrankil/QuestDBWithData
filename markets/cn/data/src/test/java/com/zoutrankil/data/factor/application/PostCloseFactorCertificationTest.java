package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.factor.domain.*;
import com.zoutrankil.data.factor.storage.QuestDbGlobalObservations;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.VerifiedSourceCertificates;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

class PostCloseFactorCertificationTest {
    @TempDir Path root;
    LocalDate day=LocalDate.parse("2026-10-16");
    QuestDbGlobalObservations physical=mock(QuestDbGlobalObservations.class);
    record Inputs(Path pipeline,Path approval,String sha){}
    Inputs prepare()throws Exception {
        var json=JobDefinitionJson.mapper();var definitions=FactorDefinitionCatalog.load().definitions();
        var stock=definitions.stream().filter(d->d.enabled()&&d.publishObservations()&&d.assetType().equals("stock")).findFirst().orElseThrow();
        var etf=definitions.stream().filter(d->d.enabled()&&d.publishObservations()&&d.assetType().equals("etf")).findFirst().orElseThrow();
        var a=new GlobalFactorObservation(day,stock.assetType(),"600000.SH",stock.factorId(),stock.version(),1d,day.atTime(16,0),"complete","GLOBAL",null);
        var b=new GlobalFactorObservation(day,etf.assetType(),"510500.SH",etf.factorId(),etf.version(),2d,day.atTime(16,0),"complete","GLOBAL",null);
        String fingerprint="a".repeat(64);String partFingerprint=FileEvidenceStore.sha256((fingerprint+"\n0\n1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path bundle=root.resolve("stock/observations");Files.createDirectories(bundle);
        String partSha=FactorObservationEvidence.writeNew(bundle.resolve("observations-000.json"),List.of(a),false,partFingerprint);
        Files.write(bundle.resolve("manifest.json"),json.writeValueAsBytes(new FactorObservationBundle.Manifest("java.factor_observation_bundle.v1",false,fingerprint,List.of(stock.factorId()),1,List.of(new FactorObservationBundle.Part("observations-000.json",1,partSha,partFingerprint)))));
        Path etfPath=root.resolve("etf/observations.json");String etfSha=FactorObservationEvidence.writeNew(etfPath,List.of(b),false,"b".repeat(64));
        var source=Map.of("table_name","daily_basic","id",1);Files.write(root.resolve("stock/compute.json"),json.writeValueAsBytes(Map.of("sourceState",List.of(source))));
        var panel=new TreeMap<String,Object>();for(String field:List.of("sourceState","l2SourceState","stockSourceState","holdingSourceState","technicalSourceState","csiFinancialSourceState","csiHoldingSourceState","csiMarketSourceState","earningsSourceState"))panel.put(field,List.of(source));
        Files.write(root.resolve("etf/panel.json"),json.writeValueAsBytes(panel));
        var ledger=new SyncRunLedger(root.resolve("ledger.sqlite3"));var def=new SyncJobDefinition("data.daily_basic",1,"daily_basic",1,"fixture",Set.of(Mode.BACKFILL),Mode.BACKFILL,Map.of(),"fixture","fixture","fixture",new RetryPolicy(1,Duration.ofMillis(1),Duration.ofSeconds(1)),Duration.ofSeconds(30),new Budget(1,1,1,100,10000),0,List.of(),Frequency.MANUAL,ZoneOffset.UTC,true,false);
        ledger.createRun("fixture-source",null,"fixture-target",def.freeze(Mode.BACKFILL,Map.of(),day,day,day));
        assertThrows(IllegalStateException.class,()->VerifiedSourceCertificates.publish(ledger.path(),"fixture-source",root.resolve("premature.json")));
        ledger.transition("fixture-source",0,SyncRunState.RUNNING,"{}");ledger.transition("fixture-source",1,SyncRunState.VERIFIED,json.writeValueAsString(Map.of("verification",Map.of("passed",true,"expectedRows",1,"actualRows",1,"matchedRows",1,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"sourceFingerprint","c".repeat(64),"readbackEvidence","fixture","writerStopped",true))));
        Path sourceCert=VerifiedSourceCertificates.publish(ledger.path(),"fixture-source",root.resolve("source-certificate.json"));
        var keys=new TreeSet<String>();for(var row:List.of(a,b))keys.add(JobDefinitionJson.canonicalMapper().writeValueAsString(List.of(day.toString(),row.entityType(),row.entityId(),row.factorId(),row.definitionVersion())));
        Path pipeline=Files.write(root.resolve("pipeline.json"),json.writeValueAsBytes(Map.of("schema","java.post_close_factor_pipeline.v1","date",day,"physicalVisibilityVerified",true,"phase","PARTIAL","stockReceipt",root.resolve("stock/pipeline.json").toString(),"etfReceipt",root.resolve("etf/pipeline.json").toString())));
        var snapshots=new ArrayList<Object>();for(int i=0;i<10;i++)snapshots.add(source);
        var approved=new LinkedHashMap<String,Object>();approved.put("schema","java.post_close_factor_source_approval.v1");approved.put("sourceComplete",true);approved.put("date",day);approved.put("stockInputFingerprint",fingerprint);approved.put("etfInputFingerprint","b".repeat(64));
        approved.put("stockBundleSha256",FactorObservationBundle.read(bundle).sha256());approved.put("etfEvidenceSha256",etfSha);approved.put("expectedObservationKeysSha256",PostCloseFactorCertification.digest(keys));
        approved.put("sources",Map.of("daily_basic",Map.of("certificate",sourceCert.toString(),"certificateSha256",FileEvidenceStore.sha256(Files.readAllBytes(sourceCert)),"sourceStateSha256",FileEvidenceStore.sha256(JobDefinitionJson.canonicalMapper().writeValueAsBytes(snapshots)))));
        Path approval=Files.write(root.resolve("approval.json"),json.writeValueAsBytes(approved));
        var byKey=Map.of(GlobalObservationCoverage.key(a),a,GlobalObservationCoverage.key(b),b);when(physical.state()).thenReturn(List.of(Map.of("txn",1)));
        when(physical.readKeys(anyList())).thenAnswer(call->{List<GlobalObservationCoverage.Key> requested=call.getArgument(0);return requested.stream().map(byKey::get).filter(Objects::nonNull).toList();});
        return new Inputs(pipeline,approval,FileEvidenceStore.sha256(Files.readAllBytes(approval)));
    }
    @Test void independentlyApprovedSourcesAndExactValuesProduceNewCertificateWithoutRewritingPartialReceipt()throws Exception {
        var in=prepare();byte[] original=Files.readAllBytes(in.pipeline());var certifier=new PostCloseFactorCertification(physical,(s,e)->{});
        Path output=certifier.certify(in.pipeline(),in.approval(),in.sha(),root.resolve("ready.json"));var proof=JobDefinitionJson.mapper().readTree(Files.readAllBytes(output));
        assertTrue(proof.path("factorReady").booleanValue());assertEquals(2,proof.path("rows").asInt());assertArrayEquals(original,Files.readAllBytes(in.pipeline()));
        Files.writeString(root.resolve("source-certificate.json"),"changed");assertThrows(IllegalStateException.class,()->certifier.certify(in.pipeline(),in.approval(),in.sha(),root.resolve("another.json")));
    }
    @Test void missingPhysicalKeysCannotBeCertifiedEvenWhenApprovalAndDatesMatch()throws Exception {
        var in=prepare();when(physical.readKeys(anyList())).thenReturn(List.of());
        assertThrows(IllegalStateException.class,()->new PostCloseFactorCertification(physical,(s,e)->{}).certify(in.pipeline(),in.approval(),in.sha(),root.resolve("ready.json")));assertFalse(Files.exists(root.resolve("ready.json")));
    }
}
