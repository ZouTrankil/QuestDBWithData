package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.engine.FactorPerformanceWindows;
import com.zoutrankil.data.factor.storage.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Native mature-date monitor composition. Physical publication does not certify upstream coverage. */
public final class PostCloseFactorMonitorService {
    public record Request(LocalDate asOf,LocalDate historyFrom,String factors,String pools,String label,Path root) {
        public Request {
            Objects.requireNonNull(asOf);Objects.requireNonNull(historyFrom);Objects.requireNonNull(root);
            if(historyFrom.isAfter(asOf)||java.time.temporal.ChronoUnit.DAYS.between(historyFrom,asOf)>1100
                    ||factors==null||factors.isBlank()||pools==null||pools.isBlank()||label==null||label.isBlank())
                throw new IllegalArgumentException("Bounded history and explicit factor/pool/label plan required");
        }
    }
    public record Result(Path receipt,Map<String,Object> state) {}
    private final FactorPipelineService factors;
    private final DailyMetricPipelineService metrics;
    private final QuestDbDailyMetricPlan planner;
    private final QuestDbDailyMetricFreshness freshness;
    private final QuestDbDailyMetrics history;
    public PostCloseFactorMonitorService(FactorPipelineService factors,DailyMetricPipelineService metrics,
            QuestDbDailyMetricPlan planner,QuestDbDailyMetricFreshness freshness,QuestDbDailyMetrics history) {
        this.factors=Objects.requireNonNull(factors);this.metrics=Objects.requireNonNull(metrics);
        this.planner=Objects.requireNonNull(planner);this.freshness=Objects.requireNonNull(freshness);
        this.history=Objects.requireNonNull(history);
    }
    public Result run(Request request)throws Exception {
        var catalog=FactorDefinitionCatalog.load();
        var selected=new LinkedHashSet<String>();
        for(String id:request.factors().split(",",-1)) {
            catalog.require(id);if(!selected.add(id))throw new IllegalArgumentException("Duplicate factor plan");
        }
        Path root=request.root().toAbsolutePath().normalize();Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("factor-monitor.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("Factor monitor already running");
            var plan=planner.plan(null,request.asOf());var signal=plan.pairs().getLast().signal();
            if(request.historyFrom().isAfter(signal))throw new IllegalArgumentException("History must include mature signal date");
            Path execution=root.resolve(request.asOf()+"-"+UUID.randomUUID());Files.createDirectory(execution);
            Path receipt=execution.resolve("pipeline.json");var state=new LinkedHashMap<String,Object>();
            state.put("schema","java.post_close_factor_monitor.v1");state.put("request",request);
            state.put("maturityPlan",plan);state.put("sourceComplete",false);state.put("factorReady",false);
            state.put("pageSnapshotRefreshed",false);state.put("metricsPhysicallyVerified",false);
            var timings=new LinkedHashMap<String,Double>();state.put("stageMillis",timings);long started=System.nanoTime();
            try {
                state.put("phase","MATURE_FACTOR_PUBLICATION");save(receipt,state);long stage=System.nanoTime();
                var panel=factors.run(new FactorPipelineService.Request(signal,request.factors(),request.pools(),root.resolve("factors")));
                state.put("factorReceipt",panel.receipt().toString());timings.put("factors",millis(stage));
                var bundlePath=panel.receipt().getParent().resolve("observations");var bundle=FactorObservationBundle.read(bundlePath);
                if(!bundle.sha256().equals(panel.state().get("observationBundleSha256"))
                        ||!(panel.state().get("verifiedRows") instanceof Number verified)||verified.longValue()!=bundle.manifest().rows())
                    throw new IllegalStateException("Mature factor publication did not verify frozen bundle");
                planner.requireUnchanged(plan);state.put("phase","METRIC_PUBLICATION");save(receipt,state);stage=System.nanoTime();
                var paths=bundle.manifest().parts().stream().map(part->bundlePath.resolve(part.file())).toList();
                var metric=metrics.run(new DailyMetricPipelineService.Request(signal,request.asOf(),paths,
                        panel.receipt().getParent().resolve("pools.json"),request.label(),root.resolve("metrics")));
                state.put("metricReceipt",metric.receipt().toString());timings.put("metrics",millis(stage));
                var json=JobDefinitionJson.mapper();var metricState=json.readTree(FileEvidenceStore.readBounded(metric.receipt(),1024*1024,
                        ()->new IllegalStateException("Metric receipt exceeds budget")));
                if(!metricState.path("publishedRowsVerified").asBoolean(false))throw new IllegalStateException("Metrics not physically verified");
                state.put("metricsPhysicallyVerified",true);
                var computation=json.readTree(FileEvidenceStore.readBounded(Path.of(metricState.path("computation").asText()),32*1024*1024,
                        ()->new IllegalStateException("Metric computation exceeds budget")));
                var expected=new ArrayList<DailyMetricFreshness.Key>();
                for(var key:computation.path("expected"))expected.add(new DailyMetricFreshness.Key(key.path("universeId").asText(),
                        key.path("universeVersion").asText(),key.path("factorId").asText(),key.path("definitionVersion").asText()));
                // Authored calculation matrix includes unavailable pool/factor keys; never infer it from physical rows.
                state.put("phase","FRESHNESS");save(receipt,state);stage=System.nanoTime();planner.requireUnchanged(plan);
                var checked=freshness.inspect(new QuestDbDailyMetricFreshness.Request(signal,request.asOf(),request.label(),expected));
                state.put("freshness",checked);timings.put("freshness",millis(stage));
                state.put("phase","PERFORMANCE_WINDOWS");save(receipt,state);stage=System.nanoTime();
                var snapshot=history.readRange(request.historyFrom(),request.asOf(),1_000_000,request.label());
                var rows=snapshot.rows().stream().filter(row->selected.contains(row.factorId())).toList();
                var directions=new HashMap<FactorPerformanceWindows.Definition,String>();
                for(String id:selected) {var definition=catalog.require(id);directions.put(new FactorPerformanceWindows.Definition(id,definition.version()),definition.direction());}
                var windows=FactorPerformanceWindows.compute(rows,request.asOf(),directions);
                byte[] frozen=json.writeValueAsBytes(rows);Path frozenPath=execution.resolve("history-rows.json");
                FileEvidenceStore.writeNewDurable(frozenPath,frozen);
                var output=new LinkedHashMap<String,Object>();output.put("schema","java.formal_factor_performance_windows.v1");
                output.put("asOf",request.asOf());output.put("from",request.historyFrom());output.put("inputSha256",FileEvidenceStore.sha256(frozen));
                output.put("catalogSha256",catalog.sourceConfigSha256());output.put("sourceState",snapshot.sourceState());
                output.put("windows",windows);output.put("sourceComplete",false);output.put("pageSnapshotRefreshed",false);
                Path windowPath=execution.resolve("windows.json");FileEvidenceStore.writeNewDurable(windowPath,json.writeValueAsBytes(output));
                state.put("windows",windowPath.toString());state.put("historyRows",rows.size());state.put("windowRows",windows.size());
                state.put("completeWindows",windows.stream().filter(FactorPerformanceWindows.Window::completeWindow).count());
                timings.put("windows",millis(stage));planner.requireUnchanged(plan);
                state.put("phase","PARTIAL");state.put("incompleteReasons",List.of("SOURCE_NOT_CERTIFIED_COMPLETE","PAGE_SNAPSHOT_NOT_MIGRATED"));
                timings.put("total",millis(started));save(receipt,state);return new Result(receipt,Collections.unmodifiableMap(state));
            } catch(Exception failure) {
                state.put("interruptedPhase",state.get("phase"));state.put("phase","FAILED_REQUIRES_INSPECTION");
                state.put("failureType",failure.getClass().getName());timings.put("total",millis(started));save(receipt,state);throw failure;
            }
        }
    }
    private static double millis(long started){return (System.nanoTime()-started)/1_000_000.0;}
    private static void save(Path path,Map<String,Object> state)throws Exception {
        FileEvidenceStore.replaceDurable(path,JobDefinitionJson.mapper().writeValueAsBytes(state));
    }
}
