package com.zoutrankil.data.factor.application;


import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;
import com.zoutrankil.data.factor.storage.DailyMetricWriteSession;
import com.zoutrankil.data.service.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Bounded canonical metric publication through the shared ledger and interval locks. */
public final class DailyMetricJobService {
    private DailyMetricJobService() {}
    public static final SyncJobDefinition DEFINITION=new SyncJobDefinition(
            "data.factor_daily_metric",1,"factor_daily_metric",1,"factor_daily_metric_owner",
            Set.of(Mode.INCREMENTAL,Mode.BACKFILL),Mode.INCREMENTAL,
            Map.of("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "metricEvidenceSha256",new Parameter(ParameterType.STRING,true,64,1,Set.of()),
                    "inputFingerprint",new Parameter(ParameterType.STRING,true,64,1,Set.of())),
            "local.factor_evaluation","factor_daily_metric.date","questdb.full_key_values",
            new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofMinutes(3)),Duration.ofMinutes(30),
            new Budget(1,40,40,10000,1024*1024),0,List.of(),Frequency.MANUAL,ZoneId.of("Asia/Shanghai"),true,false);
    @FunctionalInterface public interface InputGuard { void requireCurrent(String fingerprint)throws Exception; }
    public static SyncJobRunner.Result execute(SyncJobRunner<FactorDailyMetricRow,DailyMetricDelta.Key> runner,
            DailyMetricWriteSession session,String targetId,String runId,String priorRunId,Path evidence,
            LocalDate logicalDate,InputGuard guard,BooleanSupplier cancelled)throws Exception {
        Objects.requireNonNull(guard);var frozen=DailyMetricEvidence.read(evidence);
        if(!targetId.matches("static-v2-[a-f0-9]{64}") || !targetId.equals(session.targetIdentity()) || frozen.signalDate().isAfter(logicalDate)
                || frozen.rows().stream().anyMatch(r->r.availableAt().atOffset(ZoneOffset.UTC).toLocalDate().isAfter(logicalDate)))
            throw new IllegalArgumentException("Physical target and mature logical date required");
        var request=DEFINITION.freeze(Mode.INCREMENTAL,Map.of("targetId",targetId,"metricEvidenceSha256",frozen.fileSha256(),
                "inputFingerprint",frozen.inputFingerprint()),frozen.signalDate(),frozen.signalDate(),logicalDate);
        var adapter=new SyncJobRunner.Adapter<FactorDailyMetricRow,DailyMetricDelta.Key>() {
            private void unchanged()throws Exception {
                if(!frozen.fileSha256().equals(DailyMetricEvidence.read(evidence).fileSha256()))
                    throw new IllegalStateException("Frozen metric evidence changed");
                guard.requireCurrent(frozen.inputFingerprint());
            }
            public void preflight(FrozenRequest request)throws Exception { unchanged();session.preflight(); }
            public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<FactorDailyMetricRow> consumer,
                    BooleanSupplier stopped)throws Exception {
                int pages=0;
                for(int start=0;start<frozen.rows().size();start+=250) {
                    if(stopped.getAsBoolean())throw new java.util.concurrent.CancellationException("Metric publication cancelled");
                    unchanged();int end=Math.min(start+250,frozen.rows().size());
                    String fingerprint=com.zoutrankil.data.repository.FileEvidenceStore.sha256(
                            (frozen.fileSha256()+"\n"+start+"\n"+end).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    consumer.accept(new SyncJobRunner.Page<>(frozen.rows().subList(start,end),fingerprint,evidence.toAbsolutePath().toString(),Integer.toString(pages++)));
                }
                unchanged();return new SyncJobRunner.SourceCompletion(pages,frozen.rows().size(),frozen.complete(),evidence.toAbsolutePath().toString());
            }
            public VerifiedBatchExecutor.Codec<FactorDailyMetricRow,DailyMetricDelta.Key> codec(){return session.codec();}
            public VerifiedBatchExecutor.Port<FactorDailyMetricRow,DailyMetricDelta.Key> port(){return session;}
            public Duration visibilityTimeout(){return Duration.ofMinutes(3);}
        };
        return priorRunId==null?runner.run(runId,null,targetId,request,adapter,cancelled)
                :runner.resume(runId,priorRunId,targetId,request,adapter,cancelled);
    }
}
