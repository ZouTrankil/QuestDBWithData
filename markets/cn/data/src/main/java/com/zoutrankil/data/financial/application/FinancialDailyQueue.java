package com.zoutrankil.data.financial.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.financial.port.FinancialQueueRepository;
import com.zoutrankil.data.repository.FileEvidenceStore;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** 队列只编排原 owner；每片先保存运行 ID，未验收的原运行不自动重发。 */
public final class FinancialDailyQueue {
    public record Slice(String dataset, String code, LocalDate period) {}

    public record Cycle(
            String schema,
            String scopeSha256,
            String targetFingerprint,
            LocalDate logicalDate,
            List<Slice> slices) {}

    public record Claim(String runId, LocalDate logicalDate) {}

    public record Allowance(LocalDate day, long reservedAttempts, Instant startedAt) {}

    public record Progress(LocalDate logicalDate, int completed, int total, String reason) {}

    public interface Executor {
        String targetFingerprint() throws Exception;

        void run(Slice slice, LocalDate logicalDate, String runId, Duration remaining)
                throws Exception;

        boolean verified(Slice slice, LocalDate logicalDate, String runId) throws Exception;
    }

    private final FinancialQueueRepository store;

    public FinancialDailyQueue(FinancialQueueRepository store) {
        this.store = Objects.requireNonNull(store);
    }

