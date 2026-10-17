package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.domain.IndexMembershipState;
import com.zoutrankil.data.index.port.IndexMembershipTarget;



import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.util.*;

/** Revalidate a completed industry's full retained scope after other industries may have published. */
final class IndexMembershipCompletedRun {
    record Proof(String runId,SyncRunState state,int sourceRows,String receipt,String scopeFingerprint) {}
    private IndexMembershipCompletedRun() {}
    static Proof verify(IndexMembershipTarget backend,Path path,String table,String runId,SyncJobDefinition.FrozenRequest expected) throws Exception {
        var scopes=IndexMembershipJobPlan.scopes(expected);
        if(scopes.size()!=1) throw new IllegalArgumentException("One completed industry required");
        var scope=scopes.getFirst();var ledger=SyncRunLedger.openReadOnly(path);var run=ledger.getRun(runId);var root=ledger.get(runId);
        if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(root.state())
                || !run.jobId().equals("data.index_member") || run.jobVersion()!=1
                || !SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId()).equals(SyncRequestIdentity.fingerprint(expected,run.targetId())))
            throw new IllegalStateException("Completed membership run differs from frozen child request");
        var json=JobDefinitionJson.mapper();Path folder=path.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId);
        var frozen=json.readTree(IndexMembershipSourceEvidence.bounded(folder.resolve("prepared.json"),96*1024*1024));
        if(!frozen.path("runId").asText().equals(runId) || !frozen.path("targetId").asText().equals(run.targetId())
                || !frozen.path("request").asText().equals(run.frozenJson()) || !frozen.path("scope").equals(json.valueToTree(scope)))
            throw new IllegalStateException("Completed membership preparation changed");
        SyncJobRunner.Page<IndexMembership> source=json.convertValue(frozen.path("source"),new com.fasterxml.jackson.core.type.TypeReference<>() {});
        IndexMembershipSourceEvidence.verify(source,scope,folder);
        var prepared=json.treeToValue(frozen.path("prepared"),IndexMembershipState.Prepared.class);
        if(!prepared.equals(backend.prepare(prepared.before(),source.rows(),scope.l2Code()))
                || !run.targetId().equals(backend.identify(table,prepared.before().identity().id(),prepared.before().identity().directory())))
            throw new IllegalStateException("Completed membership target or merge differs");
        Path receipt=folder.resolve("completion.json");var proof=json.readTree(IndexMembershipSourceEvidence.bounded(receipt,96*1024*1024));
        if(!proof.path("runId").asText().equals(runId)) throw new IllegalStateException("Completed membership run ID changed");
        if(!proof.path("scope").equals(json.valueToTree(scope))) throw new IllegalStateException("Completed membership scope changed");
        if(!proof.path("source").equals(json.valueToTree(source))) throw new IllegalStateException("Completed membership source changed");
        if(!prepared.before().equals(json.treeToValue(proof.path("before"),IndexMembershipState.Snapshot.class)))
            throw new IllegalStateException("Completed membership before snapshot changed");
        if(!proof.path("actual").path("rows").equals(json.valueToTree(prepared.rows()))) throw new IllegalStateException("Completed membership actual rows changed");
        if(!proof.path("merge").equals(json.valueToTree(prepared.merge()))) throw new IllegalStateException("Completed membership merge changed");
        var payload=json.readTree(root.payloadJson());
        if(source.rows().isEmpty()) {
            if(root.state()!=SyncRunState.VERIFIED_EMPTY || !payload.path("sourceComplete").asBoolean()
                    || !payload.path("responseEvidence").asText().equals(source.responseEvidence()))
                throw new IllegalStateException("Empty membership ledger proof differs");
        } else if(root.state()!=SyncRunState.VERIFIED || !payload.path("verification").path("sourceFingerprint").asText().equals(source.sourceFingerprint()))
            throw new IllegalStateException("Membership ledger source fingerprint differs");
        for(String id:List.of(runId+"-attempt",runId+"-"+scope.l2Code())) {
            var child=ledger.get(id);
            if(!child.runId().equals(runId) || child.state()!=root.state()) throw new IllegalStateException("Completed membership children differ");
        }
        if(new DatasetIntervalLock(path).findOwned(runId,DatasetIntervalLock.Scope.allDates("index_member"))!=null)
            throw new IllegalStateException("Completed membership lock needs reconciliation");
        var actual=backend.open(table).snapshot();
        var expectedScope=prepared.rows().stream().filter(r->r.indexCode().equals(scope.l2Code())).toList();
        var actualScope=actual.rows().stream().filter(r->r.indexCode().equals(scope.l2Code())).toList();
        if(!expectedScope.equals(actualScope)) throw new IllegalStateException("Completed industry actual key/value scope changed");
        return new Proof(runId,root.state(),source.rows().size(),receipt.toString(),IndexMembershipSourceEvidence.hash(json.writeValueAsBytes(actualScope)));
    }
}
