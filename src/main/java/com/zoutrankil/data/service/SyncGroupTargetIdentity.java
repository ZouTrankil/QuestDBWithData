package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;

/** Original targets exist in the frozen group even before a member has a child run. */
final class SyncGroupTargetIdentity {
    private SyncGroupTargetIdentity() {}
    static String frozen(Path path,String groupRun,String jobId) throws Exception {
        var ledger=SyncRunLedger.openReadOnly(path);
        var run=ledger.getRun(groupRun);
        var slots=ledger.groupMembers(groupRun).stream().filter(m->m.jobId().equals(jobId)).toList();
        if(slots.size()!=1) throw new IllegalStateException("Exactly one frozen group member required");
        var slot=slots.getFirst();
        var members=JobDefinitionJson.mapper().readTree(run.frozenJson()).path("members");
        String target=null;
        for(var member:members) if(member.path("jobId").asText().equals(jobId)) {
            if(target!=null || member.path("ordinal").asInt(-1)!=slot.ordinal())
                throw new IllegalStateException("Frozen group member identity differs");
            target=member.path("targetId").asText();
        }
        if(target==null) throw new IllegalStateException("Frozen group target missing");
        SyncJobDefinition.name(target);
        return target;
    }
}
