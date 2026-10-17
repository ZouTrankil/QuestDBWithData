package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 来源回调同步执行：本页完成持久化和回读核验后，才获取下一页，形成背压。 */
public final class SyncJobRunner<T, K> {
    public record Page<T>(List<T> rows, String sourceFingerprint, String responseEvidence, String cursor) {
        public Page {
            rows = List.copyOf(rows);
            if (rows.size() > 10000 || sourceFingerprint == null || sourceFingerprint.isBlank()
                    || responseEvidence == null || responseEvidence.isBlank())
                throw new IllegalArgumentException("Bounded page and source evidence required");
        }
    }
    public record SourceCompletion(int pages, int rows, boolean complete, String evidence) {}
    public record Result(String runId, SyncRunState state, int sourceRows, int verifiedRows, String errorCode, int reusedRows) {
        public Result(String runId,SyncRunState state,int sourceRows,int verifiedRows,String errorCode) {
            this(runId,state,sourceRows,verifiedRows,errorCode,0);
        }
    }
    @FunctionalInterface public interface PageConsumer<T> { void accept(Page<T> page) throws Exception; }
    public interface Adapter<T, K> {
        void preflight(FrozenRequest request) throws Exception;
        SourceCompletion fetch(FrozenRequest request, PageConsumer<T> consumer, BooleanSupplier cancelled) throws Exception;
        VerifiedBatchExecutor.Codec<T, K> codec();
        VerifiedBatchExecutor.Port<T, K> port();
        /** 为防止写入冲突，加锁日期范围可以大于本次来源请求的范围。 */
        default DatasetIntervalLock.Scope conflictScope(FrozenRequest request) {
            return request.from() == null ? DatasetIntervalLock.Scope.allDates(request.definition().datasetId())
                    : new DatasetIntervalLock.Scope(request.definition().datasetId(), request.from(), request.to());
        }
        /** 原生异步物化可显式申请更长、但仍有上限的可见性等待时间。 */
        default Duration visibilityTimeout() { return Duration.ofSeconds(20); }
        /** 按需指定传输容量；适配器改变批次布局时，必须依据已冻结的请求。 */
        default int maxWriteBatchRows(FrozenRequest request) { return 250; }
        /** 数据集存在持久化副作用、重试前必须显式核对时，返回 true。 */
        default boolean recoveryRequired(String runId) throws Exception { return false; }
    }
    private final SyncRunLedger ledger;
    private final DatasetIntervalLock locks;
    private final ObjectMapper json = new ObjectMapper();
    public SyncJobRunner(SyncRunLedger ledger, DatasetIntervalLock locks) {
        this.ledger = Objects.requireNonNull(ledger); this.locks = Objects.requireNonNull(locks);
    }
    public Result run(String runId, String parentRunId, String targetId, FrozenRequest request,
                      Adapter<T, K> adapter, BooleanSupplier cancelled) throws Exception {
        return execute(runId,parentRunId,targetId,request,adapter,cancelled,VerifiedSliceRecovery.none());
    }
    public Result resume(String runId,String priorRunId,String targetId,FrozenRequest request,
                         Adapter<T,K> adapter,BooleanSupplier cancelled) throws Exception {
        return resume(runId, priorRunId, priorRunId, targetId, request, adapter, cancelled);
    }
    public Result resume(String runId, String parentRunId, String priorRunId, String targetId,
                         FrozenRequest request, Adapter<T,K> adapter, BooleanSupplier cancelled) throws Exception {
        var recovery=VerifiedSliceRecovery.load(ledger,priorRunId,request,targetId);
        return execute(runId,parentRunId,targetId,request,adapter,cancelled,recovery);
    }
    /**
     * 一次运行依次登记意图、预检、领取区间锁，再逐页写入和核验。
     * 来源完整性与全部批次验证同时通过才进入成功终态；未决结果保留锁等待核对。
     */
    private Result execute(String runId,String parentRunId,String targetId,FrozenRequest request,
                           Adapter<T,K> adapter,BooleanSupplier cancelled,VerifiedSliceRecovery recovery) throws Exception {
        if (!request.definition().enabled()) throw new IllegalArgumentException("Job is disabled");
        Objects.requireNonNull(adapter); Objects.requireNonNull(cancelled);
        long started = System.nanoTime();
        long duration = request.definition().timeout().toNanos();
        BooleanSupplier stopped = () -> {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || System.nanoTime() - started >= duration)
                return true;
            try { return ledger.cancellationRequested(runId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read cancellation state", failure); }
        };
        ledger.createRun(runId, parentRunId, targetId, request);
        DatasetIntervalLock.Scope scope;
        try {
            check(stopped);
            adapter.preflight(request);
            scope = adapter.conflictScope(request);
            if (scope == null || !request.definition().datasetId().equals(scope.datasetId())
                    || (request.from() == null
                        ? !scope.equals(DatasetIntervalLock.Scope.allDates(request.definition().datasetId()))
                        : scope.from().isAfter(request.from()) || scope.to().isBefore(request.to())))
                throw new IllegalArgumentException("Conflict scope must cover the request in the same dataset");
            check(stopped);
        }
        catch (Exception failure) {
            var state = failure instanceof CancellationException ? SyncRunState.CANCELLED : SyncRunState.FAILED;
            ledger.transition(runId, 0, state, error(failure));
            return new Result(runId, state, 0, 0, failure.getClass().getSimpleName());
        }
        var lease = locks.acquire(runId, scope);
        if (lease == null) {
            ledger.transition(runId, 0, SyncRunState.FAILED, "{\"errorCode\":\"DATASET_INTERVAL_BUSY\"}");
            return new Result(runId, SyncRunState.FAILED, 0, 0, "DATASET_INTERVAL_BUSY");
        }
        String attempt = "attempt-" + UUID.randomUUID();
        var progress = new Progress(recovery);
        try {
            ledger.transition(runId, 0, SyncRunState.RUNNING, "{}");
            ledger.createChild(attempt, SyncRunLedger.Kind.ATTEMPT, runId, runId);
            ledger.transition(attempt, 0, SyncRunState.RUNNING, "{}");
            Thread ownerThread = Thread.currentThread();
            var accepting = new java.util.concurrent.atomic.AtomicBoolean(true);
            SourceCompletion completion;
            try {
                completion = adapter.fetch(request, page -> {
                    if (!accepting.get() || Thread.currentThread() != ownerThread)
                        throw new IllegalStateException("Source callbacks must be synchronous and confined to fetch");
                    consume(runId, attempt, request, adapter, page, progress, stopped, started, duration);
                }, stopped);
            } finally { accepting.set(false); }
            check(stopped);
            // 空页不能单独证明没有数据；页数、行数及来源终止证据必须覆盖实际交付的全部分页。
            if (completion == null || !completion.complete() || completion.pages() != progress.pages
                    || completion.rows() != progress.sourceRows || completion.evidence() == null || completion.evidence().isBlank())
                throw new IllegalStateException("Source completion does not cover emitted pages");
            for (String emptySlice : progress.emptySlices) {
                ledger.transition(emptySlice, ledger.get(emptySlice).revision(), SyncRunState.VERIFIED_EMPTY,
                        emptyProof(completion.evidence()));
            }
            var state = progress.sourceRows == 0 ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
            String proof = state == SyncRunState.VERIFIED_EMPTY ? emptyProof(completion.evidence())
                    : proof(progress.verifiedRows, fingerprint(progress.sourceFingerprints),
                            String.join(";", progress.readbackRefs), false);
            ledger.transition(attempt, ledger.get(attempt).revision(), state, proof);
            ledger.transition(runId, ledger.get(runId).revision(), state, proof);
            locks.releaseVerified(lease);
            return new Result(runId, state, progress.sourceRows, progress.verifiedRows, null,progress.reusedRows);
        } catch (Exception failure) {
            // 已经提交或无法确认的结果仍须保留互斥占用；超时不代表写入方已经停止。
            boolean uncertain = progress.uncertain;
            boolean recoveryRequired = false;
            try {
                recoveryRequired = adapter.recoveryRequired(runId);
                uncertain |= recoveryRequired;
            } catch (Exception recoveryCheckFailure) {
                recoveryRequired = true;
                uncertain = true;
                failure.addSuppressed(recoveryCheckFailure);
            }
            var state = uncertain ? SyncRunState.IN_DOUBT : failure instanceof CancellationException
                    ? SyncRunState.CANCELLED : progress.verifiedRows > 0 ? SyncRunState.PARTIAL : SyncRunState.FAILED;
            if (progress.currentSlice != null) {
                try {
                    finishFailure(progress.currentSlice,
                            uncertain ? SyncRunState.IN_DOUBT : failure instanceof CancellationException
                                    ? SyncRunState.CANCELLED : SyncRunState.FAILED, failure);
                } catch (Exception persistenceFailure) {
                    uncertain = true;
                    failure.addSuppressed(persistenceFailure);
                }
            }
            for (String emptySlice : progress.emptySlices) {
                try { finishFailure(emptySlice, recoveryRequired ? SyncRunState.IN_DOUBT : SyncRunState.FAILED, failure); }
                catch (Exception persistenceFailure) {
                    uncertain = true;
                    failure.addSuppressed(persistenceFailure);
                }
            }
            // 分片状态不允许转为 IN_DOUBT 时，保留 FETCHED 或 VALIDATED 原状态，
            // 但仍分别尝试将所属执行尝试和整次运行标记为结果未决。
            SyncRunState ownerState = uncertain ? SyncRunState.IN_DOUBT : state;
            try { finishFailure(attempt, ownerState, failure); }
            catch (Exception persistenceFailure) {
                uncertain = true;
                failure.addSuppressed(persistenceFailure);
            }
            ownerState = uncertain ? SyncRunState.IN_DOUBT : state;
            try { finishFailure(runId, ownerState, failure); }
            catch (Exception persistenceFailure) {
                uncertain = true;
                failure.addSuppressed(persistenceFailure);
            }
            if (uncertain) {
                // 发布适配器记录持久化日志时，可能已将同一租约标记为未决。
                // 先重新读取归属，再幂等更新租约状态。
                var currentLease = locks.findOwned(runId, lease.scope());
                if (currentLease == null || !currentLease.id().equals(lease.id()))
                    throw new IllegalStateException("Uncertain run no longer owns its original interval lease", failure);
                if (!currentLease.inDoubt()) locks.retainInDoubt(currentLease);
            } else locks.releaseVerified(lease);
            if (uncertain) state = SyncRunState.IN_DOUBT;
            return new Result(runId, state, progress.sourceRows, progress.verifiedRows, failure.getClass().getSimpleName(),progress.reusedRows);
        }
    }
    private final class Progress {
        int pages, sourceRows, verifiedRows, reusedRows;
        final VerifiedSliceRecovery recovery;
        Progress(VerifiedSliceRecovery recovery) { this.recovery=recovery; }
        boolean uncertain;
        String currentSlice;
        final List<String> emptySlices = new ArrayList<>();
        final List<String> sourceFingerprints = new ArrayList<>();
        final List<String> readbackRefs = new ArrayList<>();
        final Set<Object> seenKeys = new HashSet<>();
    }
    private void consume(String run, String attempt, FrozenRequest request, Adapter<T,K> adapter, Page<T> page,
                         Progress progress, BooleanSupplier stopped, long startedNanos, long durationNanos) throws Exception {
        check(stopped);
        var budget = request.definition().budget();
        if (progress.pages >= Math.min(budget.maxPages(), budget.maxSlices())
                || (long) progress.sourceRows + page.rows().size() > budget.maxRows())
            throw new IllegalArgumentException("Source exceeds frozen job budget");
        String slice = "slice-" + UUID.randomUUID();
        ledger.createChild(slice, SyncRunLedger.Kind.SLICE, run, attempt);
        progress.currentSlice = slice;
        ledger.transition(slice, 0, SyncRunState.RUNNING, "{}");
        var source = new LinkedHashMap<String,Object>();
        source.put("returnedRows", page.rows().size()); source.put("sourceFingerprint", page.sourceFingerprint());
        source.put("responseEvidence", page.responseEvidence()); source.put("cursor", page.cursor());
        ledger.transition(slice, 1, SyncRunState.FETCHED, json.writeValueAsString(source));
        progress.pages++; progress.sourceRows += page.rows().size();
        progress.sourceFingerprints.add(page.sourceFingerprint());
        for (T row : page.rows()) {
            Object key = adapter.codec().key(row);
            if (key == null || !progress.seenKeys.add(key))
                throw new IllegalArgumentException("Duplicate or null source key across pages");
        }
        if (page.rows().isEmpty()) {
            progress.emptySlices.add(slice);
            progress.currentSlice = null;
            return;
        }
        ledger.transition(slice, 2, SyncRunState.VALIDATED, json.writeValueAsString(source));
        var port = adapter.port();
        var reused=progress.recovery.revalidate(page,adapter.codec(),port);
        if(reused!=null) {
            check(stopped);
            var evidence=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(
                    proof(page.rows().size(),page.sourceFingerprint(),"ledger:"+slice+":revalidation",false));
            evidence.set("reusedCheckpoint",json.valueToTree(reused));
            ledger.transition(slice,3,SyncRunState.VERIFIED,evidence.toString());
            progress.verifiedRows+=page.rows().size(); progress.reusedRows+=page.rows().size();
            progress.currentSlice=null; progress.readbackRefs.add("ledger:"+slice+":revalidation");
            return;
        }
        boolean[] submitted = {false};
        var guarded = new VerifiedBatchExecutor.Port<T,K>() {
            public void preflight() throws Exception { check(stopped); port.preflight(); check(stopped); }
            public void send(List<T> rows) throws Exception {
                check(stopped);
                if (!submitted[0]) {
                    ledger.transition(slice, 3, SyncRunState.SUBMITTED, json.writeValueAsString(source));
                    submitted[0] = true; progress.uncertain = true;
                }
                port.submissionRecorded(new VerifiedBatchExecutor.Submission(ledger.path(), run, slice,
                        ledger.get(slice).revision(), page.sourceFingerprint()));
                port.send(rows);
            }
            public List<T> readback(List<K> keys) throws Exception { return port.readback(keys); }
            public boolean walSettled() throws Exception { return port.walSettled(); }
            public boolean uncertainSenderStopped() throws Exception { return port.uncertainSenderStopped(); }
        };
        int capacity = adapter.maxWriteBatchRows(request);
        if(capacity < 1 || capacity > 10_000)
            throw new IllegalArgumentException("Write batch capacity must be 1..10000");
        int batchRows = Math.min(capacity, page.rows().size());
        long remainingNanos = durationNanos - (System.nanoTime() - startedNanos);
        if (remainingNanos < 1_000_000) throw new CancellationException("Write deadline reached");
        var requestedVisibility = adapter.visibilityTimeout();
        if (requestedVisibility == null || requestedVisibility.isZero() || requestedVisibility.isNegative()
                || requestedVisibility.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Positive visibility timeout of at most five minutes required");
        var visibilityTimeout = Duration.ofNanos(Math.min(requestedVisibility.toNanos(), remainingNanos));
        var poll = Duration.ofMillis(Math.min(100, Math.max(1, visibilityTimeout.toMillis())));
        var policy = new VerifiedBatchExecutor.Policy(batchRows, budget.maxBatchBytes(),
                Math.min(100000 / batchRows, page.rows().size()), visibilityTimeout, poll);
        var result = new VerifiedBatchExecutor<>(policy, adapter.codec(), guarded).execute(page.rows().iterator());
        if (!result.status().equals(VerifiedBatchExecutor.Status.VERIFIED)) {
            ledger.transition(slice, ledger.get(slice).revision(), submitted[0] ? SyncRunState.IN_DOUBT
                            : stopped.getAsBoolean() ? SyncRunState.CANCELLED : SyncRunState.FAILED,
                    json.writeValueAsString(Map.of("writeResult", result)));
            if (!submitted[0]) check(stopped);
            throw new IllegalStateException("Page write not verified");
        }
        boolean unknown = result.receipts().stream().anyMatch(r -> r.delivery() != VerifiedBatchExecutor.Delivery.ACKNOWLEDGED);
        ledger.transition(slice, 4, unknown ? SyncRunState.IN_DOUBT : SyncRunState.ACKNOWLEDGED,
                json.writeValueAsString(Map.of("writeResult", result)));
        ledger.transition(slice, 5, SyncRunState.VERIFIED,
                proof(result.verifiedRows(), page.sourceFingerprint(), "ledger:" + slice + ":revision:5", unknown));
        progress.verifiedRows += result.verifiedRows(); progress.uncertain = false;
        progress.currentSlice = null;
        progress.readbackRefs.add("ledger:" + slice + ":revision:5");
    }
    private String proof(int rows, String fingerprint, String evidence, boolean writerStopped) throws Exception {
        return json.writeValueAsString(Map.of("verification", Map.of("passed", true, "expectedRows", rows,
                "actualRows", rows, "matchedRows", rows, "mismatchedRows", 0, "duplicateKeys", 0,
                "missingKeys", 0, "sourceFingerprint", fingerprint, "readbackEvidence", evidence, "writerStopped", writerStopped)));
    }
    private String emptyProof(String evidence) throws Exception {
        return json.writeValueAsString(Map.of("sourceComplete", true, "returnedRows", 0, "submittedRows", 0, "responseEvidence", evidence));
    }
    private void finishFailure(String id, SyncRunState state, Exception failure) throws Exception {
        var entry = ledger.get(id);
        if (entry.state().terminal() || entry.state() == SyncRunState.IN_DOUBT) return;
        if (state == SyncRunState.IN_DOUBT && !canTransitionToInDoubt(entry.state())) return;
        if (!entry.state().terminal())
            ledger.transition(id, entry.revision(), state, error(failure));
    }
    private static boolean canTransitionToInDoubt(SyncRunState state) {
        return Set.of(SyncRunState.RUNNING, SyncRunState.SUBMITTED, SyncRunState.ACKNOWLEDGED).contains(state);
    }
    private String error(Exception failure) throws Exception {
        var detail = new LinkedHashMap<String,Object>();
        detail.put("errorCode", failure.getClass().getSimpleName());
        // 记录本地代码位置以便排查预检失败，
        // 不持久化供应商消息、请求体或凭据。
        detail.put("errorOrigin", Arrays.stream(failure.getStackTrace()).limit(12)
                .map(StackTraceElement::toString).toList());
        var causes = new ArrayList<Map<String,Object>>();
        for (Throwable cause = failure.getCause(); cause != null && causes.size() < 4; cause = cause.getCause())
            causes.add(Map.of("errorCode", cause.getClass().getSimpleName(), "origin",
                    Arrays.stream(cause.getStackTrace()).limit(8).map(StackTraceElement::toString).toList()));
        if (!causes.isEmpty()) detail.put("causeOrigins", causes);
        // PageExecutor 的原因由本地生成，只含错误分类，不含请求凭据。
        if (failure instanceof PageExecutor.Incomplete incomplete) {
            detail.put("sourceReason", incomplete.getMessage());
            detail.put("consumedPages", incomplete.consumedPages());
            detail.put("consumedRows", incomplete.consumedRows());
        }
        return json.writeValueAsString(detail);
    }
    private static void check(BooleanSupplier stopped) {
        if (stopped.getAsBoolean()) throw new CancellationException("Sync cancelled or deadline elapsed");
    }
    private static String fingerprint(List<String> parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
