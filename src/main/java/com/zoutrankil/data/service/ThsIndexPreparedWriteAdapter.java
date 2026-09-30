package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.ThsIndexMapper;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** A prepared THS member enters the same owner and physical publication path as a source run. */
public final class ThsIndexPreparedWriteAdapter implements WriteGroupMemberAdapter {
    private final WriteGroupPlan.Member member;
    private final FrozenRequest request;
    private final ThsIndexJobService owner;
    private final Path evidenceRoot;
    private final boolean resumed;
    private final ThsIndexMapper mapper=new ThsIndexMapper();

    public ThsIndexPreparedWriteAdapter(WriteGroupPlan plan,String memberId,
                                            ThsIndexJobService owner,Path evidenceRoot,boolean resumed) {
        this.member=plan.members().stream().filter(m->m.memberId().equals(memberId)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Unknown THS member"));
        if(!member.definition().equals(ThsIndexDataset.DEFINITION))
            throw new IllegalArgumentException("THS definition required");
        this.owner=Objects.requireNonNull(owner);this.evidenceRoot=Objects.requireNonNull(evidenceRoot);
        this.resumed=resumed;
        var parameter=new Parameter(ParameterType.STRING,true,128,1,Set.of());
        var definition=new SyncJobDefinition("write.ths_index",1,member.definition().datasetId(),
                member.definition().schemaVersion(),member.definition().owner(),Set.of(Mode.INGEST),Mode.INGEST,
                Map.of("groupBatch",parameter,"memberBatch",parameter,"planFingerprint",parameter,
                        "payloadFingerprint",parameter),"prepared.local","prepared.static",
                "questdb.full_key_values",new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(30)),
                Duration.ofMinutes(20),new Budget(1,1,1,Math.max(1,member.batch().rows().size()),8*1024*1024),
                0,List.of(),Frequency.MANUAL,ZoneOffset.UTC,true,false);
        request=definition.freeze(null,Map.of("groupBatch",plan.batchId(),"memberBatch",member.batchId(),
                "planFingerprint",plan.fingerprint(),"payloadFingerprint",member.batch().fingerprint()),
                null,null,plan.logicalDate());
    }
    @Override public WriteGroupPlan.Member member() { return member; }
    @Override public FrozenRequest request() { return request; }
    private List<ThsIndex> materialize() {
        var typed=member.batch().rows().stream().map(mapper::fromValues).toList();
        var roundTrip=DatasetWritePreparation.prepareWalReplace(member.definition(),typed,mapper::values,
                new DatasetWritePreparation.Limits(5000,8*1024*1024));
        if(!roundTrip.fingerprint().equals(member.batch().fingerprint()))
            throw new IllegalArgumentException("THS mapper changes frozen row values");
        return typed;
    }
    @Override public void preflight(FrozenRequest actual) {
        if(!SyncRequestIdentity.fingerprint(request,member.targetId())
                .equals(SyncRequestIdentity.fingerprint(actual,member.targetId())))
            throw new IllegalArgumentException("THS write request differs from frozen member");
        materialize();
        if(!resumed && !owner.targetId().equals(member.targetId()))
            throw new IllegalStateException("THS target identity changed");
    }
    @Override public SyncJobRunner.Result execute(SyncRunLedger ledger,DatasetIntervalLock locks,
            String child,String parent,String prior,String target,FrozenRequest actual,
            BooleanSupplier cancelled) throws Exception {
        if(prior!=null) {
            var entry=ledger.get(prior);
            if(!Set.of(SyncRunState.FAILED,SyncRunState.CANCELLED).contains(entry.state())
                    || locks.findOwned(prior,DatasetIntervalLock.Scope.allDates("ths_index"))!=null)
                throw new IllegalStateException("Uncertain THS member needs explicit reconciliation");
        }
        preflight(actual);
        if(!target.equals(member.targetId()) || !owner.targetId().equals(member.targetId())
                || cancelled.getAsBoolean())
            throw new java.util.concurrent.CancellationException("THS write cancelled or changed target");
        var typed=materialize();Path folder=evidenceRoot.resolve(child);Files.createDirectories(folder);
        Path receipt=folder.resolve(member.memberId()+"-prepared-input.json");
        Files.writeString(receipt,JobDefinitionJson.mapper().writeValueAsString(Map.of(
                "sourceKind","prepared-write-request","memberId",member.memberId(),
                "batchId",member.batchId(),"targetId",member.targetId(),
                "fingerprint",member.batch().fingerprint(),"rows",member.batch().rows())),
                StandardOpenOption.CREATE_NEW);
        var result=owner.executePrepared(child,parent,actual,typed,receipt.toString());
        return new SyncJobRunner.Result(result.runId(),result.state(),result.sourceRows(),
                result.verifiedRows(),result.errorCode());
    }
    @Override public String revalidate(SyncRunLedger ledger,String prior,String target,FrozenRequest actual,
            BooleanSupplier cancelled,Path evidence) throws Exception {
        if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        return owner.revalidateGroupChild(prior,target,actual);
    }
}
