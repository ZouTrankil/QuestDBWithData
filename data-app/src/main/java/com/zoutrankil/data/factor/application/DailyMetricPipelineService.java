package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.SyncJobRunner;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Durable native pipeline; business outcomes are returned without CLI exceptions or console parsing. */
public final class DailyMetricPipelineService {
    public record Request(LocalDate date, LocalDate asOf, List<Path> observations,
                          Path pools, String label, Path root) {
        public Request {
            Objects.requireNonNull(date); Objects.requireNonNull(asOf);
            Objects.requireNonNull(pools); Objects.requireNonNull(root);
            observations=List.copyOf(observations);
            if(asOf.isBefore(date) || observations.isEmpty() || label==null || label.isBlank())
                throw new IllegalArgumentException("Dated observations, pools and label required");
        }
    }
    public record Result(Path receipt, SyncJobRunner.Result publication) {}
    private final DailyMetricComputation computation;
    private final DailyMetricPublicationService publication;
    public DailyMetricPipelineService(DailyMetricComputation computation, DailyMetricPublicationService publication) {
        this.computation=Objects.requireNonNull(computation);
        this.publication=Objects.requireNonNull(publication);
    }
    public Result run(Request request)throws Exception {
        return run(request,null,null);
    }
    /** Inspect physical published rows against a caller-declared matrix, never inferred from table contents. */
    public void inspectPublished(Result result,
            com.zoutrankil.data.factor.storage.QuestDbDailyMetricFreshness.Request freshnessRequest,
            com.zoutrankil.data.factor.storage.QuestDbDailyMetricFreshness reader)throws Exception {
        var json=JobDefinitionJson.mapper();
        var state=json.readValue(FileEvidenceStore.readBounded(result.receipt(),1024*1024,
                ()->new IllegalStateException("Metric receipt exceeds limit")),
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        var signal=LocalDate.parse(Objects.toString(state.get("signalDate")));
        if(!freshnessRequest.asOf().toString().equals(state.get("asOf"))
                ||!freshnessRequest.labelDefinition().equals(state.get("labelDefinition"))
                || freshnessRequest.signalDate()!=null&&!signal.equals(freshnessRequest.signalDate()))
            throw new IllegalArgumentException("Freshness dates differ from published metric run");
        if(!Boolean.TRUE.equals(state.get("publishedRowsVerified")))
            throw new IllegalStateException("Metric publication must physically verify before freshness inspection");
        state.put("freshnessRequest",freshnessRequest);state.put("freshnessStatus","CHECKING");save(result.receipt(),state);
        try {
            var checked=reader.inspect(new com.zoutrankil.data.factor.storage.QuestDbDailyMetricFreshness.Request(
                    signal,freshnessRequest.asOf(),freshnessRequest.labelDefinition(),freshnessRequest.expected()));
            state.put("freshness",checked);state.put("freshnessStatus",checked.assessment().status());
            state.put("pageSnapshotRefreshed",false);
            save(result.receipt(),state);
            if(!"fresh".equals(checked.assessment().status()))
                throw new IllegalStateException("Published metrics did not satisfy declared freshness: "+checked.assessment().status());
        } catch(Exception failure) {
            state.put("freshnessStatus","FAILED_REQUIRES_INSPECTION");state.put("freshnessFailureType",failure.getClass().getName());
            save(result.receipt(),state);throw failure;
        }
    }
    public Result runLatest(LocalDate asOf,List<Path> observations,Path pools,String label,Path root,
            com.zoutrankil.data.factor.storage.QuestDbDailyMetricPlan planner)throws Exception {
        var plan=Objects.requireNonNull(planner).plan(null,asOf);
        return run(new Request(plan.pairs().getLast().signal(),asOf,observations,pools,label,root),plan,planner);
    }
    private Result run(Request request,com.zoutrankil.data.factor.storage.QuestDbDailyMetricPlan.Plan plan,
            com.zoutrankil.data.factor.storage.QuestDbDailyMetricPlan planner)throws Exception {
        LocalDate date=request.date();
        Path root=request.root().toAbsolutePath().normalize();Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("daily-metric-pipeline.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lease=channel.tryLock()) {
            if(lease==null)throw new IllegalStateException("Another daily metric pipeline owns this root");
            String executionId=date+"-"+UUID.randomUUID(),runId="factor-daily-metric-"+UUID.randomUUID();
            Path run=root.resolve(executionId);Files.createDirectory(run);
            Path calculated=run.resolve("computation.json"),receipt=run.resolve("pipeline.json");
            var state=new LinkedHashMap<String,Object>();
            state.put("schema","java.factor_daily_metric_pipeline.v1");state.put("executionId",executionId);
            state.put("signalDate",date);state.put("asOf",request.asOf().toString());
            state.put("labelDefinition",request.label());
            if(plan!=null)state.put("maturityPlan",plan);
            state.put("publicationRunId",runId);state.put("computation",calculated.toString());
            state.put("sourceComplete",false);state.put("factorReady",false);
            state.put("phase","COMPUTING");save(receipt,state);
            try {
                if(plan!=null)planner.requireUnchanged(plan);
                var observations=request.observations();
                computation.compute(new DailyMetricComputation.Request(date,
                        request.asOf(),observations,
                        request.pools(),request.label(),calculated));
                var calculation=JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(calculated,
                        32*1024*1024,()->new IllegalStateException("Metric computation exceeds limit")));
                if(!calculation.path("sourceComplete").isBoolean() || !calculation.path("publicationReport").isObject()
                        || !calculation.path("rows").isArray())
                    throw new IllegalStateException("Metric computation completion evidence missing");
                state.put("sourceComplete",calculation.path("sourceComplete").booleanValue());
                state.put("publicationReport",calculation.path("publicationReport"));
                state.put("expectedRows",calculation.path("rows").size());
                var incompleteReasons=new ArrayList<String>();
                if(!calculation.path("sourceComplete").booleanValue())incompleteReasons.add("SOURCE_NOT_CERTIFIED_COMPLETE");
                if(!calculation.path("publicationReport").path("reusableCheckpoint").asBoolean(false))
                    incompleteReasons.add("METRIC_MATRIX_NOT_REUSABLE");
                state.put("incompleteReasons",incompleteReasons);
                state.put("phase","COMPUTED");save(receipt,state);
                if(plan!=null)planner.requireUnchanged(plan);
                state.put("phase","PREPARING_AND_PUBLISHING");save(receipt,state);
                var result=publication.publishComputation(calculated,run.resolve("publication"),
                        request.asOf(),runId,null);
                state.put("publication",result);state.put("phase",result.state().name());save(receipt,state);
                state.put("publishedRowsVerified",result.verifiedRows()==calculation.path("rows").size()
                        &&Set.of(com.zoutrankil.data.domain.SyncRunState.PARTIAL,
                                com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                                com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(result.state()));
                save(receipt,state);
                if(plan!=null)planner.requireUnchanged(plan);
                return new Result(receipt,result);
            } catch(Exception failure) {
                state.put("interruptedPhase",state.get("phase"));state.put("phase","FAILED_REQUIRES_INSPECTION");
                state.put("failureType",failure.getClass().getName());save(receipt,state);throw failure;
            }
        }
    }
    private static void save(Path receipt,Map<String,Object> state)throws Exception {
        FileEvidenceStore.replaceDurable(receipt,JobDefinitionJson.mapper().writeValueAsBytes(state));
    }
}
