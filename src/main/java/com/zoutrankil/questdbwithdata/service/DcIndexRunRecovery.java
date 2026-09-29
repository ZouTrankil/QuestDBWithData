package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Completes only an interrupted run whose journaled DEDUP=false replacement and full snapshots reconcile. */
public final class DcIndexRunRecovery {
    private DcIndexRunRecovery(){}
    private static String requiredText(JsonNode node,String field){
        JsonNode value=node.path(field);
        if(!value.isTextual()||value.asText().isBlank())throw new IllegalStateException("D023 recovery evidence missing text field: "+field);
        return value.asText();
    }

    /** Reconcile a complete run-owned D023 stage created before its publication journal existed. */
    public static void finishStageOnly(JdbcTemplate jdbc,QuestDB questdb,Path ledgerPath,String table,String runId,boolean writerStopped)throws Exception{
        if(!writerStopped)throw new IllegalStateException("Stopped D023 writer proof required before stage-only recovery");
        var ledger=new SyncRunLedger(ledgerPath);var run=ledger.getRun(runId);var runEntry=ledger.get(runId);
        if(!Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT).contains(runEntry.state())
                ||!DcIndexSyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=DcIndexSyncJobOwner.DEFINITION.version())
            throw new IllegalStateException("D023 stage-only recovery requires an active/in-doubt run of the frozen definition");
        var request=restoreActiveRequest(run.frozenJson(),run.targetId());DcIndexSyncAdapter.validateRequest(request);
        var params=request.parameters();String logical=Objects.toString(params.get("targetId"),""),physical=Objects.toString(params.get("physicalTargetId"),"");
        if(!run.targetId().equals(logical)||!logical.equals(DcIndexTargetIdentity.logical(jdbc,table)))throw new IllegalStateException("D023 stage-only run is outside the frozen logical target");
        var dates=DcIndexSyncAdapter.decodeDates(request);Path evidenceRoot=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).toRealPath();
        Path stageRoot=evidenceRoot.resolve("staging");Path intentPath=findStageIntent(stageRoot,evidenceRoot);JsonNode intent=JobDefinitionJson.mapper().readTree(Files.readAllBytes(intentPath));
        String stage=requiredText(intent,"stage");if(!stage.matches("java_dc_index_stage_[0-9a-f]{32}")||!"dc_index".equals(intent.path("dataset").asText())
                ||!table.equals(intent.path("target").asText())||!runId.equals(intent.path("runId").asText())||!logical.equals(intent.path("logicalTargetId").asText())
                ||!physical.equals(intent.path("physicalTargetBefore").asText())
                ||!SyncRequestIdentity.fingerprint(request,logical).equals(intent.path("requestFingerprint").asText())
                ||!request.mode().name().equals(intent.path("mode").asText())||!request.logicalDate().toString().equals(intent.path("logicalDate").asText())
                ||!request.from().toString().equals(intent.path("fromInclusive").asText())
                ||!request.to().toString().equals(intent.path("toInclusive").asText()))throw new IllegalStateException("D023 stage intent differs from frozen run scope");
        var entries=ledger.entries(runId,null,1000);var attempts=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).toList();
        var slices=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        if(attempts.size()!=1||!Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT).contains(attempts.getFirst().state())||slices.size()!=dates.size())
            throw new IllegalStateException("D023 stage-only recovery needs one active attempt and one fetched slice per frozen date; partial slice creation is fail-closed");
        var pagesByDate=new HashMap<LocalDate,SyncJobRunner.Page<DcIndex>>();var slicesByDate=new HashMap<LocalDate,SyncRunLedger.Entry>();
        Path sourceRoot=evidenceRoot.resolve("source").toRealPath();var allRows=new ArrayList<DcIndex>();
        for(var slice:slices){if(slice.state().terminal()&&slice.state()!=SyncRunState.VERIFIED&&slice.state()!=SyncRunState.VERIFIED_EMPTY)
                throw new IllegalStateException("D023 stage-only recovery cannot rewrite a failed/cancelled/partial slice");
            var fetched=ledger.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();if(fetched.size()!=1)throw new IllegalStateException("D023 stage-only slice needs one immutable FETCHED source event");
            JsonNode event=JobDefinitionJson.mapper().readTree(fetched.getFirst().payloadJson());String cursor=requiredText(event,"cursor");if(!cursor.matches("[0-9]{8}"))throw new IllegalStateException("D023 stage-only cursor is not a BASIC date");
            LocalDate date=LocalDate.parse(cursor,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);if(!dates.contains(date))throw new IllegalStateException("D023 stage-only slice date is outside the frozen request");
            Path receipt=safeEvidence(sourceRoot,requiredText(event,"responseEvidence"));var page=DcIndexSource.reopen(receipt,requiredText(event,"sourceFingerprint"),date);
            if(page.rows().size()!=event.path("returnedRows").asInt(-1)
                    ||page.rows().isEmpty()&&!Set.of(SyncRunState.FETCHED,SyncRunState.VERIFIED_EMPTY).contains(slice.state())
                    ||!page.rows().isEmpty()&&slice.state()==SyncRunState.VERIFIED_EMPTY)throw new IllegalStateException("D023 stage-only source receipt differs from FETCHED ledger evidence");
            if(pagesByDate.putIfAbsent(date,page)!=null||slicesByDate.putIfAbsent(date,slice)!=null)throw new IllegalStateException("D023 stage-only duplicate daily slice");allRows.addAll(page.rows());}
        if(!pagesByDate.keySet().equals(new HashSet<>(dates)))throw new IllegalStateException("D023 stage-only source slice set is incomplete");
        if(allRows.size()>DcIndexSyncJobOwner.DEFINITION.budget().maxRows())throw new IllegalStateException("D023 stage-only source receipts exceed the frozen row budget");
        var orderedPages=dates.stream().map(pagesByDate::get).toList();String combined=combine(orderedPages.stream().map(SyncJobRunner.Page::sourceFingerprint).toList());
        if(intent.path("sourceRows").asInt(-1)!=allRows.size())throw new IllegalStateException("D023 stage intent row count differs from immutable source receipts");

        var publisher=new DcIndexPublication(jdbc,ledgerPath,evidenceRoot,table,logical,runId);
        try(var lock=publisher.acquireForRecovery(runId)){
            var before=new DcIndexStorage(jdbc,table).snapshot();String actualPhysical=DcIndexStorage.physicalTargetId(jdbc,table,before.identity());
            if(!physical.equals(actualPhysical)||!before.fingerprint().equals(requiredText(intent,"beforeFingerprint"))
                    ||before.identity().id()!=intent.path("beforeId").asLong(-1)||!before.identity().directory().equals(intent.path("beforeDirectory").asText()))
                throw new IllegalStateException("D023 target changed after stage-only intent; refusing publication");
            var prepared=DcIndexStaging.prepare(before,before,allRows,request.from(),request.to());
            var stageSnapshot=new DcIndexStorage(jdbc,stage).snapshot();if(!DcIndexStaging.sameRows(prepared.expected(),stageSnapshot.rows()))
                throw new IllegalStateException("D023 stage-only snapshot is incomplete; no formal-target mutation attempted");
            Path outside=stageRoot.resolve(stage+"-outside-verified.json");if(Files.isSymbolicLink(outside)||!Files.isRegularFile(outside,LinkOption.NOFOLLOW_LINKS)
                    ||Files.size(outside)>DcIndexSource.MAX_EVIDENCE_BYTES||!outside.toRealPath().startsWith(evidenceRoot))
                throw new IllegalStateException("D023 stage-only outside-snapshot receipt is absent or oversized");
            JsonNode outsideProof=JobDefinitionJson.mapper().readTree(Files.readAllBytes(outside));String currentStagePhysical=DcIndexStorage.physicalTargetId(jdbc,stage,stageSnapshot.identity());
            var expectedOutside=DcIndexStorage.outside(before.rows(),request.from(),request.to().plusDays(1));
            byte[] outsideBytes=DcIndexStorage.canonical(expectedOutside);
            if(!"dc_index".equals(outsideProof.path("dataset").asText())||!stage.equals(outsideProof.path("stage").asText())||outsideProof.path("dedup").asBoolean(true)
                    ||outsideProof.path("stageId").asLong(-1)!=stageSnapshot.identity().id()||!stageSnapshot.identity().directory().equals(outsideProof.path("stageDirectory").asText())
                    ||!currentStagePhysical.equals(outsideProof.path("stagePhysicalTarget").asText())
                    ||outsideProof.path("proofVersion").asInt(-1)!=2||!table.equals(outsideProof.path("target").asText())
                    ||!request.from().toString().equals(outsideProof.path("windowFrom").asText())||!request.to().toString().equals(outsideProof.path("windowTo").asText())
                    ||outsideProof.path("outsideRows").asInt(-1)!=expectedOutside.size()
                    ||outsideProof.path("snapshotProof").path("rowCount").asInt(-1)!=expectedOutside.size()
                    ||outsideProof.path("snapshotProof").path("bytes").asLong(-1)!=outsideBytes.length
                    ||!sha(outsideBytes).equals(outsideProof.path("snapshotProof").path("fingerprint").asText())
                    ||!DcIndexStorage.physicalTargetId(jdbc,table,stageSnapshot.identity()).equals(outsideProof.path("physicalTargetAfter").asText()))
                throw new IllegalStateException("D023 stage physical identity differs from its durable outside-snapshot receipt");
            var verified=new DcIndexStaging.Verified(stage,stageSnapshot,outside.toString());
            var completeStage=new DcIndexStaging(jdbc,table).verifyComplete(prepared,verified,combined,stageRoot,()->false);
            Path complete=evidenceRoot.resolve("complete-window.json");var body=new LinkedHashMap<String,Object>();
            body.put("dataset","dc_index");body.put("endpoint","dc_index");body.put("mode",request.mode().name());body.put("fromInclusive",request.from().toString());body.put("toInclusive",request.to().toString());body.put("tradeDates",dates);
            body.put("completedDateSlices",orderedPages.size());body.put("sourceRows",allRows.size());body.put("returnedRows",allRows.size());body.put("submittedRows",allRows.size());
            body.put("sourceFingerprint",combined);body.put("sourceReceipts",orderedPages.stream().map(SyncJobRunner.Page::responseEvidence).toList());body.put("stage",completeStage.table());
            body.put("stageReceipt",completeStage.receipt());body.put("snapshotProof",DcIndexStaging.snapshotProof(completeStage.snapshot()));body.put("dedup",false);body.put("sourceComplete",true);body.put("complete",true);
            byte[] manifest=JobDefinitionJson.mapper().writeValueAsBytes(body);
            if(Files.exists(complete,LinkOption.NOFOLLOW_LINKS)){if(Files.isSymbolicLink(complete)||!Files.isRegularFile(complete,LinkOption.NOFOLLOW_LINKS)
                        ||Files.size(complete)>DcIndexSource.MAX_EVIDENCE_BYTES||!JobDefinitionJson.mapper().readTree(Files.readAllBytes(complete)).equals(JobDefinitionJson.mapper().valueToTree(body)))
                    throw new IllegalStateException("D023 existing completion manifest conflicts with recovered stage proof");}
            else Files.write(complete,manifest,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            var locks=new DatasetIntervalLock(ledgerPath);var scope=new DatasetIntervalLock.Scope("dc_index",request.from(),request.to());var lease=locks.findOwned(runId,scope);
            if(lease==null)throw new IllegalStateException("D023 stage-only recovery lost its frozen interval lease");if(!lease.inDoubt()){locks.retainInDoubt(lease);lease=locks.findOwned(runId,scope);}
            if(lease==null||!lease.inDoubt())throw new IllegalStateException("D023 stopped stage-only run could not retain its interval lease");
            publisher.publishWindow(before,completeStage,allRows,request.from(),request.to(),physical,combined,complete.toString(),()->false,true);
        }
    }

    public static void finishInterrupted(JdbcTemplate jdbc,Path ledgerPath,String table,String runId,boolean writerStopped)throws Exception{
        if(!writerStopped)throw new IllegalStateException("Stopped D023 writer proof required");
        var journal=new ReferencePublicationJournal(ledgerPath,"dc_index");var publication=journal.forRun(runId);
        if(publication.state()!=ReferencePublicationJournal.State.VERIFIED)throw new IllegalStateException("D023 publication must be journal-verified before run recovery");
        var intent=publication.intent();var ledger=new SyncRunLedger(ledgerPath);var run=ledger.getRun(runId);var state=ledger.get(runId);
        if(!"data.dc_index".equals(run.jobId())||run.jobVersion()!=DcIndexSyncJobOwner.DEFINITION.version()||!run.targetId().equals(intent.initialTarget()))throw new IllegalStateException("D023 recovery run differs from journaled logical owner");
        if(state.state()==SyncRunState.VERIFIED||state.state()==SyncRunState.VERIFIED_EMPTY)return;
        if(state.state()!=SyncRunState.RUNNING&&state.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("Only active/in-doubt D023 publication runs can be reconciled");
        var json=JobDefinitionJson.mapper();JsonNode frozen=json.readTree(run.frozenJson()),params=frozen.path("parameters"),scope=json.readTree(intent.scope());
        LocalDate from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());
        if(!from.toString().equals(scope.path("fromInclusive").asText())||!to.toString().equals(scope.path("toInclusive").asText())
                ||!run.targetId().equals(scope.path("logicalTargetId").asText())||!table.equals(intent.target()))throw new IllegalStateException("D023 recovered publication scope differs from saved request");
        Path evidenceRoot=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).toRealPath();
        Path complete=safeEvidence(evidenceRoot,scope.path("completeEvidence").asText());byte[] completeBytes=Files.readAllBytes(complete);
        if(!sha(completeBytes).equals(scope.path("completeEvidenceSha256").asText()))throw new IllegalStateException("D023 complete receipt SHA differs from publication intent");
        JsonNode proof=json.readTree(completeBytes);if(!proof.path("complete").asBoolean(false)||!proof.path("sourceComplete").asBoolean(false)
                ||proof.path("dedup").asBoolean(true)||!intent.stage().equals(proof.path("stage").asText())
                ||!intent.afterFingerprint().equals(proof.path("snapshotProof").path("fingerprint").asText()))throw new IllegalStateException("D023 completion receipt does not prove the exact replacement stage");
        var dates=decodeDates(params.path("trade_dates").asText(),from,to);if(!json.valueToTree(dates).equals(proof.path("tradeDates")))throw new IllegalStateException("D023 source completion date list differs from frozen request");
        var slices=ledger.entries(runId,null,1000).stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();if(slices.size()!=dates.size())throw new IllegalStateException("D023 interrupted run lacks one slice per frozen source date");
        var byDate=new HashMap<LocalDate,SyncRunLedger.Entry>();var pagesByDate=new HashMap<LocalDate,SyncJobRunner.Page<DcIndex>>();
        var expectedRows=new ArrayList<DcIndex>();var receiptsByDate=new TreeMap<LocalDate,String>();
        for(var slice:slices){var fetched=ledger.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();if(fetched.size()!=1)throw new IllegalStateException("D023 slice lacks one immutable FETCHED source event");
            JsonNode event=json.readTree(fetched.getFirst().payloadJson());String cursor=event.path("cursor").asText();if(!cursor.matches("[0-9]{8}"))throw new IllegalStateException("D023 slice cursor invalid");LocalDate date=LocalDate.parse(cursor,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            if(!dates.contains(date)||byDate.putIfAbsent(date,slice)!=null)throw new IllegalStateException("D023 duplicate/out-of-range recovery slice");
            Path raw=safeEvidence(evidenceRoot,event.path("responseEvidence").asText());var page=DcIndexSource.reopen(raw,event.path("sourceFingerprint").asText(),date);
            if(page.rows().size()!=event.path("returnedRows").asInt(-1)||receiptsByDate.putIfAbsent(date,event.path("sourceFingerprint").asText())!=null)throw new IllegalStateException("D023 source receipt differs from FETCHED event");
            if(page.rows().isEmpty()&&!Set.of(SyncRunState.FETCHED,SyncRunState.VERIFIED_EMPTY).contains(slice.state())
                    ||!page.rows().isEmpty()&&slice.state()==SyncRunState.VERIFIED_EMPTY)throw new IllegalStateException("D023 slice/source empty semantics disagree");
            expectedRows.addAll(page.rows());pagesByDate.put(date,page);}
        if(!receiptsByDate.keySet().equals(new HashSet<>(dates)))throw new IllegalStateException("D023 recovery source receipt set is incomplete");
        String combined=combine(new ArrayList<>(receiptsByDate.values()));if(!combined.equals(proof.path("sourceFingerprint").asText())||!combined.equals(scope.path("sourceFingerprint").asText()))throw new IllegalStateException("D023 recovered daily receipts differ from complete source fingerprint");
        int rows=expectedRows.size();if(rows!=proof.path("sourceRows").asInt(-1)||rows!=proof.path("returnedRows").asInt(-1))throw new IllegalStateException("D023 completed source row count differs from receipts");
        var actual=new DcIndexStorage(jdbc,table).snapshot();if(actual.identity().id()!=intent.replacementId()||!actual.fingerprint().equals(intent.afterFingerprint()))throw new IllegalStateException("D023 target full physical snapshot differs from published journal");
        var actualWindow=actual.rows().stream().filter(r->!r.tradeDate().isBefore(from)&&!r.tradeDate().isAfter(to)).toList();
        if(!DcIndexStaging.sameRows(DcIndexStorage.sourceUnique(expectedRows),actualWindow))throw new IllegalStateException("D023 published window differs from complete receipt-backed source rows");
        // Build one stable per-run publication receipt only after the saved completion and full target both reconcile.
        String physicalAfter=DcIndexStorage.physicalTargetId(jdbc,table,actual.identity());
        var publicationProof=new DcIndexPublication.Proof(run.targetId(),params.path("physicalTargetId").asText(),physicalAfter,from,to,rows,actualWindow.size(),combined,actual.fingerprint(),complete.toString(),true,true);
        Path publicationPath=evidenceRoot.resolve("publication-window.json");var publicationBytes=json.writeValueAsBytes(publicationProof);
        if(Files.exists(publicationPath)){if(!Arrays.equals(Files.readAllBytes(publicationPath),publicationBytes))throw new IllegalStateException("Existing D023 publication proof differs from recovered journal");}
        else Files.write(publicationPath,publicationBytes,StandardOpenOption.CREATE_NEW);
        String readback="questdb-full-snapshot:"+actual.fingerprint();Map<String,Object> verification=Map.of("passed",true,"writerStopped",true,"expectedRows",rows,"actualRows",rows,
                "matchedRows",rows,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"readbackEvidence",readback,"sourceFingerprint",combined);
        Map<String,Object> completionEvidence=Map.of("sourceComplete",true,"returnedRows",rows,"submittedRows",rows,
                "responseEvidence",complete.toString(),"publicationId",intent.id(),"verification",verification);
        if(rows==0){verification=Map.of("passed",true,"writerStopped",true,"expectedRows",0,"actualRows",0,"matchedRows",0,
                "mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"readbackEvidence",readback,"sourceFingerprint",combined);
            completionEvidence=Map.of("sourceComplete",true,"returnedRows",0,"submittedRows",0,"responseEvidence",complete.toString(),"publicationId",intent.id(),"verification",verification);}
        String payload=json.writeValueAsString(completionEvidence);
        for(LocalDate date:dates){var slice=byDate.get(date);var sourcePage=pagesByDate.get(date);var fetched=json.readTree(ledger.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).findFirst().orElseThrow().payloadJson());
            var actualDate=actualWindow.stream().filter(r->r.tradeDate().equals(date)).toList();if(!DcIndexStaging.sameRows(sourcePage.rows(),actualDate))throw new IllegalStateException("D023 per-date published snapshot differs from source receipt at "+date);
            int dateRows=sourcePage.rows().size();String dateFingerprint=sourcePage.sourceFingerprint();String dateEvidence=fetched.path("responseEvidence").asText();
            if(dateRows==0){var current=ledger.get(slice.id());if(current.state()==SyncRunState.FETCHED||current.state()==SyncRunState.IN_DOUBT){
                    var emptyPayload=Map.of("sourceComplete",true,"returnedRows",0,"submittedRows",0,"responseEvidence",dateEvidence,"publicationId",intent.id(),
                            "verification",Map.of("passed",true,"writerStopped",true,"expectedRows",0,"actualRows",0,"matchedRows",0,"mismatchedRows",0,
                                    "duplicateKeys",0,"missingKeys",0,"readbackEvidence",readback,"sourceFingerprint",dateFingerprint));
                    ledger.transition(slice.id(),current.revision(),SyncRunState.VERIFIED_EMPTY,json.writeValueAsString(emptyPayload));
                }else if(current.state()!=SyncRunState.VERIFIED_EMPTY)throw new IllegalStateException("D023 empty source slice is not reconcilable: "+current.state());
            }else{var current=ledger.get(slice.id());if(current.state()==SyncRunState.FETCHED){
                    ledger.transition(slice.id(),current.revision(),SyncRunState.VALIDATED,json.writeValueAsString(Map.of("sourceComplete",true,"returnedRows",dateRows,"responseEvidence",dateEvidence,"sourceFingerprint",dateFingerprint)));current=ledger.get(slice.id());}
                if(current.state()==SyncRunState.SUBMITTED){ledger.transition(slice.id(),current.revision(),SyncRunState.ACKNOWLEDGED,json.writeValueAsString(Map.of("writerStopped",true,"readbackEvidence",readback)));current=ledger.get(slice.id());}
                if(current.state()!=SyncRunState.VERIFIED){if(!Set.of(SyncRunState.VALIDATED,SyncRunState.ACKNOWLEDGED,SyncRunState.IN_DOUBT).contains(current.state()))
                        throw new IllegalStateException("D023 nonempty source slice is not reconcilable: "+current.state());
                    var sliceProof=Map.of("sourceComplete",true,"returnedRows",dateRows,"submittedRows",dateRows,"responseEvidence",dateEvidence,"publicationId",intent.id(),
                            "verification",Map.of("passed",true,"writerStopped",true,"expectedRows",dateRows,"actualRows",dateRows,"matchedRows",dateRows,"mismatchedRows",0,
                                    "duplicateKeys",0,"missingKeys",0,"readbackEvidence",readback,"sourceFingerprint",dateFingerprint));
                    ledger.transition(slice.id(),current.revision(),SyncRunState.VERIFIED,json.writeValueAsString(sliceProof));}}
        }
        var allEntries=ledger.entries(runId,null,1000);var attempts=allEntries.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).toList();if(attempts.size()!=1)throw new IllegalStateException("D023 recovery requires one generic attempt");
        SyncRunState terminal=rows==0?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
        for(String id:List.of(attempts.getFirst().id(),runId)){var entry=ledger.get(id);if(entry.state()==terminal)continue;
            if(entry.state()!=SyncRunState.RUNNING&&entry.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("D023 run/attempt is not reconcilable: "+entry.state());ledger.transition(id,entry.revision(),terminal,payload);}
        var locks=new DatasetIntervalLock(ledgerPath);var interval=new DatasetIntervalLock.Scope("dc_index",from,to);var lease=locks.findOwned(runId,interval);
        if(lease==null)throw new IllegalStateException("D023 verified run lost its owned interval lease before recovery closure");if(!lease.inDoubt()){locks.retainInDoubt(lease);lease=locks.findOwned(runId,interval);}
        if(lease==null||!lease.inDoubt())throw new IllegalStateException("D023 recovery could not retain lease for final exact-readback release");locks.releaseAfterReconciliation(lease,true,true);
    }
    private static Path safeEvidence(Path root,String value)throws Exception{if(value==null||value.isBlank())throw new IllegalStateException("D023 recovery evidence reference absent");Path p=Path.of(value).toAbsolutePath().normalize();if(!p.startsWith(root)||!Files.isRegularFile(p)||!p.toRealPath().startsWith(root)||Files.size(p)>DcIndexSource.MAX_EVIDENCE_BYTES)throw new IllegalStateException("D023 recovery evidence outside run bound");return p;}
    private static Path findStageIntent(Path stageRoot,Path evidenceRoot)throws Exception{
        if(!DcIndexStaging.hasStageIntent(stageRoot))throw new IllegalStateException("D023 stage-only recovery intent is absent");
        Path realRoot=evidenceRoot.toRealPath();Path realFolder=stageRoot.toRealPath();if(!realFolder.startsWith(realRoot))throw new IllegalStateException("D023 stage intent directory escapes run evidence");
        try(var files=Files.newDirectoryStream(stageRoot,"*-intent.json")){Path found=null;for(Path path:files){if(found!=null||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>DcIndexSource.MAX_EVIDENCE_BYTES)
                    throw new IllegalStateException("D023 stage-only intent set is ambiguous or unbounded");found=path;}
            if(found==null||!found.toRealPath().startsWith(realRoot))throw new IllegalStateException("D023 stage-only intent is outside run evidence");return found.toRealPath();}
    }
    private static SyncJobDefinition.FrozenRequest restoreActiveRequest(String frozenJson,String targetId)throws Exception{
        var json=JobDefinitionJson.mapper();JsonNode saved=json.readTree(frozenJson);var definition=json.treeToValue(saved.path("definition"),SyncJobDefinition.class);
        if(!definition.equals(DcIndexSyncJobOwner.DEFINITION))throw new IllegalStateException("D023 active frozen definition changed before recovery");
        Map<String,Object> parameters=json.convertValue(saved.path("parameters"),new TypeReference<LinkedHashMap<String,Object>>(){});
        for(var spec:definition.parameters().entrySet())if(parameters.containsKey(spec.getKey())&&spec.getValue().type()==SyncJobDefinition.ParameterType.DATE)
            parameters.put(spec.getKey(),LocalDate.parse(saved.path("parameters").path(spec.getKey()).asText()));
        LocalDate from=LocalDate.parse(saved.path("from").asText()),to=LocalDate.parse(saved.path("to").asText()),logicalDate=LocalDate.parse(saved.path("logicalDate").asText());
        var request=definition.freeze(SyncJobDefinition.Mode.valueOf(saved.path("mode").asText()),parameters,from,to,logicalDate);
        if(!SyncRequestIdentity.fingerprint(request,targetId).equals(SyncRequestIdentity.fingerprint(frozenJson,targetId)))
            throw new IllegalStateException("D023 active recovery request differs from its immutable frozen identity");
        return request;
    }
    private static List<LocalDate> decodeDates(String value,LocalDate from,LocalDate to){var dates=Arrays.stream(value.split(",",-1)).map(s->LocalDate.parse(s,java.time.format.DateTimeFormatter.BASIC_ISO_DATE)).toList();if(dates.isEmpty()||dates.size()>5||dates.stream().distinct().count()!=dates.size()||!dates.equals(dates.stream().sorted().toList())||dates.stream().anyMatch(d->d.isBefore(from)||d.isAfter(to)))throw new IllegalStateException("D023 frozen trade-date list invalid");return dates;}
    private static String combine(List<String> values)throws Exception{var d=MessageDigest.getInstance("SHA-256");for(String v:values){d.update(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));d.update((byte)0);}return HexFormat.of().formatHex(d.digest());}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
