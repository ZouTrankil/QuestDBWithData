package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import java.security.MessageDigest;
import java.util.*;

/** Verified ledger slices are checkpoints; old offset cursors never skip fresh source reads. */
public final class VerifiedSliceRecovery {
    public record Checkpoint(String runId,String sliceId,String sourceFingerprint,int rows) {}
    public record Readback(Checkpoint checkpoint,String valueDigest) {}
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
}
