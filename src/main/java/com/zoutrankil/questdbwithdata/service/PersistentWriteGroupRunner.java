package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Prepared write groups share the authoritative sync parent/child ledger and interval locks. */
public final class PersistentWriteGroupRunner {
    private final Path ledgerPath;
    private final Path evidenceRoot;
    private final DatasetRegistry datasets;
    public PersistentWriteGroupRunner(Path ledgerPath, Path evidenceRoot, DatasetRegistry datasets) {
        this.ledgerPath = Objects.requireNonNull(ledgerPath);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot);
        this.datasets = Objects.requireNonNull(datasets);
    }
    public SyncGroupRunner.Result run(String runId, WriteGroupPlan plan,
            Map<String,? extends WriteGroupMemberAdapter> adapters, String priorGroupRunId) throws Exception {
        boolean staticMemberSeen=false;
        for(var member:plan.members()) {
            if(staticMemberSeen) throw new IllegalArgumentException("Static replacement must be the final write-group member");
            if(member.definition().capabilities().contains(DatasetDefinition.Capability.STATIC_REPLACE)
                    || member.definition().capabilities().contains(DatasetDefinition.Capability.WAL_REPLACE))
                staticMemberSeen=true;
        }
        var ids = new HashSet<String>(); plan.members().forEach(m -> ids.add(m.memberId()));
        if (!ids.equals(adapters.keySet())) throw new IllegalArgumentException("Exact prepared writer members required");
        var definitions = new ArrayList<SyncJobDefinition>();
        var modes = new LinkedHashMap<String,Set<SyncJobDefinition.Mode>>();
        var groupMembers = new ArrayList<SyncGroupDefinition.Member>();
        var inputs = new LinkedHashMap<String,SyncGroupRunner.MemberInput>();
        var byJob = new LinkedHashMap<String,WriteGroupMemberAdapter>();
        for (var member : plan.members()) {
            var adapter = Objects.requireNonNull(adapters.get(member.memberId()));
            var bound = adapter.member();
            if (!bound.definition().equals(member.definition()) || !bound.memberId().equals(member.memberId())
                    || !bound.batchId().equals(member.batchId()) || !bound.targetId().equals(member.targetId())
                    || !bound.batch().fingerprint().equals(member.batch().fingerprint())
                    || !plan.fingerprint().equals(adapter.request().parameters().get("planFingerprint"))
                    || !plan.logicalDate().equals(adapter.request().logicalDate()))
                throw new IllegalArgumentException("Adapter differs from frozen write group");
            var job = adapter.request().definition();
            definitions.add(job); modes.put(job.datasetId(), job.supportedModes());
            groupMembers.add(new SyncGroupDefinition.Member(new SyncJobDefinition.JobRef(job.jobId(), job.version()), List.of()));
            inputs.put(job.jobId(), new SyncGroupRunner.MemberInput(adapter.request().mode(),
                    adapter.request().parameters(), SyncGroupRunner.Window.none(), member.targetId()));
            if (byJob.putIfAbsent(job.jobId(), adapter) != null) throw new IllegalArgumentException("Duplicate write job");
        }
        // Reject known target, schema, mapping and WAL problems before any member sends.
        for (var adapter : adapters.values()) adapter.preflight(adapter.request());
        var jobs = new SyncJobRegistry(definitions, datasets, modes, new SyncJobRegistry.Policies(
                Set.of("prepared.local"), Set.of("prepared.single_page","prepared.static"),
                Set.of("questdb.full_key_values")));
        var definition = new SyncGroupDefinition("group.prepared_writes", 1, groupMembers, true, false);
        var groups = new SyncGroupRegistry(List.of(definition), jobs);
        var ledger = new SyncRunLedger(ledgerPath);
        var locks = new DatasetIntervalLock(ledgerPath);
        var runner = new SyncGroupRunner(groups, jobs, ledger);
        BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read group cancellation", failure); }
        };
        var executor = new SyncGroupRunner.ChildExecutor() {
            public SyncJobRunner.Result execute(String child, String parent, String prior, String target,
                    SyncJobDefinition.FrozenRequest request) throws Exception {
                return byJob.get(request.definition().jobId()).execute(ledger,locks,child,parent,prior,
                        target,request,cancelled);
            }
            public String revalidateCompleted(String prior, String target,
                    SyncJobDefinition.FrozenRequest request) throws Exception {
                return byJob.get(request.definition().jobId()).revalidate(ledger,prior,target,request,
                        cancelled,evidenceRoot.resolve(runId));
            }
        };
        var request = new SyncGroupRunner.Request(plan.logicalDate(), SyncGroupRunner.Window.none(), inputs);
        return priorGroupRunId == null ? runner.run(runId, definition.groupId(), definition.version(), request, executor)
                : runner.resume(runId, priorGroupRunId, definition.groupId(), definition.version(), request, executor);
    }
}
