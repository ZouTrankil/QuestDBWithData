package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.mapper.StockDetailInfoMapper;
import com.zoutrankil.data.repository.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Prepared full rows enter the static owner through the same ledger and publication protocol as source sync. */
public final class StaticStockDetailWriteAdapter implements WriteGroupMemberAdapter {
    private final WriteGroupPlan.Member member;
    private final FrozenRequest request;
    private final StockDetailInfoJobService owner;
    private final Path evidenceRoot;
    private final StockDetailInfoMapper mapper=new StockDetailInfoMapper();

    public StaticStockDetailWriteAdapter(WriteGroupPlan plan,String memberId,
                                         StockDetailInfoJobService owner,Path evidenceRoot) {
        this.member=plan.members().stream().filter(m->m.memberId().equals(memberId)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Unknown prepared member"));
        if(!member.definition().equals(StockDetailInfoDataset.DEFINITION))
            throw new IllegalArgumentException("Static stock-detail definition required");
        this.owner=Objects.requireNonNull(owner);this.evidenceRoot=Objects.requireNonNull(evidenceRoot);
        var parameter=new Parameter(ParameterType.STRING,true,128,1,Set.of());
        var definition=new SyncJobDefinition("write.stock_detail_info",1,member.definition().datasetId(),
                member.definition().schemaVersion(),member.definition().owner(),Set.of(Mode.INGEST),Mode.INGEST,
                Map.of("groupBatch",parameter,"memberBatch",parameter,"planFingerprint",parameter,
                        "payloadFingerprint",parameter),"prepared.local","prepared.static",
                "questdb.full_key_values",new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(30)),
                Duration.ofMinutes(20),new Budget(1,1,1,Math.max(1,member.batch().rows().size()),16*1024*1024),
                0,List.of(),Frequency.MANUAL,ZoneOffset.UTC,true,false);
        request=definition.freeze(null,Map.of("groupBatch",plan.batchId(),"memberBatch",member.batchId(),
                "planFingerprint",plan.fingerprint(),"payloadFingerprint",member.batch().fingerprint()),
                null,null,plan.logicalDate());
    }
    @Override public WriteGroupPlan.Member member() { return member; }
    @Override public FrozenRequest request() { return request; }
    private List<StockDetailInfo> materialize() {
        var typed=member.batch().rows().stream().map(mapper::fromValues).toList();
        var roundTrip=DatasetWritePreparation.prepareStatic(member.definition(),typed,mapper::values,
                new DatasetWritePreparation.Limits(10000,16*1024*1024));
        if(!roundTrip.fingerprint().equals(member.batch().fingerprint()))
            throw new IllegalArgumentException("Static mapper changes frozen row values");
        return typed;
    }
    @Override public void preflight(FrozenRequest actual) {
        if(!SyncRequestIdentity.fingerprint(request,member.targetId())
                .equals(SyncRequestIdentity.fingerprint(actual,member.targetId())))
            throw new IllegalArgumentException("Static write request differs from frozen member");
        materialize();
        if(!owner.targetId().equals(member.targetId()))
            throw new IllegalStateException("Static write target identity changed");
    }
    @Override public SyncJobRunner.Result execute(SyncRunLedger ledger,DatasetIntervalLock locks,
            String child,String parent,String prior,String target,FrozenRequest actual,
            BooleanSupplier cancelled) throws Exception {
        if(prior!=null) {
            var entry=ledger.get(prior);var previous=entry.state();
            if(previous!=SyncRunState.FAILED && previous!=SyncRunState.CANCELLED)
                throw new IllegalStateException("Uncertain or completed static member needs explicit reconciliation");
            var priorPayload=JobDefinitionJson.mapper().readTree(entry.payloadJson());
            if(priorPayload.hasNonNull("publicationId"))
                throw new IllegalStateException("Prior static member has publication evidence");
            if(locks.findOwned(prior,DatasetIntervalLock.Scope.allDates(member.definition().datasetId()))!=null)
                throw new IllegalStateException("Prior static member still owns dataset lease");
        }
        preflight(actual);
        if(!target.equals(member.targetId()) || cancelled.getAsBoolean())
            throw new java.util.concurrent.CancellationException("Static prepared write cancelled or changed target");
        var typed=materialize();
        Path folder=evidenceRoot.resolve(child);Files.createDirectories(folder);
        Path receipt=folder.resolve(member.memberId()+"-prepared-input.json");
        FileEvidenceStore.writeNewUtf8(receipt,JobDefinitionJson.mapper().writeValueAsString(Map.of(
                "sourceKind","prepared-write-request","memberId",member.memberId(),
                "batchId",member.batchId(),"targetId",member.targetId(),
                "fingerprint",member.batch().fingerprint(),"rows",member.batch().rows())));
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
