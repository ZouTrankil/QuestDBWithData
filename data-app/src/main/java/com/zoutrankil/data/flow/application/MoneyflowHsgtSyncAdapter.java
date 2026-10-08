package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.calendar.port.SseCalendarWindowReadPort;

import com.zoutrankil.data.flow.domain.MoneyflowHsgtRows;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MoneyflowHsgt;
import com.zoutrankil.data.domain.MoneyflowHsgtDataset;
import com.zoutrankil.data.domain.MoneyflowHsgtKey;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import java.nio.file.Path;
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
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** D027 fetches bounded source chunks, writes only the replacement stage, verifies it, then publishes. */
public final class MoneyflowHsgtSyncAdapter implements SyncJobRunner.Adapter<MoneyflowHsgt,MoneyflowHsgtKey> {
    private final MoneyflowHsgtSource source;
    private final MoneyflowHsgtWriteSession port;
    private final MoneyflowHsgtStaging staging;
    private final MoneyflowHsgtPublication publication;
    private final Path runEvidence;
    private final MoneyflowHsgtTables tables;
    private final SseCalendarWindowReadPort calendars;
    private volatile String activeRunId;
    public MoneyflowHsgtSyncAdapter(MoneyflowHsgtSource source,MoneyflowHsgtWriteSession port,
            MoneyflowHsgtStaging staging,MoneyflowHsgtPublication publication,Path runEvidence,MoneyflowHsgtTables tables,SseCalendarWindowReadPort calendars) {
        this.source=Objects.requireNonNull(source);this.port=Objects.requireNonNull(port);this.staging=Objects.requireNonNull(staging);
        this.publication=Objects.requireNonNull(publication);this.runEvidence=Objects.requireNonNull(runEvidence).toAbsolutePath().normalize();this.tables=Objects.requireNonNull(tables);this.calendars=Objects.requireNonNull(calendars);
    }
    @Override public void preflight(FrozenRequest request)throws Exception {
        validateRequest(request);port.preflight();
        var target=tables.open(port.formalTable()).snapshot();
        String current=tables.physicalTargetId(port.formalTable(),target.identity());
        if(!current.equals(request.parameters().get("physicalTargetId"))
                ||target.rows().size()!=((Integer)request.parameters().get("targetRowsBefore"))
                ||!target.fingerprint().equals(request.parameters().get("targetFingerprint")))
            throw new IllegalStateException("D027 physical target generation or full snapshot changed after planning");
    }
    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<MoneyflowHsgt> consumer,
            BooleanSupplier cancelled)throws Exception {
        String runId=runEvidence.getFileName().toString();activeRunId=runId;
        try(MoneyflowHsgtPublication.Operation operation=publication.beginOperation(runId)) {
            check(cancelled);
            String logical=(String)request.parameters().get("targetId"),physical=(String)request.parameters().get("physicalTargetId");
            // Formal admission proves calendar coverage before any temporary table is created.
            // Its explicit 31-day bound fits one existing source request.
            SyncJobRunner.Page<MoneyflowHsgt> formalPage=null;
            if("moneyflow_hsgt".equals(port.formalTable())){
                if(java.time.temporal.ChronoUnit.DAYS.between(request.from(),request.to())>=MoneyflowHsgtSource.MAX_RANGE_DAYS)
                    throw new IllegalArgumentException("Formal northbound request exceeds 31-day source bound");
                formalPage=source.fetch(request.from(),request.to(),cancelled);
                requireFormalCalendarCoverage(request.from(),request.to(),formalPage.rows());
            }
            var prepared=staging.prepare(port.formalTable(),logical,physical,runId,request,runEvidence,cancelled);
            port.useStage(prepared.stage(),prepared.stagePhysicalTarget());
            var collected=new ArrayList<SyncJobRunner.Page<MoneyflowHsgt>>();var seen=new HashSet<MoneyflowHsgtKey>();
            int rowCount=0;LocalDate from=request.from();
            while(!from.isAfter(request.to())) {
                check(cancelled);LocalDate to=from.plusDays(MoneyflowHsgtSource.MAX_RANGE_DAYS-1L);
                if(to.isAfter(request.to()))to=request.to();
                var page=formalPage==null?source.fetch(from,to,cancelled):formalPage;
                for(var row:page.rows())if(!seen.add(row.key()))throw new IllegalStateException("D027 duplicate natural trade date across source slices");
                consumer.accept(page);
                var actual=port.readWindow(prepared.stage(),from,to);
                if(!MoneyflowHsgtRows.sameRows(page.rows(),actual))throw new IllegalStateException("D027 stage slice all-field readback mismatch");
                collected.add(page);rowCount=Math.addExact(rowCount,page.rows().size());from=to.plusDays(1);
            }
            var verified=staging.verify(prepared,collected,cancelled);
            var published=publication.publish(operation,verified,cancelled);
            Path completion=runEvidence.resolve("source-complete.json");var body=new LinkedHashMap<String,Object>();
            body.put("jobId",MoneyflowHsgtSyncJobOwner.DEFINITION.jobId());body.put("jobVersion",MoneyflowHsgtSyncJobOwner.DEFINITION.version());
            body.put("datasetId","moneyflow_hsgt");body.put("mode",request.mode());body.put("logicalTargetId",logical);
            body.put("physicalTargetBefore",physical);body.put("publishedPhysicalId",tables.physicalTargetId(port.formalTable(),published.target().identity()));
            body.put("fromInclusive",request.from());body.put("toInclusive",request.to());body.put("logicalDate",request.logicalDate());
            body.put("sourceSlices",verified.sourceReceipts());body.put("sourceRows",rowCount);body.put("stageReceipt",verified.receipt());
            body.put("publicationState",published.entry().state());body.put("publicationJournalId",published.entry().intent().id());body.put("complete",true);
            byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(body);
            if(bytes.length>2*1024*1024)throw new IllegalStateException("D027 source completion receipt exceeds 2 MiB");
            FileEvidenceStore.writeNew(completion,bytes);
            return new SyncJobRunner.SourceCompletion(collected.size(),rowCount,true,completion.toString());
        }
    }
    @Override public VerifiedBatchExecutor.Codec<MoneyflowHsgt,MoneyflowHsgtKey> codec(){return port.codec();}
    @Override public VerifiedBatchExecutor.Port<MoneyflowHsgt,MoneyflowHsgtKey> port(){return port;}
    @Override public boolean recoveryRequired(String runId)throws Exception {
        var entry=publication.findForRun(runId);
        if(entry.isPresent())return entry.get().state()!=com.zoutrankil.data.repository.ReferencePublicationJournal.State.VERIFIED
                ||!publication.runClosedSuccessfully(runId);
        if(MoneyflowHsgtStaging.hasStageIntent(runEvidence)){
            return true;
        }
        return false;
    }
    private static void validateRequest(FrozenRequest request) {
        if(request==null||!request.definition().equals(MoneyflowHsgtSyncJobOwner.DEFINITION)
                ||request.definition().datasetVersion()!=MoneyflowHsgtDataset.DEFINITION.schemaVersion()
                ||!Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE).contains(request.mode())
                ||request.from()==null||request.to()==null||request.from().isAfter(request.to())||request.to().isAfter(request.logicalDate())
                ||java.time.temporal.ChronoUnit.DAYS.between(request.from(),request.to())+1>MoneyflowHsgtSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("Frozen bounded D027 request required");
        Map<String,Object> p=request.parameters();Set<String> allowed=Set.of("targetId","physicalTargetId","targetFingerprint","targetRowsBefore",
                "targetMinBefore","targetMaxBefore","checkpointAnchor","checkpointBefore");
        if(!p.keySet().containsAll(Set.of("targetId","physicalTargetId","targetFingerprint","targetRowsBefore"))||!allowed.containsAll(p.keySet()))
            throw new IllegalArgumentException("Unexpected/missing D027 frozen target and checkpoint fields");
        for(String field:List.of("targetId","physicalTargetId"))if(!(p.get(field) instanceof String value)||!value.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D027 target identity required");
        if(!(p.get("targetFingerprint") instanceof String fingerprint)||!fingerprint.matches("[0-9a-f]{64}")
                ||!(p.get("targetRowsBefore") instanceof Integer rows)||rows<0)throw new IllegalArgumentException("Frozen D027 full target baseline required");
        for(String field:List.of("targetMinBefore","targetMaxBefore","checkpointAnchor","checkpointBefore"))
            if(p.get(field)!=null&&!(p.get(field) instanceof LocalDate))throw new IllegalArgumentException("Invalid D027 frozen date parameter");
        if((p.get("targetMinBefore")==null)!=(p.get("targetMaxBefore")==null))throw new IllegalArgumentException("D027 frozen target bounds must be paired");
        if(request.mode()==Mode.INCREMENTAL){if(!(p.get("checkpointAnchor") instanceof LocalDate anchor)||request.from().isBefore(anchor)
                    ||p.get("checkpointBefore")!=null&&(!(p.get("checkpointBefore") instanceof LocalDate before)||before.isBefore(anchor)))
                throw new IllegalArgumentException("D027 incremental requires explicit frozen anchor/checkpoint");}
        else if(p.get("checkpointAnchor")!=null||p.get("checkpointBefore")!=null)throw new IllegalArgumentException("D027 non-incremental request cannot carry checkpoint fields");
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("D027 cancelled between bounded source slices");}

    /** A short provider response alone cannot certify missing aggregate dates in the formal product. */
    private void requireFormalCalendarCoverage(LocalDate from,LocalDate to,List<MoneyflowHsgt> rows){
        var natural=new HashSet<LocalDate>();var expected=new HashSet<LocalDate>();
        calendars.readSseDates(from,to,(date,open)->{
            if(!natural.add(date))throw new IllegalStateException("Duplicate SSE calendar business date");
            if(open==1)expected.add(date);
        });
        for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1))
            if(!natural.contains(date))throw new IllegalStateException("Missing SSE calendar coverage on "+date);
        var returned=new HashSet<LocalDate>();for(var row:rows)returned.add(row.tradeDate());
        expected.removeAll(returned);
        if(!expected.isEmpty())throw new IllegalStateException("SOURCE_INCOMPLETE: formal northbound aggregate lacks SSE open dates "+expected+"; confirm cross-border source availability before publication");
    }
}
