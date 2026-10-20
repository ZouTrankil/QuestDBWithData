package com.zoutrankil.batch.job;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.postclose.CoreSourceFanout;
import com.zoutrankil.batch.postclose.PostCloseGraph;
import com.zoutrankil.batch.source.SourceCollector;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.batch.storage.SqliteLedger;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 手工触发、Quartz 调度和恢复请求的统一入口。 */
public final class LaunchService {
    private final SqliteLedger ledger;
    private final JobOperator operator;
    private final Map<String, Job> jobs;
    private final SourceCollector sourceCollector;
    private final CoreSourceFanout coreSourceFanout;

    public LaunchService(SqliteLedger ledger, JobOperator operator, Job postClose) {
        this(ledger, operator, List.of(postClose));
    }

    public LaunchService(SqliteLedger ledger, JobOperator operator, Collection<Job> jobs) {
        this(ledger, operator, jobs, null, null);
    }

    public LaunchService(
            SqliteLedger ledger,
            JobOperator operator,
            Collection<Job> jobs,
            SourceCollector sourceCollector) {
        this(ledger, operator, jobs, sourceCollector, null);
    }

    public LaunchService(
            SqliteLedger ledger,
            JobOperator operator,
            Collection<Job> jobs,
            SourceCollector sourceCollector,
            CoreSourceFanout coreSourceFanout) {
        this.ledger = ledger;
        this.operator = operator;
        this.sourceCollector = sourceCollector;
        this.coreSourceFanout = coreSourceFanout;
        var registered = new HashMap<String, Job>();
        for (var job : jobs) {
            if ("l2_archive_integrity".equals(job.getName())) continue; // 这里只传递归档专用的路径和身份参数。
            if (registered.put(job.getName(), job) != null)
                throw new IllegalArgumentException("Duplicate job owner");
        }
        this.jobs = Map.copyOf(registered);
    }

