package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Freeze the complete intended merge before any staging write; reconstruct only from exact published readback. */
final class StockDetailRecoveryEvidence {
    private StockDetailRecoveryEvidence() {}
    static void prepare(Path evidence,String run,String target,SyncJobDefinition.FrozenRequest request,
            Instant observed,List<StockDetailInfo> source,List<String> receipts,StockDetailInfoStaging.Prepared prepared)
            throws Exception {
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("prepared-publication.json"),JobDefinitionJson.mapper().writeValueAsString(
                Map.of("runId",run,"targetId",target,"request",SyncRequestIdentity.snapshotJson(request),
                        "observedAt",observed,"sourceRows",source,"sources",receipts,"prepared",prepared)),
                StandardOpenOption.CREATE_NEW);
    }
    private record Pending(com.fasterxml.jackson.databind.JsonNode frozen,StockDetailInfoStaging.Prepared prepared,
                           StockDetailPublicationJournal.Entry entry) {}
    private static Pending validate(JdbcTemplate jdbc,Path ledgerPath,String table,SyncRunLedger.Run run,Path receipt)
            throws Exception {
        var json=JobDefinitionJson.mapper();
        var frozen=json.readTree(receipt.resolveSibling("prepared-publication.json").toFile());
        if(!run.id().equals(frozen.path("runId").asText()) || !run.targetId().equals(frozen.path("targetId").asText())
                || !run.frozenJson().equals(frozen.path("request").asText()))
            throw new IllegalStateException("Prepared publication evidence differs from frozen run");
        var prepared=json.treeToValue(frozen.path("prepared"),StockDetailInfoStaging.Prepared.class);
        if(!run.targetId().equals(StaticTargetIdentity.identify(jdbc,table,prepared.before().identity())))
            throw new IllegalStateException("Prepared publication endpoint differs from current connection");
        var sources=new ArrayList<StockDetailInfo>();
        for(var row:frozen.path("sourceRows")) sources.add(json.treeToValue(row,StockDetailInfo.class));
        var recomputed=StockDetailInfoStaging.prepare(prepared.before(),sources);
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
    static void finish(JdbcTemplate jdbc,Path ledgerPath,String table,SyncRunLedger.Run run,Path receipt,
                       DatasetIntervalLock.Lease lease) throws Exception {
        var pending=validate(jdbc,ledgerPath,table,run,receipt);
        if(pending.entry()==null) {
            var actual=new StockDetailInfoStorage(jdbc,table).snapshot();var before=pending.prepared().before();
            if(!actual.identity().equals(before.identity()) || !actual.rows().equals(before.rows())
                    || !actual.fingerprint().equals(before.fingerprint()))
                throw new IllegalStateException("Original target changed during interrupted staging");
            var folder=receipt.getParent().resolve("staging-recovery-"+UUID.randomUUID());
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("recovery-intent.json"),JobDefinitionJson.mapper().writeValueAsString(
                    Map.of("runId",run.id(),"targetId",run.targetId(),"sourceReplayRequests",0,
                            "action","retain old partial stages; rebuild from frozen complete merge")),StandardOpenOption.CREATE_NEW);
            var replacement=new StockDetailInfoStaging(jdbc).write(pending.prepared(),folder);
            new StockDetailInfoPublication(jdbc,ledgerPath).publishAfterStoppedWriter(lease,table,pending.prepared(),replacement,true);
            return;
        }
        var intent=pending.entry().intent();
        var publisher=new StockDetailInfoPublication(jdbc,ledgerPath);var layout=publisher.inspect(intent.id()).layout();
        if(layout==StockDetailInfoPublication.Layout.PUBLISHED) return;
        if(layout!=StockDetailInfoPublication.Layout.ORIGINAL && layout!=StockDetailInfoPublication.Layout.OLD_MOVED)
            throw new IllegalStateException("Conflicting publication cannot be resumed");
        var staged=new StockDetailInfoStorage(jdbc,intent.stage()).snapshot();
        if(!staged.rows().equals(pending.prepared().rows()))
            throw new IllegalStateException("Staged values differ from prepared source merge");
        publisher.finishInterrupted(lease,intent.id(),true);
    }
    static void reconstruct(JdbcTemplate jdbc,Path ledgerPath,String table,SyncRunLedger.Run run,Path receipt)
            throws Exception {
        var pending=validate(jdbc,ledgerPath,table,run,receipt);var json=JobDefinitionJson.mapper();
        if(pending.entry()==null) throw new IllegalStateException("No publication to reconcile; explicit finish is required");
        var prepared=pending.prepared();var frozen=pending.frozen();var intent=pending.entry().intent();
        if(new StockDetailInfoPublication(jdbc,ledgerPath).inspect(intent.id()).layout()
                        !=StockDetailInfoPublication.Layout.PUBLISHED)
            throw new IllegalStateException("Interrupted publication is not exactly published");
        var after=new StockDetailInfoStorage(jdbc,table).snapshot();
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
        Files.writeString(receipt,json.writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
    }
}
