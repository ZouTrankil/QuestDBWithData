package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.domain.stock.moneyflow.MoneyflowHsgtKey;
import com.zoutrankil.data.domain.stock.moneyflow.MoneyflowHsgt;
import com.zoutrankil.data.calendar.port.SseCalendarWindowReadPort;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtRows;
import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Fresh bounded source compared to the current formal generation, without staging or publication. */
final class MoneyflowHsgtRevalidationAdapter implements SyncJobRunner.Adapter<MoneyflowHsgt,MoneyflowHsgtKey> {
    private final MoneyflowHsgtSource source;
    private final MoneyflowHsgtTarget target;
    private final MoneyflowHsgtWriteSession reader;
    private final SseCalendarWindowReadPort calendars;
    private final Path evidence;
    private final String physical;
    MoneyflowHsgtRevalidationAdapter(MoneyflowHsgtSource source,MoneyflowHsgtTarget target,
            SseCalendarWindowReadPort calendars,Path evidence)throws Exception {
        this.source=source;this.target=target;this.calendars=calendars;this.evidence=evidence;
        physical=target.physicalTargetId();reader=target.newWriter(physical);
    }
    private void requireIdentity()throws Exception {
        if(!physical.equals(target.physicalTargetId()))throw new IllegalStateException("D027 generation changed during revalidation");
    }
    public void preflight(SyncJobDefinition.FrozenRequest request)throws Exception {
        MoneyflowHsgtSyncAdapter.validateRequest(request);
        if(!target.targetId().equals(request.parameters().get("targetId")))throw new IllegalStateException("D027 logical target changed");
        if("moneyflow_hsgt".equals(target.tableName())
                &&java.time.temporal.ChronoUnit.DAYS.between(request.from(),request.to())>=MoneyflowHsgtSource.MAX_RANGE_DAYS)
            throw new IllegalArgumentException("Formal D027 revalidation exceeds source window bound");
        requireIdentity();reader.preflight();
    }
    public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<MoneyflowHsgt> consumer,BooleanSupplier cancelled)throws Exception {
        var receipts=new ArrayList<String>();var seen=new HashSet<MoneyflowHsgtKey>();int count=0;
        for(var from=request.from();!from.isAfter(request.to());) {
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            requireIdentity();var to=from.plusDays(MoneyflowHsgtSource.MAX_RANGE_DAYS-1L);
            if(to.isAfter(request.to()))to=request.to();
            var page=source.fetch(from,to,cancelled);
            for(var row:page.rows())if(!seen.add(row.key()))throw new IllegalStateException("Duplicate D027 source key");
            if("moneyflow_hsgt".equals(target.tableName()))
                MoneyflowHsgtSyncAdapter.requireFormalCalendarCoverage(calendars,from,to,page.rows());
            consumer.accept(page);
            if(!MoneyflowHsgtRows.sameRows(page.rows(),reader.readWindow(target.tableName(),from,to)))
                throw new IllegalStateException("D027 full window readback differs from fresh source");
            requireIdentity();receipts.add(page.responseEvidence());count=Math.addExact(count,page.rows().size());from=to.plusDays(1);
        }
        Files.createDirectories(evidence);var receipt=evidence.resolve("source-revalidated.json");
        FileEvidenceStore.writeNew(receipt,JobDefinitionJson.canonicalMapper().writeValueAsBytes(Map.of(
                "sourceSlices",receipts,"sourceRows",count,"physicalTarget",physical,"complete",true)));
        return new SyncJobRunner.SourceCompletion(receipts.size(),count,true,receipt.toString());
    }
    public VerifiedBatchExecutor.Codec<MoneyflowHsgt,MoneyflowHsgtKey> codec(){return reader.codec();}
    public VerifiedBatchExecutor.Port<MoneyflowHsgt,MoneyflowHsgtKey> port(){return reader;}
}
