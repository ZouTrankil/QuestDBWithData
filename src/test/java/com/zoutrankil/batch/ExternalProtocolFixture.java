package com.zoutrankil.batch;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Java-only protocol peer; deliberately not a production runner. */
public final class ExternalProtocolFixture {
    public static void main(String[] args) throws Exception {
        var invocation=Json.MAPPER.readValue(System.in.readAllBytes(),RestrictedExternalExecutor.Invocation.class);
        var r=invocation.request();String mode=args.length==0?"verified":args[0];
        if(mode.equals("slow"))Thread.sleep(700);
        var logicalDate=mode.equals("wrong-date")?r.logicalDate().plusDays(1):r.logicalDate();
        Path artifact=Path.of(invocation.outputDirectory()).resolve("result.json");
        String definitionVersion=mode.equals("wrong-version")?"definition-drift":r.definitionVersion();
        var evidence=new CompletionEvidence(1,"external:"+invocation.stage(),r.instanceId(),invocation.stage(),
                RunRequest.hash("fixture",invocation.childId()),logicalDate,r.rangeStart(),r.rangeEnd(),r.rangeStart(),r.rangeEnd(),
                definitionVersion,"fixture-source",r.inputFingerprint(),1,mode.equals("partial")?0:1,
                true,true,true,true,true,false,Instant.now(),mode.equals("partial")?BusinessState.PARTIAL:BusinessState.VERIFIED,
                artifact.toString(),null);
        if(mode.equals("stale-file")) {
            var stale=new CompletionEvidence(1,evidence.producer(),evidence.instanceId(),evidence.stage(),evidence.batchId(),
                    evidence.logicalDate(),evidence.requestedStart(),evidence.requestedEnd(),evidence.actualStart(),evidence.actualEnd(),
                    evidence.definitionVersion(),evidence.sourceVersion(),"stale-fingerprint",evidence.readRows(),evidence.writtenRows(),
                    evidence.uniqueKeys(),evidence.completeCoverage(),evidence.physicallyVisible(),evidence.qualityPassed(),
                    evidence.sourceProbeComplete(),evidence.emptyAllowed(),evidence.availableAt(),evidence.state(),artifact.toString(),null);
            Files.writeString(artifact,Json.write(stale));
        } else {
            Files.writeString(artifact,Json.write(evidence));
        }
        System.out.println(Json.write(Map.of("type","progress","at",Instant.now().toString(),"message","fixture completed")));
        System.out.println(Json.write(Map.of("type","result","evidence",evidence)));
    }
}
