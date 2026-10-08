package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Daily full-market slices or one bounded exact-code date-range query, with no unbounded dual scan. */
public final class StockFactorSyncAdapter implements SyncJobRunner.Adapter<StockFactor,StockFactorKey> {
    private final StockFactorSource source;
    private final VerifiedWriteSession<StockFactor, StockFactorKey> port;
    private final Path evidenceRoot;
    private final ObjectMapper json=JobDefinitionJson.mapper();
    public StockFactorSyncAdapter(TusharePageService pages,VerifiedWriteSession<StockFactor, StockFactorKey> port,Path evidenceRoot) {
        this.source=new StockFactorSource(pages,evidenceRoot.resolve("source"));
        this.port=Objects.requireNonNull(port);this.evidenceRoot=evidenceRoot.toAbsolutePath().normalize();
    }
    public static void validateRequest(FrozenRequest request) {
        if(request==null || !request.definition().equals(StockFactorSyncJobOwner.DEFINITION)
                || !request.definition().datasetId().equals(StockFactorDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion()!=StockFactorDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL,Mode.BACKFILL).contains(request.mode())
                || request.from()==null || request.to()==null
                || request.parameters().keySet().stream().anyMatch(k->!Set.of("targetId","tsCode","checkpointAnchor","checkpointBefore",
                        "targetMinBefore","targetMaxBefore").contains(k)))
            throw new IllegalArgumentException("Frozen bounded stk_factor request required");
        Object target=request.parameters().get("targetId");
        if(!(target instanceof String text) || !text.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen stk_factor physical target identity required");
        long days=java.time.temporal.ChronoUnit.DAYS.between(request.from(),request.to())+1;
        if(days<1 || days>request.definition().budget().maxWindowDays())
            throw new IllegalArgumentException("stk_factor window exceeds the five-calendar-day request budget");
        Object code=request.parameters().get("tsCode");
        if(code!=null && (!(code instanceof String codeText) || !codeText.matches("[0-9]{6}\\.(?:SZ|SH|BJ)")))
            throw new IllegalArgumentException("Exact Tushare A-share code required");
        Object anchor=request.parameters().get("checkpointAnchor");
        if(request.mode()==Mode.INCREMENTAL && !(anchor instanceof LocalDate))
            throw new IllegalArgumentException("Incremental stk_factor requests require their frozen checkpoint anchor");
        for(String dateParameter:List.of("checkpointBefore","targetMinBefore","targetMaxBefore")) {
            Object value=request.parameters().get(dateParameter);
            if(value!=null && !(value instanceof LocalDate))
                throw new IllegalArgumentException("Frozen stk_factor planning date required: "+dateParameter);
        }
        if(request.mode()==Mode.BACKFILL && (anchor!=null || request.parameters().containsKey("checkpointBefore")))
            throw new IllegalArgumentException("Backfill does not use incremental checkpoint metadata");
    }
    @Override public void preflight(FrozenRequest request) {
        validateRequest(request);
        port.preflight();
    }
    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<StockFactor> consumer,BooleanSupplier cancelled) throws Exception {
        var pages=new ArrayList<String>();int[] totals={0,0};
        String code=(String)request.parameters().get("tsCode");
        if(code!=null) {
            accept(new StockFactorSource.Query(request.from(),request.to(),code),consumer,cancelled,pages,totals);
        } else {
            for(LocalDate date=request.from();!date.isAfter(request.to());date=date.plusDays(1)) {
                if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException("stk_factor date slice cancelled");
                accept(new StockFactorSource.Query(date,date,null),consumer,cancelled,pages,totals);
            }
        }
        Path complete=evidenceRoot.resolve("complete-"+UUID.randomUUID()+".json");
        var receipt=Map.of("endpoint",StockFactorSource.SOURCE_ENDPOINT,
                "sourceContractVersion",StockFactorSource.SOURCE_CONTRACT_VERSION,"mode",request.mode().name(),
                "fromInclusive",request.from().toString(),"toInclusive",request.to().toString(),
                "tsCode",code==null?"all-market":code,"completedSlices",totals[0],"sourceRows",totals[1],
                "sliceReceipts",pages,"complete",true);
        byte[] body=json.writeValueAsBytes(receipt);
        if(body.length>StockFactorSource.MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("stk_factor completion evidence exceeds byte budget");
        Files.createDirectories(evidenceRoot);FileEvidenceStore.writeNew(complete,body);
        return new SyncJobRunner.SourceCompletion(totals[0],totals[1],true,complete.toString());
    }
    private void accept(StockFactorSource.Query query,SyncJobRunner.PageConsumer<StockFactor> consumer,
            BooleanSupplier cancelled,List<String> receipts,int[] totals) throws Exception {
        if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("stk_factor source cancelled");
        var result=source.fetch(query,cancelled);
        consumer.accept(new SyncJobRunner.Page<>(result.rows(),result.fingerprint(),result.receipt(),null));
        receipts.add(result.receipt());totals[0]++;totals[1]=Math.addExact(totals[1],result.rows().size());
    }
    @Override public VerifiedBatchExecutor.Codec<StockFactor,StockFactorKey> codec() { return port.codec(); }
    @Override public VerifiedBatchExecutor.Port<StockFactor,StockFactorKey> port() { return port; }
}