    /** 状态查询不建目录、不建账本；计数只表示本队列已收到原 owner 的验收结果。 */
    public Map<String, Object> status() throws Exception {
        var snapshot =
                store.snapshot(
                        active ->
                                directory(
                                        JobDefinitionJson.mapper().readValue(active, Cycle.class)));
        if (snapshot.active() == null)
            return Map.of("status", "NOT_STARTED", "dataVerified", false);
        var cycle = JobDefinitionJson.mapper().readValue(snapshot.active(), Cycle.class);
        String prefix = directory(cycle);
        var verified = new HashSet<String>();
        var claims = new HashMap<String, Claim>();
        for (var entry : snapshot.entries().entrySet()) {
            if (entry.getKey().endsWith("/verified")) verified.add(entry.getKey());
            else if (entry.getKey().endsWith("/run"))
                claims.put(
                        entry.getKey(),
                        JobDefinitionJson.mapper().readValue(entry.getValue(), Claim.class));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("logicalDate", cycle.logicalDate());
        result.put("scopeSha256", cycle.scopeSha256());
        result.put("completed", verified.size());
        result.put("total", cycle.slices().size());
        result.put("dataVerified", false);
        result.put("status", "CYCLE_VERIFIED");
        if (snapshot.allowance() != null)
            result.put(
                    "allowance",
                    JobDefinitionJson.mapper().readValue(snapshot.allowance(), Allowance.class));
        for (int i = 0; i < cycle.slices().size(); i++) {
            String key = prefix + "/" + i;
            if (!verified.contains(key + "/verified")) {
                result.put("nextSlice", cycle.slices().get(i));
                Claim claim = claims.get(key + "/run");
                result.put("status", claim == null ? "PENDING" : "RUN_REQUIRES_VERIFICATION");
                if (claim != null) {
                    result.put("runId", claim.runId());
                    result.put("runLogicalDate", claim.logicalDate());
                }
                break;
            }
        }
        return result;
    }

    public Progress run(
            String scopeSha,
            LocalDate day,
            List<Slice> proposed,
            long maxAttempts,
            int attemptsPerSlice,
            Duration window,
            Executor executor)
            throws Exception {
        return runPlanned(
                scopeSha,
                day,
                previous -> proposed,
                maxAttempts,
                attemptsPerSlice,
                window,
                executor);
    }

    public Progress runPlanned(
            String scopeSha,
            LocalDate day,
            Function<Cycle, List<Slice>> planner,
            long maxAttempts,
            int attemptsPerSlice,
            Duration window,
            Executor executor)
            throws Exception {
        if (!scopeSha.matches("[a-f0-9]{64}")
                || maxAttempts < attemptsPerSlice
                || attemptsPerSlice < 1
                || window.isNegative()
                || window.isZero()
                || window.compareTo(Duration.ofHours(12)) > 0)
            throw new IllegalArgumentException("财务分片范围和预算无效");
        try (var lease = store.acquire()) {
            store.initialize();
            String targetFingerprint = executor.targetFingerprint();
            if (targetFingerprint == null || targetFingerprint.isBlank())
                throw new IllegalArgumentException("财务队列物理目标身份缺失");
            String active = "active";
            Cycle cycle;
            if (store.exists(active)) {
                cycle = store.read(active, Cycle.class);
                if (!"java.financial.daily-queue.v1".equals(cycle.schema())
                        || !scopeSha.equals(cycle.scopeSha256())
                        || !targetFingerprint.equals(cycle.targetFingerprint()))
                    throw new IllegalStateException("财务队列仍绑定原批准范围及物理目标，请先处理原队列");
                if (day.isBefore(cycle.logicalDate()))
                    throw new IllegalStateException("财务队列业务日期不能倒退");
                if (done(cycle) == cycle.slices().size() && day.isAfter(cycle.logicalDate())) {
                    cycle = create(scopeSha, targetFingerprint, day, planner.apply(cycle));
                    store.write("cycle/" + directory(cycle), cycle);
                    store.write(active, cycle);
                }
            } else {
                cycle = create(scopeSha, targetFingerprint, day, planner.apply(null));
                store.write("cycle/" + directory(cycle), cycle);
                store.write(active, cycle);
            }
            String directory = directory(cycle);
            String budgetFile = "allowance";
            Allowance budget =
                    store.exists(budgetFile) ? store.read(budgetFile, Allowance.class) : null;
            if (budget != null && day.isBefore(budget.day()))
                throw new IllegalStateException("财务预算日期不能倒退");
            if (budget == null || day.isAfter(budget.day())) {
                budget = new Allowance(day, 0, Instant.now());
                store.write(budgetFile, budget);
            }
            var receipts = store.receipts(directory);
            int completed = 0;
            for (int i = 0; i < cycle.slices().size(); i++) {
                var slice = cycle.slices().get(i);
                String receipt = directory + "/" + i + "/verified";
                if (receipts.contains(receipt)) {
                    completed++;
                    continue;
                }
                String claim = directory + "/" + i + "/run";
                if (store.exists(claim)) {
                    var original = store.read(claim, Claim.class);
                    if (!executor.verified(slice, original.logicalDate(), original.runId()))
                        throw new IllegalStateException(
                                "财务分片未验收，保留原运行，按原恢复命令处理：" + original.runId());
                    store.write(receipt, original);
                    completed++;
                    continue;
                }
                Duration remaining =
                        Duration.between(Instant.now(), budget.startedAt().plus(window));
                if (remaining.isNegative()
                        || remaining.isZero()
                        || maxAttempts - budget.reservedAttempts() < attemptsPerSlice)
                    return new Progress(
                            cycle.logicalDate(),
                            completed,
                            cycle.slices().size(),
                            "今日预算已用完，下次继续原队列");
                // 先扣最坏重试预算，再保存运行意图；崩溃只能少用预算，不能重置配额或重复发送。
                budget =
                        new Allowance(
                                day,
                                Math.addExact(budget.reservedAttempts(), attemptsPerSlice),
                                budget.startedAt());
                store.write(budgetFile, budget);
                String id = "financial-" + slice.dataset() + "-" + UUID.randomUUID();
                // 证券和报告期沿用原轮次；尚未开始的片在首次执行时才冻结业务日期，避免跨日披露落入旧日期。
                var intent = new Claim(id, day);
                store.write(claim, intent);
                executor.run(slice, intent.logicalDate(), id, remaining);
                if (!executor.verified(slice, intent.logicalDate(), id))
                    throw new IllegalStateException("财务分片未验收，保留原运行：" + id);
                store.write(receipt, intent);
                completed++;
            }
            if (!cycle.targetFingerprint().equals(executor.targetFingerprint()))
                throw new IllegalStateException("财务队列物理目标已改变");
            return new Progress(cycle.logicalDate(), completed, cycle.slices().size(), "本轮全部分片已验收");
        }
    }

    private Cycle create(String sha, String targetFingerprint, LocalDate day, List<Slice> slices) {
        if (slices.isEmpty() || slices.stream().distinct().count() != slices.size())
            throw new IllegalArgumentException("财务分片重复");
        return new Cycle(
                "java.financial.daily-queue.v1", sha, targetFingerprint, day, List.copyOf(slices));
    }

    private String directory(Cycle cycle) throws Exception {
        return FileEvidenceStore.sha256(
                JobDefinitionJson.canonicalMapper().writeValueAsBytes(cycle));
    }

    private int done(Cycle cycle) throws Exception {
        return store.receipts(directory(cycle)).size();
    }
}
