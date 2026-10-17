package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.domain.StockSuspendState;
import com.zoutrankil.data.stock.port.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Source/readback only: no staging, append intent, or publication. */
final class StockSuspendRevalidationAdapter implements SyncJobRunner.Adapter<StockSuspend,StockSuspendKey> {
    private final StockSuspendSource source;
    private final StockSuspendTarget target;
    private final StockSuspendWriteSession reader;
    private final String physical;
    private final Path evidence;
    StockSuspendRevalidationAdapter(TusharePageService pages,StockSuspendTarget target,Path evidence) {
        this.target=target;this.evidence=evidence;
        source=new StockSuspendSource(pages,evidence.resolve("source"));
        physical=target.physicalTargetId();reader=target.newWriter(physical);
    }
    private void requireIdentity() {
        if(!physical.equals(target.physicalTargetId()))throw new IllegalStateException("Suspension generation changed during revalidation");
    }
    public void preflight(SyncJobDefinition.FrozenRequest request) {
        StockSuspendSyncAdapter.validateRequest(request);
        if(!target.targetId().equals(request.parameters().get("targetId")))throw new IllegalStateException("Suspension logical target changed");
        requireIdentity();reader.preflight();
    }
    public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<StockSuspend> consumer,BooleanSupplier cancelled)throws Exception {
        var rows=new ArrayList<StockSuspend>();var receipts=new ArrayList<String>();
        for(var date=request.from();!date.isAfter(request.to());date=date.plusDays(1)) {
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            requireIdentity();var result=source.fetch(new StockSuspendSource.Query(date),cancelled);
            rows.addAll(result.rows());
            if(rows.size()>request.definition().budget().maxRows())throw new IllegalStateException("Suspension source exceeds frozen budget");
            consumer.accept(new SyncJobRunner.Page<>(result.rows(),result.fingerprint(),result.receipt(),null));
            receipts.add(result.receipt());
        }
        var snapshot=target.openTable(target.tableName()).snapshot();
        if(!physical.equals(target.physicalTargetId(target.tableName(),snapshot.identity())))
            throw new IllegalStateException("Suspension readback generation changed");
        var actual=snapshot.rows().stream().filter(row->!row.tradeDate().isBefore(request.from())
                &&!row.tradeDate().isAfter(request.to())).toList();
        if(!StockSuspendState.sortedUnique(rows).equals(StockSuspendState.sortedUnique(actual)))
            throw new IllegalStateException("Suspension complete window differs from fresh source");
        requireIdentity();Files.createDirectories(evidence);var receipt=evidence.resolve("source-revalidated.json");
        FileEvidenceStore.writeNew(receipt,JobDefinitionJson.mapper().writeValueAsBytes(Map.of(
                "sourceSlices",receipts,"sourceRows",rows.size(),"physicalTarget",physical,"complete",true)));
        return new SyncJobRunner.SourceCompletion(receipts.size(),rows.size(),true,receipt.toString());
    }
    public VerifiedBatchExecutor.Codec<StockSuspend,StockSuspendKey> codec(){return reader.codec();}
    public VerifiedBatchExecutor.Port<StockSuspend,StockSuspendKey> port(){return reader;}
}
