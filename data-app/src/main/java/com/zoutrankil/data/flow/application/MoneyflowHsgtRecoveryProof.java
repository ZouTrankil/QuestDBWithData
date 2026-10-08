package com.zoutrankil.data.flow.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.SyncJobRunner;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Read-only recovery authority, checked before any remaining physical publication mutation. */
final class MoneyflowHsgtRecoveryProof {
    private MoneyflowHsgtRecoveryProof() {}
    record Slice(SyncRunLedger.Entry entry, JsonNode fetched, SyncJobRunner.Page<MoneyflowHsgt> page) {}

    static SyncJobDefinition.FrozenRequest request(SyncRunLedger.Run run) throws Exception {
        var json=JobDefinitionJson.mapper();var frozen=json.readTree(run.frozenJson());
        var definition=json.treeToValue(frozen.path("definition"),SyncJobDefinition.class);
        if(!MoneyflowHsgtSyncJobOwner.DEFINITION.equals(definition)||!definition.jobId().equals(run.jobId())||definition.version()!=run.jobVersion())
            throw new IllegalStateException("D027 frozen recovery owner/definition differs");
        Map<String,Object> parameters=json.convertValue(frozen.path("parameters"),new TypeReference<LinkedHashMap<String,Object>>(){});
        for(var spec:definition.parameters().entrySet())if(parameters.containsKey(spec.getKey())&&spec.getValue().type()==SyncJobDefinition.ParameterType.DATE)
            parameters.put(spec.getKey(),LocalDate.parse(frozen.path("parameters").path(spec.getKey()).asText()));
        var request=definition.freeze(SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText()),parameters,
                LocalDate.parse(frozen.path("from").asText()),LocalDate.parse(frozen.path("to").asText()),LocalDate.parse(frozen.path("logicalDate").asText()));
        if(!run.logicalDate().equals(request.logicalDate().toString())||!run.targetId().equals(parameters.get("targetId"))
                ||!SyncRequestIdentity.fingerprint(request,run.targetId()).equals(SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId())))
            throw new IllegalStateException("D027 frozen recovery request/target differs");
        return request;
    }

    static boolean fetchedEmpty(SyncRunState state,JsonNode fetched) {
        JsonNode count=fetched.path("returnedRows");
        return state==SyncRunState.FETCHED&&count.isIntegralNumber()&&count.canConvertToInt()&&count.intValue()==0;
    }

    static List<Slice> slices(Path ledgerPath,String runId,SyncJobDefinition.FrozenRequest request)throws Exception {
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);var parent=ledger.get(runId);
        var restored=request(run);
        if(!runId.equals(run.id())||!SyncRequestIdentity.fingerprint(request,run.targetId()).equals(SyncRequestIdentity.fingerprint(restored,run.targetId()))||parent.kind()!=SyncRunLedger.Kind.RUN
                ||!runId.equals(parent.runId())||parent.parentId()!=null||!recoverableParent(parent.state()))
            throw new IllegalStateException("D027 recovery run authority differs");
        var children=ledger.entries(runId,null,1000);
        if(children.size()==1000)throw new IllegalStateException("D027 child inventory exceeds bound");
        var attempts=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).toList();
        var slices=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        if(attempts.size()!=1||slices.isEmpty()||slices.size()>MoneyflowHsgtSyncJobOwner.MAX_SOURCE_SLICES
                ||children.size()!=slices.size()+2||!runId.equals(attempts.getFirst().parentId())||!runId.equals(attempts.getFirst().runId())
                ||!recoverableParent(attempts.getFirst().state()))
            throw new IllegalStateException("D027 recovery requires one owned attempt and bounded source slices");
        requireLatestEvent(ledger,parent);requireLatestEvent(ledger,attempts.getFirst());
        Path root=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).toRealPath();
        var result=new ArrayList<Slice>();
        for(var slice:slices){
            if(!runId.equals(slice.runId())||!attempts.getFirst().id().equals(slice.parentId()))
                throw new IllegalStateException("D027 source slice belongs to another run/attempt");
            var events=ledger.events(slice.id(),-1,100);
            if(events.size()==100)throw new IllegalStateException("D027 slice event inventory exceeds bound");
            var fetchedEvents=events.stream().filter(e->e.state()==SyncRunState.FETCHED).toList();
            if(fetchedEvents.size()!=1)throw new IllegalStateException("D027 slice requires one immutable fetched receipt");
            var fetched=JobDefinitionJson.mapper().readTree(fetchedEvents.getFirst().payloadJson());
            if(slice.state()!=SyncRunState.VERIFIED&&slice.state()!=SyncRunState.VERIFIED_EMPTY&&!fetchedEmpty(slice.state(),fetched))
                throw new IllegalStateException("D027 stage is incomplete; retain it for explicit reconciliation");
            var latest=events.getLast();
            if(latest.revision()!=slice.revision()||latest.state()!=slice.state())
                throw new IllegalStateException("D027 slice state differs from its immutable event history");
            String[] bounds=fetched.path("cursor").asText().split("\\.\\.",-1);
            if(bounds.length!=2)throw new IllegalStateException("D027 frozen source cursor invalid");
            LocalDate from=LocalDate.parse(bounds[0],DateTimeFormatter.BASIC_ISO_DATE),to=LocalDate.parse(bounds[1],DateTimeFormatter.BASIC_ISO_DATE);
            Path receipt=Path.of(fetched.path("responseEvidence").asText()).toAbsolutePath().normalize();
            if(Files.isSymbolicLink(receipt)||!Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS)||!receipt.toRealPath().startsWith(root.resolve("source")))
                throw new IllegalStateException("D027 receipt outside owning source folder");
            var page=MoneyflowHsgtSource.reopen(receipt,fetched.path("sourceFingerprint").asText(),from,to);
            JsonNode count=fetched.path("returnedRows");
            if(!count.isIntegralNumber()||!count.canConvertToInt()||count.intValue()!=page.rows().size()
                    ||slice.state()==SyncRunState.VERIFIED&&page.rows().isEmpty()
                    ||slice.state()==SyncRunState.VERIFIED_EMPTY&&!page.rows().isEmpty())
                throw new IllegalStateException("D027 fetched count/state differs from immutable source");
            result.add(new Slice(slice,fetched,page));
        }
        result.sort(Comparator.comparing(s->s.page().cursor()));LocalDate next=request.from();
        for(var slice:result){String[] bounds=slice.page().cursor().split("\\.\\.",-1);
            LocalDate from=LocalDate.parse(bounds[0],DateTimeFormatter.BASIC_ISO_DATE),to=LocalDate.parse(bounds[1],DateTimeFormatter.BASIC_ISO_DATE);
            if(!from.equals(next)||to.isAfter(request.to()))throw new IllegalStateException("D027 recovery slices do not cover the frozen window");next=to.plusDays(1);
        }
        if(!next.equals(request.to().plusDays(1)))throw new IllegalStateException("D027 recovery slices do not cover the frozen window");
        return List.copyOf(result);
    }

    /** Called only after the existing journal/raw/physical proof validation has succeeded. */
    static List<Slice> publication(Path ledgerPath,ReferencePublicationJournal.Entry entry,String logicalTargetId)throws Exception {
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(entry.intent().runId());var request=request(run);
        var json=JobDefinitionJson.mapper();var scope=json.readTree(entry.intent().scope());
        var proof=json.readTree(FileEvidenceStore.readBounded(Path.of(scope.path("stageReceipt").asText()),4*1024*1024,
                ()->new IllegalStateException("D027 bounded stage proof required")));
        String fingerprint=SyncRequestIdentity.fingerprint(request,run.targetId());
        if(!"moneyflow_hsgt".equals(entry.intent().dataset())||!logicalTargetId.equals(run.targetId())
                ||!run.targetId().equals(proof.path("logicalTargetId").asText())||!fingerprint.equals(scope.path("requestFingerprint").asText())
                ||!fingerprint.equals(proof.path("requestFingerprint").asText())
                ||!Objects.equals(request.parameters().get("physicalTargetId"),entry.intent().initialTarget())
                ||!entry.intent().initialTarget().equals(proof.path("physicalTargetBefore").asText())
                ||!request.from().toString().equals(scope.path("windowFrom").asText())||!request.to().toString().equals(scope.path("windowTo").asText())
                ||!request.from().toString().equals(proof.path("windowFrom").asText())||!request.to().toString().equals(proof.path("windowTo").asText()))
            throw new IllegalStateException("D027 journal source window differs from its frozen owning run");
        var slices=slices(ledgerPath,run.id(),request);var refs=proof.path("sourceReceipts");
        if(!refs.isArray()||refs.size()!=slices.size())throw new IllegalStateException("D027 stage/source slice inventory differs");
        int count=0;
        for(int i=0;i<slices.size();i++){
            var page=slices.get(i).page();var ref=refs.get(i);String[] bounds=page.cursor().split("\\.\\.",-1);
            if(!LocalDate.parse(bounds[0],DateTimeFormatter.BASIC_ISO_DATE).toString().equals(ref.path("fromInclusive").asText())
                    ||!LocalDate.parse(bounds[1],DateTimeFormatter.BASIC_ISO_DATE).toString().equals(ref.path("toInclusive").asText())
                    ||!ref.path("rows").isIntegralNumber()||!ref.path("rows").canConvertToInt()||ref.path("rows").intValue()!=page.rows().size()
                    ||!page.sourceFingerprint().equals(ref.path("sourceFingerprint").asText())
                    ||!Path.of(page.responseEvidence()).toRealPath().equals(Path.of(ref.path("responseEvidence").asText()).toRealPath()))
                throw new IllegalStateException("D027 stage proof differs from immutable fetched slice");
            count=Math.addExact(count,page.rows().size());
        }
        for(JsonNode total:List.of(proof.path("sourceRows"),scope.path("sourceRows")))
            if(!total.isIntegralNumber()||!total.canConvertToInt()||total.intValue()!=count)throw new IllegalStateException("D027 recovery source row totals differ");
        return slices;
    }
    private static boolean recoverableParent(SyncRunState state){return Set.of(SyncRunState.RUNNING,SyncRunState.ACKNOWLEDGED,SyncRunState.IN_DOUBT,SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(state);}
    private static void requireLatestEvent(SyncRunLedger ledger,SyncRunLedger.Entry entry)throws Exception {
        var events=ledger.events(entry.id(),-1,100);
        if(events.isEmpty()||events.size()==100||events.getLast().revision()!=entry.revision()||events.getLast().state()!=entry.state())
            throw new IllegalStateException("D027 recovery parent state differs from immutable event history");
    }
}