    public Map<String, Object> launch(RunRequest request) throws Exception {
        if (request.job().startsWith("source_")) {
            if (sourceCollector == null)
                throw new IllegalStateException("Source scope verification is unavailable");
            var frozen = sourceCollector.read(request.inputFingerprint());
            String dataset = request.job().substring("source_".length());
            if (!frozen.dataset().equals(dataset)
                    || !frozen.logicalDate().equals(request.logicalDate())
                    || !frozen.rangeStart().equals(request.rangeStart())
                    || !frozen.rangeEnd().equals(request.rangeEnd()))
                throw new IllegalArgumentException(
                        "Source artifact and requested business scope do not match");
            var source = SourceContract.load(dataset);
            if (!source.version().equals(frozen.definitionVersion()))
                throw new IllegalArgumentException("Frozen source definition is not supported");
            source.writeLayout(request.definitionVersion());
            String scope = SourceCollector.scopeIdentity(frozen);
            if (!request.scopeIdentity().isBlank() && !request.scopeIdentity().equals(scope))
                throw new IllegalArgumentException(
                        "Declared source scope does not match frozen expected codes");
            request = request.withScopeIdentity(scope);
        }
        try (var guard = ledger.tryLock("instance:" + request.instanceId()).orElse(null)) {
            if (guard == null) {
                ledger.register(request); // 相同输入的重复触发仍需留痕，但不能改绑其他运行实例正在使用的输入。
                return Map.of("instanceId", request.instanceId(), "disposition", "already-running");
            }
            RunRequest trigger = request;
            ledger.register(
                    request,
                    sourceCollector == null
                            ? (previous, next) -> false
                            : sourceCollector::sameScope);
            request = ledger.request(request.instanceId()).withTrigger(trigger);
            var state = ledger.state(request.instanceId());
            if (state.ready()) return ledger.detail(request.instanceId());
            if (state == BusinessState.RUNNING
                    || (state == BusinessState.VERIFYING && !request.job().startsWith("source_"))
                    || state == BusinessState.IN_DOUBT) {
                ledger.state(
                        request.instanceId(),
                        BusinessState.IN_DOUBT,
                        null,
                        "Reconcile prior execution before restart; no automatic replay");
                return ledger.detail(request.instanceId());
            }
            if ("pre_open_acceptance".equals(request.job())) {
                // 这里只读验收已有产物，不调用生产流程。
                ledger.state(
                        request.instanceId(),
                        BusinessState.BLOCKED,
                        null,
                        "product-certificates-not-connected");
                return ledger.detail(request.instanceId());
            }
            guard.requireAlive();
            ledger.state(request.instanceId(), BusinessState.RUNNING, null, null);
            try {
                if ("post_close".equals(request.job()) && coreSourceFanout != null) {
                    var fanout = coreSourceFanout.execute(request, this::launch);
                    if (!fanout.ready()) return ledger.detail(request.instanceId());
                }
                var parameters =
                        new JobParametersBuilder()
                                .addString("instance", request.instanceId())
                                .addString("input", request.inputIdentity(), false)
                                .toJobParameters();
                var job = jobs.get(request.job());
                if (job == null) throw new IllegalArgumentException("Unregistered executable job");
                var execution = operator.start(job, parameters);
                if (request.job().startsWith("source_")) {
                    if (ledger.state(request.instanceId()) == BusinessState.RUNNING)
                        ledger.state(
                                request.instanceId(),
                                BusinessState.IN_DOUBT,
                                execution.getId(),
                                "Source execution outcome requires reconciliation");
                    return ledger.detail(request.instanceId());
                }
                var states = ledger.stages(request.instanceId());
                if (MainStrategyDailyWork.JOB.equals(request.job())) {
                    boolean ready =
                            MainStrategyDailyWork.STAGES.stream()
                                    .allMatch(
                                            stage ->
                                                    states.getOrDefault(
                                                                    stage,
                                                                    BusinessState.WAITING_UPSTREAM)
                                                            .ready());
                    var outcome = ledger.state(request.instanceId());
                    boolean active =
                            outcome == BusinessState.RUNNING || outcome == BusinessState.VERIFYING;
                    boolean completed = execution.getStatus() == BatchStatus.COMPLETED;
                    // 最终回调或提交失败时，旧的就绪证书可能仍然存在。
                    // 这些旧证书不能覆盖已经记录的“结果未决”或失败状态。
                    if (ready && completed && active)
                        ledger.state(
                                request.instanceId(),
                                BusinessState.VERIFIED,
                                execution.getId(),
                                null);
                    else if (active)
                        ledger.state(
                                request.instanceId(),
                                BusinessState.IN_DOUBT,
                                execution.getId(),
                                "Main-strategy batch outcome requires reconciliation: technical="
                                        + execution.getStatus()
                                        + ", authoritativeStagesReady="
                                        + ready);
                    return ledger.detail(request.instanceId());
                }
                boolean allReady =
                        PostCloseGraph.STAGES.stream()
                                .allMatch(
                                        s ->
                                                states.getOrDefault(
                                                                s.id(),
                                                                BusinessState.WAITING_UPSTREAM)
                                                        .ready());
                boolean criticalReady =
                        PostCloseGraph.STAGES.stream()
                                .filter(PostCloseGraph.Stage::critical)
                                .allMatch(
                                        s ->
                                                states.getOrDefault(
                                                                s.id(),
                                                                BusinessState.WAITING_UPSTREAM)
                                                        .ready());
                if (allReady)
                    ledger.state(
                            request.instanceId(), BusinessState.VERIFIED, execution.getId(), null);
                else if (criticalReady)
                    ledger.state(
                            request.instanceId(),
                            BusinessState.PARTIAL,
                            execution.getId(),
                            "Optional stage warning; inspect steps");
                else if (ledger.state(request.instanceId()) == BusinessState.RUNNING)
                    ledger.state(
                            request.instanceId(),
                            BusinessState.IN_DOUBT,
                            execution.getId(),
                            "Batch ended without an authoritative product outcome; reconcile before replay");
                return ledger.detail(request.instanceId());
            } catch (Exception error) {
                ledger.state(
                        request.instanceId(),
                        BusinessState.IN_DOUBT,
                        null,
                        "Batch launch/commit uncertain; reconcile: "
                                + error.getClass().getSimpleName());
                throw error;
            }
        }
    }
}
