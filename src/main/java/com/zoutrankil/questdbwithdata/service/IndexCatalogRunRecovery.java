package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;

/** Explicit stopped-writer completion from a frozen file receipt and exact WAL layout. */
public final class IndexCatalogRunRecovery {
    private IndexCatalogRunRecovery() {}

    public static IndexCatalogJobService.Result finish(JdbcTemplate jdbc,Path ledgerPath,String table,
                                                       String runId,boolean writerStopped) throws Exception {
        if(!writerStopped) throw new IllegalStateException("Stopped catalog writer proof required");
        DatasetDefinition.identifier(table);ledgerPath=ledgerPath.toAbsolutePath().normalize();
        var ledger=new SyncRunLedger(ledgerPath);var run=ledger.getRun(runId);var root=ledger.get(runId);
        if(!Set.of("data.index","write.index").contains(run.jobId()) || run.jobVersion()!=1
                || !Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT).contains(root.state()))
            throw new IllegalStateException("Expected unfinished catalog owner run");
        var locks=new DatasetIntervalLock(ledgerPath);
        var lease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates("index"));
        if(lease==null) throw new IllegalStateException("Retained catalog dataset lease required");
        Path folder=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        if(Files.size(folder.resolve("prepared.json"))>64L*1024*1024)
            throw new IllegalStateException("Catalog recovery evidence exceeds bound");
        var json=JobDefinitionJson.mapper();var frozen=json.readTree(folder.resolve("prepared.json").toFile());
        if(!runId.equals(frozen.path("runId").asText())
                || !run.targetId().equals(frozen.path("targetId").asText())
                || !run.frozenJson().equals(frozen.path("request").asText()))
            throw new IllegalStateException("Frozen catalog input differs from run authority");
        var source=json.treeToValue(frozen.path("source"),IndexCatalogFileSource.Input.class);
        var request=json.readTree(run.frozenJson());
        if(run.jobId().equals("data.index")) {
            if(!source.path().equals(request.path("parameters").path("file").asText())
                    || !source.sha256().equals(request.path("parameters").path("sha256").asText())
                    || !IndexCatalogJobService.definition().equals(json.treeToValue(request.path("definition"),SyncJobDefinition.class)))
                throw new IllegalStateException("Catalog file source differs from frozen request");
        } else {
            Path input=Path.of(source.path()).toAbsolutePath().normalize();
            byte[] bytes=IndexCatalogFileSource.readBounded(input,16*1024*1024);
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if(bytes.length>16*1024*1024 || bytes.length!=source.bytes() || !hash.equals(source.sha256()))
                throw new IllegalStateException("Prepared catalog receipt changed");
            var proof=json.readTree(bytes);
            var mapper=new com.zoutrankil.questdbwithdata.mapper.IndexCatalogMapper();
            if(!proof.path("sourceKind").asText().equals("prepared-write-request")
                    || !proof.path("targetId").asText().equals(run.targetId())
                    || !proof.path("fingerprint").asText().equals(request.path("parameters").path("payloadFingerprint").asText())
                    || !json.valueToTree(source.rows().stream().map(mapper::values).toList()).equals(proof.path("rows")))
                throw new IllegalStateException("Prepared catalog input differs from frozen request");
        }
        var prepared=json.treeToValue(frozen.path("prepared"),IndexCatalogStaging.Prepared.class);
        if(source.rows().size()>IndexCatalogFileSource.MAX_ROWS
                || !source.sha256().matches("[0-9a-f]{64}")
                || !IndexCatalogStaging.prepare(prepared.before(),source.rows()).equals(prepared))
            throw new IllegalStateException("Prepared catalog receipt is incomplete or inconsistent");
        if(!prepared.merge().requiresWrite())
            return IndexCatalogNoWriteRecovery.finish(jdbc,ledgerPath,table,runId,folder,source,prepared,lease);
        var journal=new ReferencePublicationJournal(ledgerPath,"index");
        var existing=journal.findForRun(runId);
        var entry=existing.isPresent()?existing.get():IndexCatalogStageRecovery.prepare(jdbc,ledgerPath,table,runId,folder,prepared,lease);
        var intent=entry.intent();
        if(!intent.target().equals(table) || !intent.initialTarget().equals(run.targetId())
                || intent.originalId()!=prepared.before().identity().id()
                || !intent.originalDirectory().equals(prepared.before().identity().directory())
                || !intent.beforeFingerprint().equals(prepared.before().fingerprint())
                || !intent.beforeFingerprint().equals(fingerprint(prepared.before().rows()))
                || !intent.afterFingerprint().equals(fingerprint(prepared.rows())))
            throw new IllegalStateException("Catalog publication differs from frozen prepared input");
        var publication=new IndexCatalogPublication(jdbc,ledgerPath);
        if(publication.inspect(runId)==IndexCatalogPublication.Layout.CONFLICT)
            throw new IllegalStateException("Conflicting catalog physical layout");
        var completed=publication.finish(lease,true);
        var actual=completed.actual();
        var backup=new IndexCatalogStorage(jdbc,intent.backup()).snapshot();
        if(!actual.rows().equals(prepared.rows()) || !actual.fingerprint().equals(intent.afterFingerprint())
                || !backup.equals(prepared.before()))
            throw new IllegalStateException("Catalog recovery target/backup differs from frozen full rows");
        var byKey=new HashMap<String,IndexCatalogEntry>();
        for(var row:actual.businessRows()) if(byKey.putIfAbsent(row.indexCode(),row)!=null)
            throw new IllegalStateException("Duplicate catalog business identity after recovery");
        for(var row:source.rows()) if(!IndexCatalogMerge.sameBusinessValues(row,byKey.get(row.indexCode())))
            throw new IllegalStateException("Recovered catalog source values differ");
        Path receipt=folder.resolve("completion.json");
        var proof=new LinkedHashMap<String,Object>();
        proof.put("runId",runId);proof.put("source",source);proof.put("before",prepared.before());
        proof.put("actual",actual);proof.put("merge",prepared.merge());
        proof.put("publicationId",intent.id());proof.put("submittedStageRows",prepared.rows().size());
        if(Files.exists(receipt)) {
            if(!json.readTree(receipt.toFile()).equals(json.readTree(json.writeValueAsBytes(proof))))
                throw new IllegalStateException("Existing catalog completion receipt differs from physical proof");
        } else Files.writeString(receipt,json.writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
        int sourceRows=source.rows().size();
        var verification=Map.of("passed",true,"expectedRows",sourceRows,"actualRows",sourceRows,
                "matchedRows",sourceRows,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                "readbackEvidence",receipt.toString(),"sourceFingerprint",source.sha256(),"writerStopped",true);
        var payload=Map.of("verification",verification,"evidence",receipt.toString(),
                "checkpoint",actual.fingerprint(),"publicationId",intent.id());
        String attempt=runId+"-attempt",slice=runId+"-snapshot";
        var attemptEntry=ledger.get(attempt);var sliceEntry=ledger.get(slice);
        if(attemptEntry.kind()!=SyncRunLedger.Kind.ATTEMPT || !attemptEntry.parentId().equals(runId)
                || sliceEntry.kind()!=SyncRunLedger.Kind.SLICE || !sliceEntry.parentId().equals(attempt))
            throw new IllegalStateException("Catalog run/attempt/slice ownership differs");
        for(String id:List.of(slice,attempt,runId)) {
            var current=ledger.get(id);
            if(current.state()!=SyncRunState.VERIFIED)
                ledger.transition(id,current.revision(),SyncRunState.VERIFIED,json.writeValueAsString(payload));
        }
        var currentLease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates("index"));
        if(currentLease==null) throw new IllegalStateException("Catalog recovery lease disappeared");
        if(currentLease.inDoubt()) locks.releaseAfterReconciliation(currentLease,true,true);
        else locks.releaseVerified(currentLease);
        var merge=prepared.merge();
        return new IndexCatalogJobService.Result(runId,SyncRunState.VERIFIED,sourceRows,
                merge.inserted(),merge.revised(),merge.unchanged(),merge.retainedAbsent(),sourceRows,
                intent.id(),receipt.toString(),null);
    }

    private static String fingerprint(List<com.zoutrankil.questdbwithdata.domain.table.IndexRow> rows) throws Exception {
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(rows);
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
