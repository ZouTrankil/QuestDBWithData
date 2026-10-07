package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginAll;
import com.zoutrankil.data.domain.MarginAllDataset;
import com.zoutrankil.data.domain.MarginAllKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.repository.MarginAllStorage;
import com.zoutrankil.data.repository.MarginAllStaging;
import com.zoutrankil.data.repository.MarginAllWritePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** D028 fetches bounded source chunks, writes only the replacement stage, verifies it, then publishes. */
public final class MarginAllSyncAdapter implements SyncJobRunner.Adapter<MarginAll,MarginAllKey> {
    private final MarginAllSource source;
    private final MarginAllWritePort port;
    private final MarginAllStaging staging;
    private final MarginAllPublication publication;
    private final Path runEvidence;
    private final JdbcTemplate jdbc;
    private volatile String activeRunId;
    public MarginAllSyncAdapter(MarginAllSource source,MarginAllWritePort port,
            MarginAllStaging staging,MarginAllPublication publication,Path runEvidence,JdbcTemplate jdbc) {
        this.source=Objects.requireNonNull(source);this.port=Objects.requireNonNull(port);this.staging=Objects.requireNonNull(staging);
        this.publication=Objects.requireNonNull(publication);this.runEvidence=Objects.requireNonNull(runEvidence).toAbsolutePath().normalize();this.jdbc=Objects.requireNonNull(jdbc);
    }
    @Override public void preflight(FrozenRequest request)throws Exception {
        validateRequest(request);port.preflight();
        var target=new MarginAllStorage(jdbc,port.formalTable()).snapshot();
        String current=MarginAllStorage.physicalTargetId(jdbc,port.formalTable(),target.identity());
        if(!current.equals(request.parameters().get("physicalTargetId"))
                ||target.rows().size()!=((Integer)request.parameters().get("targetRowsBefore"))
                ||!target.fingerprint().equals(request.parameters().get("targetFingerprint")))
            throw new IllegalStateException("D028 physical target generation or full snapshot changed after planning");
    }
    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<MarginAll> consumer,
            BooleanSupplier cancelled)throws Exception {
        String runId=runEvidence.getFileName().toString();activeRunId=runId;
        try(MarginAllPublication.Operation operation=publication.beginOperation(runId)) {
            check(cancelled);
            String logical=(String)request.parameters().get("targetId"),physical=(String)request.parameters().get("physicalTargetId");
            var prepared=staging.prepare(port.formalTable(),logical,physical,runId,request,runEvidence,cancelled);
            port.useStage(prepared.stage(),prepared.stagePhysicalTarget());
            var collected=new ArrayList<SyncJobRunner.Page<MarginAll>>();var seen=new HashSet<MarginAllKey>();
            int rowCount=0;LocalDate from=request.from();
            while(!from.isAfter(request.to())) {
                check(cancelled);LocalDate sliceDate=from;var page=source.fetch(sliceDate,cancelled);
                for(var row:page.rows())if(!row.tradeDate().equals(sliceDate)||!seen.add(row.key()))throw new IllegalStateException("D028 duplicate natural key or out-of-date source row across slices");
                var preexisting=prepared.before().rows().stream().filter(row->row.tradeDate().equals(sliceDate)).map(MarginAll::key).collect(java.util.stream.Collectors.toSet());
                var sourceKeys=page.rows().stream().map(MarginAll::key).collect(java.util.stream.Collectors.toSet());
                if(!sourceKeys.containsAll(preexisting))throw new IllegalStateException("D028 source omitted an existing natural key; non-DEDUP replacement has no approved deletion policy");
                consumer.accept(page);
                var actual=port.readWindow(prepared.stage(),sliceDate,sliceDate);
                if(!MarginAllStorage.sameRows(page.rows(),actual))throw new IllegalStateException("D028 stage slice all-field readback mismatch");
                collected.add(page);rowCount=Math.addExact(rowCount,page.rows().size());from=sliceDate.plusDays(1);
            }
            var verified=staging.verify(prepared,collected,cancelled);
            var published=publication.publish(operation,verified,cancelled);
            Path completion=runEvidence.resolve("source-complete.json");var body=new LinkedHashMap<String,Object>();
            body.put("jobId",MarginAllSyncJobOwner.DEFINITION.jobId());body.put("jobVersion",MarginAllSyncJobOwner.DEFINITION.version());
            body.put("datasetId","margin_all");body.put("mode",request.mode());body.put("logicalTargetId",logical);
            body.put("physicalTargetBefore",physical);body.put("publishedPhysicalId",MarginAllStorage.physicalTargetId(jdbc,port.formalTable(),published.target().identity()));
            body.put("fromInclusive",request.from());body.put("toInclusive",request.to());body.put("logicalDate",request.logicalDate());
            body.put("sourceSlices",verified.sourceReceipts());body.put("sourceRows",rowCount);body.put("stageReceipt",verified.receipt());
            body.put("publicationState",published.entry().state());body.put("publicationJournalId",published.entry().intent().id());body.put("complete",true);
            byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(body);
            if(bytes.length>2*1024*1024)throw new IllegalStateException("D028 source completion receipt exceeds 2 MiB");
            FileEvidenceStore.writeNew(completion,bytes);
            return new SyncJobRunner.SourceCompletion(collected.size(),rowCount,true,completion.toString());
        }
    }
    @Override public VerifiedBatchExecutor.Codec<MarginAll,MarginAllKey> codec(){return MarginAllWritePort.CODEC;}
    @Override public VerifiedBatchExecutor.Port<MarginAll,MarginAllKey> port(){return port;}
    @Override public boolean recoveryRequired(String runId)throws Exception {
        if(MarginAllStaging.hasStageIntent(runEvidence)){
            var entry=publication.findForRun(runId);return entry.isEmpty()||entry.get().state()!=com.zoutrankil.data.repository.ReferencePublicationJournal.State.VERIFIED;
        }
        return false;
    }
    public static void validateRequest(FrozenRequest request) {
        if(request==null||!request.definition().equals(MarginAllSyncJobOwner.DEFINITION)
                ||request.definition().datasetVersion()!=MarginAllDataset.DEFINITION.schemaVersion()
                ||!Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE).contains(request.mode())
                ||request.from()==null||request.to()==null||request.from().isAfter(request.to())||request.to().isAfter(request.logicalDate())
                ||java.time.temporal.ChronoUnit.DAYS.between(request.from(),request.to())+1>MarginAllSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("Frozen bounded D028 request required");
        Map<String,Object> p=request.parameters();Set<String> allowed=Set.of("targetId","physicalTargetId","targetFingerprint","targetRowsBefore",
                "targetMinBefore","targetMaxBefore","checkpointAnchor","checkpointBefore");
        if(!p.keySet().containsAll(Set.of("targetId","physicalTargetId","targetFingerprint","targetRowsBefore"))||!allowed.containsAll(p.keySet()))
            throw new IllegalArgumentException("Unexpected/missing D028 frozen target and checkpoint fields");
        for(String field:List.of("targetId","physicalTargetId"))if(!(p.get(field) instanceof String value)||!value.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D028 target identity required");
        if(!(p.get("targetFingerprint") instanceof String fingerprint)||!fingerprint.matches("[0-9a-f]{64}")
                ||!(p.get("targetRowsBefore") instanceof Integer rows)||rows<0)throw new IllegalArgumentException("Frozen D028 full target baseline required");
        for(String field:List.of("targetMinBefore","targetMaxBefore","checkpointAnchor","checkpointBefore"))
            if(p.get(field)!=null&&!(p.get(field) instanceof LocalDate))throw new IllegalArgumentException("Invalid D028 frozen date parameter");
        if((p.get("targetMinBefore")==null)!=(p.get("targetMaxBefore")==null))throw new IllegalArgumentException("D028 frozen target bounds must be paired");
        if(request.mode()==Mode.INCREMENTAL){if(!(p.get("checkpointAnchor") instanceof LocalDate anchor)||request.from().isBefore(anchor)
                    ||p.get("checkpointBefore")!=null&&(!(p.get("checkpointBefore") instanceof LocalDate before)||before.isBefore(anchor)
                            ||before.isAfter(request.to())||!request.from().equals(before.minusDays(MarginAllSyncJobOwner.REVISION_DAYS).isBefore(anchor)?anchor:before.minusDays(MarginAllSyncJobOwner.REVISION_DAYS))))
                throw new IllegalArgumentException("D028 incremental requires explicit frozen anchor/checkpoint");}
        else if(p.get("checkpointAnchor")!=null||p.get("checkpointBefore")!=null)throw new IllegalArgumentException("D028 non-incremental request cannot carry checkpoint fields");
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("D028 cancelled between bounded source slices");}
}
