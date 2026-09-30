package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.security.MessageDigest;
import java.util.*;

/** Verified ledger slices are checkpoints; old offset cursors never skip fresh source reads. */
public final class VerifiedSliceRecovery {
    public record Checkpoint(String runId,String sliceId,String sourceFingerprint,int rows) {}
    public record Readback(Checkpoint checkpoint,String valueDigest) {}
    public record CompleteRevalidation(int pages,int sourceRows,int matchedRows,
                                       String sourceCompletionEvidence,List<Readback> readbacks) {
        public CompleteRevalidation { readbacks=List.copyOf(readbacks); }
    }
    private final Map<String,Checkpoint> byFingerprint;
    private VerifiedSliceRecovery(Map<String,Checkpoint> checkpoints) { byFingerprint=Map.copyOf(checkpoints); }
    public static VerifiedSliceRecovery none() { return new VerifiedSliceRecovery(Map.of()); }
    public static VerifiedSliceRecovery load(SyncRunLedger ledger,String priorRun,
            SyncJobDefinition.FrozenRequest request,String targetId) throws Exception {
        var run=ledger.getRun(priorRun);
        if(!ledger.get(priorRun).state().terminal())
            throw new IllegalStateException("Previous run active or in doubt; reconciliation required");
        if(!run.targetId().equals(targetId) || !SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId())
                .equals(SyncRequestIdentity.fingerprint(request,targetId)))
            throw new IllegalArgumentException("Recovery definition, parameters, logical date or target changed");
        var json=new ObjectMapper(); var checkpoints=new HashMap<String,Checkpoint>();
        String cursor=null; long seen=0;
        while(true) {
            var page=ledger.entries(priorRun,cursor,1000);
            if(page.isEmpty()) break;
            for(var entry:page) {
                if(++seen > (long)request.definition().budget().maxSlices()+2)
                    throw new IllegalArgumentException("Previous run exceeds frozen recovery budget");
                if(!entry.state().terminal()) throw new IllegalStateException("Previous child unresolved");
                if(entry.kind()!=SyncRunLedger.Kind.SLICE || entry.state()!=SyncRunState.VERIFIED) continue;
                var proof=json.readTree(entry.payloadJson()).path("verification");
                String fingerprint=proof.path("sourceFingerprint").asText("");
                int rows=proof.path("expectedRows").asInt(-1);
                if(fingerprint.isBlank() || rows<1 || rows>10000 || !proof.path("passed").asBoolean(false))
                    throw new IllegalStateException("Incomplete verified checkpoint evidence");
                if(checkpoints.putIfAbsent(fingerprint,new Checkpoint(priorRun,entry.id(),fingerprint,rows))!=null)
                    throw new IllegalStateException("Ambiguous duplicate source checkpoint");
            }
            cursor=page.getLast().id();
        }
        return new VerifiedSliceRecovery(checkpoints);
    }
    /** Re-fetched source and current target values must both match before skipping a write. */
    public <T,K> Readback revalidate(SyncJobRunner.Page<T> page,VerifiedBatchExecutor.Codec<T,K> codec,
                                    VerifiedBatchExecutor.Port<T,K> port) throws Exception {
        var checkpoint=byFingerprint.get(page.sourceFingerprint());
        if(checkpoint==null) return null;
        if(page.rows().size()!=checkpoint.rows()) throw new IllegalStateException("Checkpoint source row count changed");
        var expected=new LinkedHashMap<K,byte[]>();
        for(T row:page.rows()) {
            var key=Objects.requireNonNull(codec.key(row));
            if(expected.putIfAbsent(key,codec.canonicalBytes(row))!=null)
                throw new IllegalStateException("Duplicate source checkpoint key");
        }
        port.preflight();
        var actual=port.readback(List.copyOf(expected.keySet()));
        if(actual==null || actual.size()!=expected.size()) throw new IllegalStateException("Checkpoint target rows changed");
        var seen=new HashSet<K>();
        for(T row:actual) {
            K key=codec.key(row);
            if(!seen.add(key) || !expected.containsKey(key) || !Arrays.equals(expected.get(key),codec.canonicalBytes(row)))
                throw new IllegalStateException("Checkpoint target values changed; reconcile before writing");
        }
        if(!port.walSettled()) throw new IllegalStateException("Checkpoint target WAL not settled");
        var hash=MessageDigest.getInstance("SHA-256");
        for(byte[] value:expected.values()) {
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array()); hash.update(value);
        }
        return new Readback(checkpoint,HexFormat.of().formatHex(hash.digest()));
    }

    /** Re-fetch every bounded page and verify current target values before reusing a child. */
    public static <T,K> CompleteRevalidation verifyWhole(SyncRunLedger ledger,String priorRun,
            SyncJobDefinition.FrozenRequest request,String targetId,SyncJobRunner.Adapter<T,K> adapter,
            java.util.function.BooleanSupplier cancelled) throws Exception {
        var oldState=ledger.get(priorRun).state();
        if(oldState!=SyncRunState.VERIFIED && oldState!=SyncRunState.VERIFIED_EMPTY)
            throw new IllegalStateException("Only completed child runs can be reused");
        var checkpoints=load(ledger,priorRun,request,targetId);
        adapter.preflight(request);
        int[] counts={0,0,0};
        var seenKeys=new HashSet<K>();
        var seenCheckpoints=new HashSet<String>();
        var proofs=new ArrayList<Readback>();
        var completion=adapter.fetch(request,page->{
            if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Group child revalidation cancelled");
            var budget=request.definition().budget();
            if(counts[0]>=Math.min(budget.maxPages(),budget.maxSlices())
                    || (long)counts[1]+page.rows().size()>budget.maxRows())
                throw new IllegalArgumentException("Recovery source exceeds frozen budget");
            counts[0]++; counts[1]+=page.rows().size();
            for(T row:page.rows()) {
                var key=Objects.requireNonNull(adapter.codec().key(row));
                if(!seenKeys.add(key)) throw new IllegalArgumentException("Duplicate key in refreshed source");
            }
            if(!page.rows().isEmpty()) {
                var proof=checkpoints.revalidate(page,adapter.codec(),adapter.port());
                if(proof==null || !seenCheckpoints.add(proof.checkpoint().sliceId()))
                    throw new IllegalStateException("Completed child source changed or repeated");
                proofs.add(proof); counts[2]+=page.rows().size();
            }
        },cancelled);
        if(completion==null || !completion.complete() || completion.pages()!=counts[0]
                || completion.rows()!=counts[1] || completion.evidence()==null
                || completion.evidence().isBlank() || seenCheckpoints.size()!=checkpoints.byFingerprint.size()
                || counts[2]!=counts[1] || oldState==SyncRunState.VERIFIED && counts[2]<1
                || oldState==SyncRunState.VERIFIED_EMPTY && counts[1]!=0)
            throw new IllegalStateException("Completed child source or checkpoint coverage changed");
        return new CompleteRevalidation(counts[0],counts[1],counts[2],completion.evidence(),proofs);
    }
}
