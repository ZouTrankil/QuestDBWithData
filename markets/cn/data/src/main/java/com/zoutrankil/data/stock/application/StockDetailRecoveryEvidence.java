package com.zoutrankil.data.stock.application;
import com.zoutrankil.data.stock.domain.StockDetailState;

import com.zoutrankil.data.stock.storage.StockDetailPublicationJournal;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.stock.port.StockDetailTarget;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Freeze the complete intended merge before any staging write; reconstruct only from exact published readback. */
final class StockDetailRecoveryEvidence {
    private StockDetailRecoveryEvidence() {}
    static void prepare(Path evidence,String run,String target,SyncJobDefinition.FrozenRequest request,
            Instant observed,List<StockDetailInfo> source,List<String> receipts,StockDetailState.Prepared prepared)
            throws Exception {
        Files.createDirectories(evidence);
        FileEvidenceStore.writeNewUtf8(evidence.resolve("prepared-publication.json"),JobDefinitionJson.mapper().writeValueAsString(
                Map.of("runId",run,"targetId",target,"request",SyncRequestIdentity.snapshotJson(request),
                        "observedAt",observed,"sourceRows",source,"sources",receipts,"prepared",prepared)));
    }
    private record Pending(com.fasterxml.jackson.databind.JsonNode frozen,StockDetailState.Prepared prepared,
                           StockDetailPublicationJournal.Entry entry) {}
    private static Pending validate(StockDetailTarget database,Path ledgerPath,String table,SyncRunLedger.Run run,Path receipt)
            throws Exception {
        var json=JobDefinitionJson.mapper();
        var frozen=json.readTree(receipt.resolveSibling("prepared-publication.json").toFile());
        if(!run.id().equals(frozen.path("runId").asText()) || !run.targetId().equals(frozen.path("targetId").asText())
                || !run.frozenJson().equals(frozen.path("request").asText()))
            throw new IllegalStateException("Prepared publication evidence differs from frozen run");
        var prepared=json.treeToValue(frozen.path("prepared"),StockDetailState.Prepared.class);
        if(!run.targetId().equals(database.identify(table,prepared.before().identity())))
            throw new IllegalStateException("Prepared publication endpoint differs from current connection");
        var sources=new ArrayList<StockDetailInfo>();
        for(var row:frozen.path("sourceRows")) sources.add(json.treeToValue(row,StockDetailInfo.class));
        var recomputed=database.prepare(prepared.before(),sources);
        if(sources.isEmpty() || !recomputed.equals(prepared) || !prepared.merge().requiresPublication())
            throw new IllegalStateException("Prepared publication merge is inconsistent");
        var entry=new StockDetailPublicationJournal(ledgerPath).findSingleForRun(run.id());
        if(entry==null) return new Pending(frozen,prepared,null);
        var intent=entry.intent();
        if(!intent.target().equals(table) || intent.oldTableId()!=prepared.before().identity().id()
                || !intent.beforeFingerprint().equals(prepared.before().fingerprint()))
            throw new IllegalStateException("Publication intent differs from complete prepared input");
        return new Pending(frozen,prepared,entry);
    }
    static void finish(StockDetailTarget database,Path ledgerPath,String table,SyncRunLedger.Run run,Path receipt,
                       DatasetIntervalLock.Lease lease) throws Exception {
        var pending=validate(database,ledgerPath,table,run,receipt);
        if(pending.entry()==null) {
            var actual=database.open(table).snapshot();var before=pending.prepared().before();
            if(!actual.identity().equals(before.identity()) || !actual.rows().equals(before.rows())
                    || !actual.fingerprint().equals(before.fingerprint()))
                throw new IllegalStateException("Original target changed during interrupted staging");
            var folder=receipt.getParent().resolve("staging-recovery-"+UUID.randomUUID());
            Files.createDirectories(folder);
            FileEvidenceStore.writeNewUtf8(folder.resolve("recovery-intent.json"),JobDefinitionJson.mapper().writeValueAsString(
                    Map.of("runId",run.id(),"targetId",run.targetId(),"sourceReplayRequests",0,
                            "action","retain old partial stages; rebuild from frozen complete merge")));
            var replacement=database.newStaging().write(pending.prepared(),folder);
            new StockDetailInfoPublication(database,ledgerPath).publishAfterStoppedWriter(lease,table,pending.prepared(),replacement,true);
            return;
        }
        var intent=pending.entry().intent();
        var publisher=new StockDetailInfoPublication(database,ledgerPath);var layout=publisher.inspect(intent.id()).layout();
        if(layout==StockDetailInfoPublication.Layout.PUBLISHED) return;
        if(layout!=StockDetailInfoPublication.Layout.ORIGINAL && layout!=StockDetailInfoPublication.Layout.OLD_MOVED)
            throw new IllegalStateException("Conflicting publication cannot be resumed");
        var staged=database.open(intent.stage()).snapshot();
        if(!staged.rows().equals(pending.prepared().rows()))
            throw new IllegalStateException("Staged values differ from prepared source merge");
        publisher.finishInterrupted(lease,intent.id(),true);
    }
    static void reconstruct(StockDetailTarget database,Path ledgerPath,String table,SyncRunLedger.Run run,Path receipt)
            throws Exception {
        var pending=validate(database,ledgerPath,table,run,receipt);var json=JobDefinitionJson.mapper();
        if(pending.entry()==null) throw new IllegalStateException("No publication to reconcile; explicit finish is required");
        var prepared=pending.prepared();var frozen=pending.frozen();var intent=pending.entry().intent();
        if(new StockDetailInfoPublication(database,ledgerPath).inspect(intent.id()).layout()
                        !=StockDetailInfoPublication.Layout.PUBLISHED)
            throw new IllegalStateException("Interrupted publication is not exactly published");
        var after=database.open(table).snapshot();
        if(!after.rows().equals(prepared.rows()) || !after.fingerprint().equals(intent.afterFingerprint()))
            throw new IllegalStateException("Published rows differ from complete intended merge");
        var proof=json.createObjectNode();
        for(String field:List.of("runId","request","observedAt","sourceRows","sources")) proof.set(field,frozen.path(field));
        proof.put("snapshotSlice",run.id()+"-snapshot");
        proof.set("beforeIdentity",json.valueToTree(prepared.before().identity()));
        proof.put("beforeFingerprint",prepared.before().fingerprint());
        proof.set("after",json.valueToTree(after));proof.set("merge",json.valueToTree(prepared.merge()));
        proof.put("publicationId",intent.id());proof.put("stagingSubmittedRows",prepared.rows().size());
        proof.put("checkpointKind","verified identity/content snapshot; no upstream date watermark");
        proof.put("reconstructedFrom","prepared-publication.json and exact live target/backup readback");
        FileEvidenceStore.writeNewUtf8(receipt,json.writeValueAsString(proof));
    }
}
