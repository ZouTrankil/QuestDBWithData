package com.zoutrankil.batch.job;

import com.zoutrankil.batch.domain.CompletionEvidence;
import com.zoutrankil.batch.domain.RunRequest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Parent-context Java owners supply physical certificates to the isolated Batch repository. */
public interface MainStrategyDailyWork {
    String JOB = "main_strategy_daily";
    String DEFINITION_VERSION = "3";
    String INPUT_PROTOCOL_DEFINITION_VERSION = "4";
    List<String> STAGES =
            List.of(
                    "Sources",
                    "MarketSentimentDaily",
                    "RegimeFeaturesMonitorDaily",
                    "BacktestDaily",
                    "L2DailyFeatures",
                    "ScoreInputs",
                    "OfficialPlanData",
                    "MainStrategyAcceptance");

    LocalDate resolveTarget(Clock clock, LocalTime completionCutoff) throws Exception;

    String inputFingerprint(LocalDate target) throws Exception;

    /** 原实现仍默认使用 v3；新版 owner 明确选择自己的新请求版本。 */
    default String definitionVersion() { return DEFINITION_VERSION; }

    /** 恢复按原请求版本算指纹，不能用当前默认版本重绑历史任务。 */
    default String inputFingerprint(LocalDate target, String version) throws Exception {
        if (!definitionVersion().equals(version)) throw new IllegalArgumentException("不支持的主策略任务版本：" + version);
        return inputFingerprint(target);
    }

    void execute(RunRequest request, StageSink sink) throws Exception;

    /** Read-only revalidation never rewrites historical VERIFIED certificates. */
    void validateCompleted(RunRequest request, Map<String, CompletionEvidence> stages)
            throws Exception;

    /** Explicit operator recovery of the original stopped owner; never creates a replacement run. */
    default CompletionEvidence reconcilePublication(
            RunRequest request,
            String stage,
            String runId,
            boolean writerStopped,
            Map<String, CompletionEvidence> upstream)
            throws Exception {
        throw new UnsupportedOperationException(
                "Publication reconciliation is not implemented by this owner");
    }

    Map<String, Object> status() throws Exception;

    @FunctionalInterface
    interface StageSink {
        void complete(String stage, StageExecutor.Result result) throws Exception;

        default Optional<CompletionEvidence> existing(String stage) throws Exception {
            return Optional.empty();
        }
    }
}
