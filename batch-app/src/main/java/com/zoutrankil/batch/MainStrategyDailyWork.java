package com.zoutrankil.batch;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Parent-context Java owners supply physical certificates to the isolated Batch repository. */
public interface MainStrategyDailyWork {
    String JOB = "main_strategy_daily";
    String DEFINITION_VERSION = "1";
    List<String> STAGES = List.of("Sources", "MarketSentimentDaily", "RegimeFeaturesMonitorDaily",
            "BacktestDaily", "MainStrategyAcceptance");

    LocalDate resolveTarget(Clock clock, LocalTime completionCutoff) throws Exception;
    String inputFingerprint(LocalDate target) throws Exception;
    void execute(RunRequest request, StageSink sink) throws Exception;
    /** Read-only revalidation never rewrites historical VERIFIED certificates. */
    void validateCompleted(RunRequest request, Map<String,CompletionEvidence> stages) throws Exception;
    /** Explicit operator recovery of the original stopped owner; never creates a replacement run. */
    default CompletionEvidence reconcilePublication(RunRequest request,String stage,String runId,
            boolean writerStopped,Map<String,CompletionEvidence> upstream) throws Exception {
        throw new UnsupportedOperationException("Publication reconciliation is not implemented by this owner");
    }
    Map<String,Object> status() throws Exception;

    @FunctionalInterface
    interface StageSink {
        void complete(String stage, StageExecutor.Result result) throws Exception;
        default Optional<CompletionEvidence> existing(String stage) throws Exception { return Optional.empty(); }
    }
}
