package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Stopped-writer completion from the frozen provider receipt and exact WAL layout. */
public final class ThsIndexRunRecovery {
    private ThsIndexRunRecovery() {}

    public static ThsIndexJobService.Result finish(JdbcTemplate jdbc,Path ledgerPath,String table,
                                                       String runId,boolean writerStopped) throws Exception {
        if(!writerStopped) throw new IllegalStateException("Stopped THS writer proof required");
        DatasetDefinition.identifier(table);ledgerPath=ledgerPath.toAbsolutePath().normalize();
        var ledger=new SyncRunLedger(ledgerPath);var run=ledger.getRun(runId);var root=ledger.get(runId);
        if(!Set.of("data.ths_index","write.ths_index").contains(run.jobId()) || run.jobVersion()!=1
                || !Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT).contains(root.state()))
            throw new IllegalStateException("Expected unfinished THS owner run");
        var locks=new DatasetIntervalLock(ledgerPath);
        var lease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates("ths_index"));
        if(lease==null) throw new IllegalStateException("Retained THS dataset lease required");
        Path folder=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        if(Files.size(folder.resolve("prepared.json"))>64L*1024*1024)
            throw new IllegalStateException("THS recovery evidence exceeds bound");
        var json=JobDefinitionJson.mapper();var frozen=json.readTree(folder.resolve("prepared.json").toFile());
        if(!runId.equals(frozen.path("runId").asText())
                || !run.targetId().equals(frozen.path("targetId").asText())
                || !run.frozenJson().equals(frozen.path("request").asText()))
            throw new IllegalStateException("Frozen THS input differs from run authority");
        var source=json.readValue(json.writeValueAsBytes(frozen.path("source")),
                new com.fasterxml.jackson.core.type.TypeReference<SyncJobRunner.Page<ThsIndex>>() {});
        var request=json.readTree(run.frozenJson());
        Path input=Path.of(source.responseEvidence()).toAbsolutePath().normalize();
        boolean preparedWrite=run.jobId().equals("write.ths_index");
        Path allowed=preparedWrite?ledgerPath.getParent().resolve("write-evidence").toAbsolutePath().normalize()
                :folder.toAbsolutePath().normalize();
        if(!(preparedWrite?input.startsWith(allowed):input.getParent().equals(allowed))
                || Files.size(input)>ThsIndexStorage.MAX_BYTES)
            throw new IllegalStateException("THS source receipt path or size differs");
        byte[] bytes=Files.readAllBytes(input);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var sourceProof=json.readTree(bytes);
        if(!hash.equals(source.sourceFingerprint()))
            throw new IllegalStateException("THS source receipt hash changed");
        if(preparedWrite) {
            var mapper=new com.zoutrankil.questdbwithdata.mapper.ThsIndexMapper();
            if(!sourceProof.path("sourceKind").asText().equals("prepared-write-request")
                    || !sourceProof.path("targetId").asText().equals(run.targetId())
                    || !sourceProof.path("fingerprint").asText().equals(request.path("parameters").path("payloadFingerprint").asText())
                    || !json.valueToTree(source.rows().stream().map(mapper::values).toList()).equals(sourceProof.path("rows")))
                throw new IllegalStateException("Prepared THS receipt differs from frozen request");
        } else {
            if(!ThsIndexJobService.definition().equals(json.treeToValue(request.path("definition"),SyncJobDefinition.class))
                    || !request.path("parameters").isEmpty())
                throw new IllegalStateException("THS source differs from frozen request");
            if(!sourceProof.path("endpoint").asText().equals("ths_index")
                || !sourceProof.path("parameters").isEmpty() || sourceProof.path("completion").path("pages").asInt()!=1
                || sourceProof.path("completion").path("rows").asInt()!=source.rows().size()
                || sourceProof.path("rows").size()!=source.rows().size() || source.rows().isEmpty()
                || source.rows().size()>=ThsIndexStorage.MAX_ROWS)
                throw new IllegalStateException("THS source receipt is incomplete or changed");
            Instant observed=Instant.parse(sourceProof.path("observedAt").asText());
            var mapper=new com.zoutrankil.questdbwithdata.mapper.ThsIndexMapper();
            var decoded=new ArrayList<ThsIndex>();
            for(var row:sourceProof.path("rows")) decoded.add(mapper.fromSource(
                    new com.zoutrankil.questdbwithdata.client.dto.TushareThsIndexDto(
                            value(row,"ts_code"),value(row,"name"),row.path("count").isNull()?null:row.path("count").intValue(),
                            value(row,"exchange"),value(row,"list_date"),value(row,"type")),observed));
            if(!decoded.equals(source.rows())) throw new IllegalStateException("Frozen THS rows differ from provider receipt");
        }
        var prepared=json.treeToValue(frozen.path("prepared"),ThsIndexStaging.Prepared.class);
        var recomputed=preparedWrite?ThsIndexStaging.preparePrepared(prepared.before(),source.rows())
                :ThsIndexStaging.prepare(prepared.before(),source.rows(),ThsIndexSource.Scope.all());
        if(!recomputed.equals(prepared))
            throw new IllegalStateException("Prepared THS receipt is incomplete or inconsistent");
        if(!prepared.merge().requiresWrite())
            return ThsIndexNoWriteRecovery.finish(jdbc,ledgerPath,table,runId,folder,source,prepared,lease);
        var journal=new ReferencePublicationJournal(ledgerPath,"ths_index");
        var existing=journal.findForRun(runId);
        var entry=existing.isPresent()?existing.get():ThsIndexStageRecovery.prepare(jdbc,ledgerPath,table,runId,folder,prepared,lease);
        var intent=entry.intent();
        if(!intent.target().equals(table) || !intent.initialTarget().equals(run.targetId())
                || intent.originalId()!=prepared.before().identity().id()
                || !intent.originalDirectory().equals(prepared.before().identity().directory())
                || !intent.beforeFingerprint().equals(prepared.before().fingerprint())
                || !intent.beforeFingerprint().equals(fingerprint(prepared.before().rows()))
                || !intent.afterFingerprint().equals(fingerprint(prepared.rows())))
            throw new IllegalStateException("THS publication differs from frozen prepared input");
        var publication=new ThsIndexPublication(jdbc,ledgerPath);
        if(publication.inspect(runId)==ThsIndexPublication.Layout.CONFLICT)
            throw new IllegalStateException("Conflicting THS physical layout");
        var completed=publication.finish(lease,true);
        var actual=completed.actual();
        var backup=new ThsIndexStorage(jdbc,intent.backup()).snapshot();
        if(!actual.rows().equals(prepared.rows()) || !actual.fingerprint().equals(intent.afterFingerprint())
                || !backup.equals(prepared.before()))
            throw new IllegalStateException("THS recovery target/backup differs from frozen full rows");
        var byKey=new HashMap<String,ThsIndex>();
        for(var row:actual.businessRows()) if(byKey.putIfAbsent(row.tsCode(),row)!=null)
            throw new IllegalStateException("Duplicate THS business identity after recovery");
        for(var row:source.rows()) if(!ThsIndexMerge.sameBusinessValues(row,byKey.get(row.tsCode())))
            throw new IllegalStateException("Recovered THS source values differ");
        Path receipt=folder.resolve("completion.json");
        var proof=new LinkedHashMap<String,Object>();
        proof.put("runId",runId);proof.put("source",source);proof.put("before",prepared.before());
        proof.put("actual",actual);proof.put("merge",prepared.merge());
        proof.put("publicationId",intent.id());proof.put("submittedStageRows",prepared.rows().size());
        if(Files.exists(receipt)) {
            if(!json.readTree(receipt.toFile()).equals(json.readTree(json.writeValueAsBytes(proof))))
                throw new IllegalStateException("Existing THS completion receipt differs from physical proof");
        } else Files.writeString(receipt,json.writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
        int sourceRows=source.rows().size();
        var verification=Map.of("passed",true,"expectedRows",sourceRows,"actualRows",sourceRows,
                "matchedRows",sourceRows,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                "readbackEvidence",receipt.toString(),"sourceFingerprint",source.sourceFingerprint(),"writerStopped",true);
        var payload=Map.of("verification",verification,"evidence",receipt.toString(),
                "checkpoint",actual.fingerprint(),"publicationId",intent.id());
        String attempt=runId+"-attempt",slice=runId+"-snapshot";
        var attemptEntry=ledger.get(attempt);var sliceEntry=ledger.get(slice);
        if(attemptEntry.kind()!=SyncRunLedger.Kind.ATTEMPT || !attemptEntry.parentId().equals(runId)
                || sliceEntry.kind()!=SyncRunLedger.Kind.SLICE || !sliceEntry.parentId().equals(attempt))
            throw new IllegalStateException("THS run/attempt/slice ownership differs");
        for(String id:List.of(slice,attempt,runId)) {
            var current=ledger.get(id);
            if(current.state()!=SyncRunState.VERIFIED)
                ledger.transition(id,current.revision(),SyncRunState.VERIFIED,json.writeValueAsString(payload));
        }
        var currentLease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates("ths_index"));
        if(currentLease==null) throw new IllegalStateException("THS recovery lease disappeared");
        if(currentLease.inDoubt()) locks.releaseAfterReconciliation(currentLease,true,true);
        else locks.releaseVerified(currentLease);
        var merge=prepared.merge();
        return new ThsIndexJobService.Result(runId,SyncRunState.VERIFIED,sourceRows,
                merge.inserted(),merge.revised(),merge.removed(),merge.unchanged(),sourceRows,
                intent.id(),receipt.toString(),null);
    }

    private static String value(com.fasterxml.jackson.databind.JsonNode row,String field) {
        var node=row.path(field);
        if(node.isMissingNode() || node.isNull()) return null;
        if(!node.isTextual()) throw new IllegalArgumentException("THS source text field changed: "+field);
        return node.asText();
    }

    private static String fingerprint(List<com.zoutrankil.questdbwithdata.domain.table.ThsIndexRow> rows) throws Exception {
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(rows);
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
