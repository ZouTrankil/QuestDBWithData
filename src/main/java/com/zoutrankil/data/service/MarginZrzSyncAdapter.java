package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginZrz;
import com.zoutrankil.data.domain.MarginZrzDataset;
import com.zoutrankil.data.domain.MarginZrzKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.repository.MarginZrzStorage;
import com.zoutrankil.data.repository.MarginZrzStaging;
import com.zoutrankil.data.repository.MarginZrzWritePort;
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

/** D031 fetches bounded source chunks, writes only the replacement stage, verifies it, then publishes. */
public final class MarginZrzSyncAdapter implements SyncJobRunner.Adapter<MarginZrz,MarginZrzKey> {
    private final MarginZrzSource source;
    private final MarginZrzWritePort port;
    private final MarginZrzStaging staging;
    private final MarginZrzPublication publication;
    private final Path runEvidence;
    private final JdbcTemplate jdbc;
    private volatile String activeRunId;
    public MarginZrzSyncAdapter(MarginZrzSource source,MarginZrzWritePort port,
            MarginZrzStaging staging,MarginZrzPublication publication,Path runEvidence,JdbcTemplate jdbc) {
        this.source=Objects.requireNonNull(source);this.port=Objects.requireNonNull(port);this.staging=Objects.requireNonNull(staging);
        this.publication=Objects.requireNonNull(publication);this.runEvidence=Objects.requireNonNull(runEvidence).toAbsolutePath().normalize();this.jdbc=Objects.requireNonNull(jdbc);
    }
    @Override public void preflight(FrozenRequest request)throws Exception {
        validateRequest(request);port.preflight();
        var target=new MarginZrzStorage(jdbc,port.formalTable()).snapshot();
        String current=MarginZrzStorage.physicalTargetId(jdbc,port.formalTable(),target.identity());
        if(!current.equals(request.parameters().get("physicalTargetId"))
                ||target.rows().size()!=((Integer)request.parameters().get("targetRowsBefore"))
                ||!target.fingerprint().equals(request.parameters().get("targetFingerprint")))
            throw new IllegalStateException("D031 physical target generation or full snapshot changed after planning");
    }
    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<MarginZrz> consumer,
            BooleanSupplier cancelled)throws Exception {
        String runId=runEvidence.getFileName().toString();activeRunId=runId;
        try(MarginZrzPublication.Operation operation=publication.beginOperation(runId)) {
            check(cancelled);
            String logical=(String)request.parameters().get("targetId"),physical=(String)request.parameters().get("physicalTargetId");
            var page=source.fetch(request.from(),request.to(),cancelled);
            if(page.rows().isEmpty()){
                consumer.accept(page);
                throw new IllegalStateException("D031 source returned an empty window; retired Python owner treats this as unverified, so no stage or checkpoint is published");
            }
            var seen=new HashSet<MarginZrzKey>();
            for(var row:page.rows())if(row.tradeDate().isBefore(request.from())||row.tradeDate().isAfter(request.to())||!seen.add(row.key()))
                throw new IllegalStateException("D031 duplicate natural key or out-of-range provider row");
            var before=new MarginZrzStorage(jdbc,port.formalTable()).snapshot();
            var existingKeys=before.rows().stream().filter(row->!row.tradeDate().isBefore(request.from())&&!row.tradeDate().isAfter(request.to())).map(MarginZrz::key).collect(java.util.stream.Collectors.toSet());
            if(!seen.containsAll(existingKeys))throw new IllegalStateException("D031 source omitted an existing natural date; non-DEDUP replacement has no approved deletion policy");
            var prepared=staging.prepare(port.formalTable(),logical,physical,runId,request,runEvidence,cancelled);
            if(!MarginZrzStorage.sameContent(before,prepared.before()))throw new IllegalStateException("D031 target changed while source was being staged");
            port.useStage(prepared.stage(),prepared.stagePhysicalTarget());
            consumer.accept(page);
            var actual=port.readWindow(prepared.stage(),request.from(),request.to());
            if(!MarginZrzStorage.sameRows(page.rows(),actual))throw new IllegalStateException("D031 stage window all-field readback mismatch");
            var verified=staging.verify(prepared,List.of(page),cancelled);
            var published=publication.publish(operation,verified,cancelled);
            Path completion=runEvidence.resolve("source-complete.json");var body=new LinkedHashMap<String,Object>();
            body.put("jobId",MarginZrzSyncJobOwner.DEFINITION.jobId());body.put("jobVersion",MarginZrzSyncJobOwner.DEFINITION.version());
            body.put("datasetId","margin_zrz");body.put("mode",request.mode());body.put("logicalTargetId",logical);
            body.put("physicalTargetBefore",physical);body.put("publishedPhysicalId",MarginZrzStorage.physicalTargetId(jdbc,port.formalTable(),published.target().identity()));
            body.put("fromInclusive",request.from());body.put("toInclusive",request.to());body.put("logicalDate",request.logicalDate());
            body.put("sourceSlices",verified.sourceReceipts());body.put("sourceRows",page.rows().size());body.put("stageReceipt",verified.receipt());
            body.put("publicationState",published.entry().state());body.put("publicationJournalId",published.entry().intent().id());body.put("complete",true);
            byte[] bytes=JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(body);
            if(bytes.length>2*1024*1024)throw new IllegalStateException("D031 source completion receipt exceeds 2 MiB");
            Files.write(completion,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            return new SyncJobRunner.SourceCompletion(1,page.rows().size(),true,completion.toString());
        }
    }
    @Override public VerifiedBatchExecutor.Codec<MarginZrz,MarginZrzKey> codec(){return MarginZrzWritePort.CODEC;}
    @Override public VerifiedBatchExecutor.Port<MarginZrz,MarginZrzKey> port(){return port;}
    @Override public boolean recoveryRequired(String runId)throws Exception {
        if(MarginZrzStaging.hasStageIntent(runEvidence)){
            var entry=publication.findForRun(runId);return entry.isEmpty()||entry.get().state()!=com.zoutrankil.data.repository.ReferencePublicationJournal.State.VERIFIED;
        }
        return false;
    }
    public static void validateRequest(FrozenRequest request) {
        if(request==null||!request.definition().equals(MarginZrzSyncJobOwner.DEFINITION)
                ||request.definition().datasetVersion()!=MarginZrzDataset.DEFINITION.schemaVersion()
                ||!Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE).contains(request.mode())
                ||request.from()==null||request.to()==null||request.from().isAfter(request.to())||request.to().isAfter(request.logicalDate())
                ||java.time.temporal.ChronoUnit.DAYS.between(request.from(),request.to())+1>MarginZrzSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("Frozen bounded D031 request required");
        Map<String,Object> p=request.parameters();Set<String> allowed=Set.of("targetId","physicalTargetId","targetFingerprint","targetRowsBefore",
                "targetMinBefore","targetMaxBefore","checkpointAnchor","checkpointBefore");
        if(!p.keySet().containsAll(Set.of("targetId","physicalTargetId","targetFingerprint","targetRowsBefore"))||!allowed.containsAll(p.keySet()))
            throw new IllegalArgumentException("Unexpected/missing D031 frozen target and checkpoint fields");
        for(String field:List.of("targetId","physicalTargetId"))if(!(p.get(field) instanceof String value)||!value.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D031 target identity required");
        if(!(p.get("targetFingerprint") instanceof String fingerprint)||!fingerprint.matches("[0-9a-f]{64}")
                ||!(p.get("targetRowsBefore") instanceof Integer rows)||rows<0)throw new IllegalArgumentException("Frozen D031 full target baseline required");
        for(String field:List.of("targetMinBefore","targetMaxBefore","checkpointAnchor","checkpointBefore"))
            if(p.get(field)!=null&&!(p.get(field) instanceof LocalDate))throw new IllegalArgumentException("Invalid D031 frozen date parameter");
        if((p.get("targetMinBefore")==null)!=(p.get("targetMaxBefore")==null))throw new IllegalArgumentException("D031 frozen target bounds must be paired");
        if(request.mode()==Mode.INCREMENTAL){if(!(p.get("checkpointAnchor") instanceof LocalDate anchor)||request.from().isBefore(anchor)
                    ||p.get("checkpointBefore")!=null&&(!(p.get("checkpointBefore") instanceof LocalDate before)||before.isBefore(anchor)
                            ||before.isAfter(request.to())||!request.from().equals(before.minusDays(MarginZrzSyncJobOwner.REVISION_DAYS).isBefore(anchor)?anchor:before.minusDays(MarginZrzSyncJobOwner.REVISION_DAYS))))
                throw new IllegalArgumentException("D031 incremental requires explicit frozen anchor/checkpoint");}
        else if(p.get("checkpointAnchor")!=null||p.get("checkpointBefore")!=null)throw new IllegalArgumentException("D031 non-incremental request cannot carry checkpoint fields");
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("D031 cancelled between bounded source slices");}
}
