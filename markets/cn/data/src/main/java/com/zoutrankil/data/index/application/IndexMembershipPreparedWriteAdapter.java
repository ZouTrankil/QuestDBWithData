package com.zoutrankil.data.index.application;



import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.mapper.IndexMembershipMapper;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** A local full-field batch has explicit provenance separate from Tushare membership sync. */
public final class IndexMembershipPreparedWriteAdapter implements WriteGroupMemberAdapter {
    private final WriteGroupPlan.Member member;
    private final FrozenRequest request;
    private final IndexMembershipJobService owner;
    private final Path evidenceRoot;
    private final boolean resumed;
    private final IndexMembershipMapper mapper=new IndexMembershipMapper();
    public IndexMembershipPreparedWriteAdapter(WriteGroupPlan plan,String memberId,
            IndexMembershipJobService owner,Path evidenceRoot,boolean resumed) {
        member=plan.members().stream().filter(m->m.memberId().equals(memberId)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Unknown membership member"));
        if(!member.definition().equals(IndexMembershipDataset.DEFINITION))
            throw new IllegalArgumentException("Membership definition required");
        this.owner=Objects.requireNonNull(owner);this.evidenceRoot=Objects.requireNonNull(evidenceRoot);this.resumed=resumed;
        var parameter=new Parameter(ParameterType.STRING,true,128,1,Set.of());
        var definition=new SyncJobDefinition("write.index_member",1,member.definition().datasetId(),
                member.definition().schemaVersion(),member.definition().owner(),Set.of(Mode.INGEST),Mode.INGEST,
                Map.of("groupBatch",parameter,"memberBatch",parameter,"planFingerprint",parameter,
                        "payloadFingerprint",parameter),"prepared.local","prepared.membership.l2",
                "questdb.full_key_values",new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(30)),
                Duration.ofMinutes(20),new Budget(1,1,1,Math.max(1,member.batch().rows().size()),8*1024*1024),
                0,List.of(),Frequency.MANUAL,ZoneOffset.UTC,true,false);
        request=definition.freeze(null,Map.of("groupBatch",plan.batchId(),"memberBatch",member.batchId(),
                "planFingerprint",plan.fingerprint(),"payloadFingerprint",member.batch().fingerprint()),
                null,null,plan.logicalDate());
    }
    @Override public WriteGroupPlan.Member member() { return member; }
    @Override public FrozenRequest request() { return request; }
    private List<IndexMembership> materialize() {
        var typed=member.batch().rows().stream().map(mapper::fromValues).toList();
        var roundTrip=DatasetWritePreparation.prepareWalReplace(member.definition(),typed,mapper::values,
                new DatasetWritePreparation.Limits(3999,8*1024*1024));
        if(!roundTrip.fingerprint().equals(member.batch().fingerprint()))
            throw new IllegalArgumentException("Membership mapper changes frozen rows");
        String code=typed.getFirst().indexCode();
        if(typed.stream().anyMatch(row->!row.indexCode().equals(code) || !row.level().equals("L2")))
            throw new IllegalArgumentException("One L2 industry per prepared membership write");
        return typed;
    }
    @Override public void preflight(FrozenRequest actual) {
        if(!SyncRequestIdentity.fingerprint(request,member.targetId())
                .equals(SyncRequestIdentity.fingerprint(actual,member.targetId())))
            throw new IllegalArgumentException("Membership write request differs from frozen member");
        materialize();
        if(!resumed && !owner.targetId().equals(member.targetId()))
            throw new IllegalStateException("Membership target identity changed");
    }
    @Override public SyncJobRunner.Result execute(SyncRunLedger ledger,DatasetIntervalLock locks,
            String child,String parent,String prior,String target,FrozenRequest actual,
            BooleanSupplier cancelled) throws Exception {
        if(prior!=null) {
            var entry=ledger.get(prior);
            if(!Set.of(SyncRunState.FAILED,SyncRunState.CANCELLED).contains(entry.state())
                    || locks.findOwned(prior,DatasetIntervalLock.Scope.allDates("index_member"))!=null)
                throw new IllegalStateException("Uncertain prepared membership child needs reconciliation");
        }
        preflight(actual);
        if(!target.equals(member.targetId()) || !owner.targetId().equals(member.targetId())
                || cancelled.getAsBoolean())
            throw new java.util.concurrent.CancellationException("Membership write cancelled or changed target");
        var typed=materialize();Path folder=evidenceRoot.resolve(child);Files.createDirectories(folder);
        Path receipt=folder.resolve(member.memberId()+"-prepared-input.json");
        FileEvidenceStore.writeNewUtf8(receipt,JobDefinitionJson.mapper().writeValueAsString(Map.of(
                "sourceKind","prepared-write-request","memberId",member.memberId(),
                "batchId",member.batchId(),"targetId",member.targetId(),
                "fingerprint",member.batch().fingerprint(),"rows",member.batch().rows())));
        var result=owner.executePrepared(child,parent,actual,typed,receipt);
        return new SyncJobRunner.Result(result.runId(),result.state(),result.supplied(),result.verified(),result.errorCode());
    }
    @Override public String revalidate(SyncRunLedger ledger,String prior,String target,FrozenRequest actual,
            BooleanSupplier cancelled,Path evidence) throws Exception {
        if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        return owner.revalidatePreparedChild(prior,target,actual);
    }
}
