package com.zoutrankil.batch.testsupport;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.CompletionEvidence;
import com.zoutrankil.batch.domain.RunRequest;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Shared immutable inputs for Batch contract tests. */
public final class BatchFixtures {
    private BatchFixtures() {}
    public static RunRequest request(String id) {
        var date=LocalDate.of(2026,9,29);
        return new RunRequest(id,"post_close",date,date,date,"v1","0",null,null,"source-v1","cal-v1",
                "Asia/Shanghai",Instant.parse("2026-09-29T10:30:00Z"),Instant.parse("2026-09-29T10:30:01Z"));
    }

    public static CompletionEvidence evidence(RunRequest r,String stage) {
        return new CompletionEvidence(1,"test-only",r.instanceId(),stage,"b1",r.logicalDate(),r.rangeStart(),r.rangeEnd(),
            r.rangeStart(),r.rangeEnd(),r.definitionVersion(),"source-v1",r.inputFingerprint(),1,1,
            true,true,true,true,true,false,Instant.parse("2026-09-29T10:30:00Z"),BusinessState.VERIFIED,"fixture://test",null);
    }
}
