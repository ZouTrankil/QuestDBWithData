package com.zoutrankil.data.stock.application;
import com.zoutrankil.data.stock.domain.StockDetailState;
import com.zoutrankil.data.stock.domain.policy.StockDetailInfoMerge;

import com.zoutrankil.data.stock.storage.StockDetailPublicationJournal;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.stock.port.StockDetailTarget;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Recover the run ledger after successful publication but incomplete control acknowledgement. No source or data writes. */
public final class StockDetailInfoRunRecovery {
    private StockDetailInfoRunRecovery() {}
    public static StockDetailInfoJobService.Result finishInterrupted(StockDetailTarget database,Path ledgerPath,String table,
                                                                     String runId,boolean writerStopped) throws Exception {
        if(!writerStopped) throw new IllegalStateException("Stopped writer proof required");
        ledgerPath=ledgerPath.toAbsolutePath().normalize();DatasetDefinition.identifier(table);
        var ledger=new SyncRunLedger(ledgerPath);var run=ledger.getRun(runId);
        if(!Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT).contains(ledger.get(runId).state())
                || !Set.of("data.stock_detail_info","write.stock_detail_info").contains(run.jobId()) || run.jobVersion()!=1)
            throw new IllegalStateException("Expected interrupted static owner run");
        var lease=new DatasetIntervalLock(ledgerPath).findOwned(runId,DatasetIntervalLock.Scope.allDates("stock_detail_info"));
        if(lease==null) throw new IllegalStateException("Retained dataset lease required");
        var receipt=ledgerPath.getParent().resolve("sync-evidence").resolve(runId).resolve("completion.json");
        StockDetailRecoveryEvidence.finish(database,ledgerPath,table,run,receipt,lease);
        return reconcilePublished(database,ledgerPath,table,runId,true);
    }
    public static StockDetailInfoJobService.Result reconcilePublished(StockDetailTarget database,Path ledgerPath,String table,
                                                                      String runId,boolean writerStopped) throws Exception {
        if(!writerStopped) throw new IllegalStateException("Stopped writer proof required");
        ledgerPath=ledgerPath.toAbsolutePath().normalize();DatasetDefinition.identifier(table);
        var ledger=new SyncRunLedger(ledgerPath);var root=ledger.get(runId);var run=ledger.getRun(runId);
        if(!Set.of(SyncRunState.IN_DOUBT,SyncRunState.RUNNING).contains(root.state())
                || !Set.of("data.stock_detail_info","write.stock_detail_info").contains(run.jobId())
                || run.jobVersion()!=1)
            throw new IllegalStateException("Expected uncertain stock-detail run");
        var locks=new DatasetIntervalLock(ledgerPath);var lease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates("stock_detail_info"));
        if(lease==null) throw new IllegalStateException("Retained dataset lease required");
        Path receipt=ledgerPath.getParent().resolve("sync-evidence").resolve(runId).resolve("completion.json");
        if(!Files.exists(receipt)) StockDetailRecoveryEvidence.reconstruct(database,ledgerPath,table,run,receipt);
        var json=JobDefinitionJson.mapper();var proof=json.readTree(receipt.toFile());
        if(!runId.equals(proof.path("runId").asText()) || !run.frozenJson().equals(proof.path("request").asText())
                || !run.targetId().equals(database.identify(table,
                        json.treeToValue(proof.path("beforeIdentity"),StockDetailState.Identity.class))))
            throw new IllegalStateException("Recovery evidence differs from frozen run/target");
        String publicationId=proof.path("publicationId").asText();var journal=new StockDetailPublicationJournal(ledgerPath);
        var publication=journal.get(publicationId);
        if(!publication.intent().runId().equals(runId) || !publication.intent().target().equals(table))
            throw new IllegalStateException("Publication ownership differs");
        var publisher=new StockDetailInfoPublication(database,ledgerPath);
        if(publisher.inspect(publicationId).layout()!=StockDetailInfoPublication.Layout.PUBLISHED)
            throw new IllegalStateException("Current target is not the exact published layout");
        var actual=database.open(table).snapshot();
        if(!json.valueToTree(actual.rows()).equals(proof.path("after").path("rows"))
                || !actual.fingerprint().equals(proof.path("after").path("fingerprint").asText()))
            throw new IllegalStateException("Actual recovery readback differs from completion receipt");
        int sourceRows=proof.path("sourceRows").size();if(sourceRows<1) throw new IllegalStateException("Nonempty publication expected");
        var byKey=new HashMap<String,StockDetailInfo>();
        for(var row:actual.businessRows()) byKey.put(row.key(),row);
        for(var source:proof.path("sourceRows")) {
            var row=json.treeToValue(source,StockDetailInfo.class);
            var found=byKey.get(row.key());
            if(found==null || !StockDetailInfoMerge.sameBusinessValues(row,found))
                throw new IllegalStateException("Recovery source-key values differ from actual target");
        }
        if(publication.state()!=StockDetailPublicationJournal.State.VERIFIED) publisher.acceptPublished(lease,publicationId,true);
        var verified=new LinkedHashMap<String,Object>();int count=actual.rows().size();
        verified.put("passed",true);verified.put("expectedRows",count);verified.put("actualRows",count);verified.put("matchedRows",count);
        verified.put("mismatchedRows",0);verified.put("duplicateKeys",0);verified.put("missingKeys",0);
        verified.put("readbackEvidence",receipt.toString());verified.put("writerStopped",true);
        verified.put("sourceFingerprint",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(proof.path("sourceRows")))));
        var payload=Map.of("verification",verified,"sourceRows",sourceRows,"verifiedRows",sourceRows,
                "checkpoint",actual.fingerprint(),"targetAfter",actual.identity(),"publicationId",publicationId);
        String attempt=runId+"-attempt";
        if(proof.has("snapshotSlice")) {
            String slice=proof.path("snapshotSlice").asText();
            var entry=ledger.get(slice);
            if(!slice.equals(runId+"-snapshot") || entry.kind()!=SyncRunLedger.Kind.SLICE
                    || !entry.runId().equals(runId) || !entry.parentId().equals(attempt))
                throw new IllegalStateException("Static publication slice ownership differs");
            if(entry.state()!=SyncRunState.VERIFIED)
                ledger.transition(slice,entry.revision(),SyncRunState.VERIFIED,json.writeValueAsString(payload));
        }
        if(ledger.get(attempt).state()!=SyncRunState.VERIFIED)
            ledger.transition(attempt,ledger.get(attempt).revision(),SyncRunState.VERIFIED,json.writeValueAsString(payload));
        ledger.transition(runId,ledger.get(runId).revision(),SyncRunState.VERIFIED,json.writeValueAsString(payload));
        lease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates("stock_detail_info"));
        if(lease.inDoubt()) locks.releaseAfterReconciliation(lease,true,true);else locks.releaseVerified(lease);
        var merge=proof.path("merge");
        return new StockDetailInfoJobService.Result(runId,SyncRunState.VERIFIED,sourceRows,merge.path("insertedRows").asInt(),
                merge.path("updatedRows").asInt(),merge.path("unchangedRows").asInt(),sourceRows,publicationId,receipt.toString(),null);
    }
}
