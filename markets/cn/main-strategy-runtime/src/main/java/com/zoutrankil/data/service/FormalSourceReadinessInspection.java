package com.zoutrankil.data.service;

import com.zoutrankil.batch.postclose.DataReadiness;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

/** 清点来源并可选地串行复核各执行组件；本检查本身不签发 DataReady 证书。 */
public final class FormalSourceReadinessInspection {
    @FunctionalInterface
    public interface Identity {
        String get() throws Exception;
    }

    @FunctionalInterface
    public interface Revalidator {
        String run(String runId) throws Exception;
    }

    public record Source(
            String dataset,
            String jobId,
            Identity table,
            Identity target,
            Revalidator revalidator) {}

    private final Path ledgerPath;
    private final Map<String, Source> sources;

    public FormalSourceReadinessInspection(Path ledgerPath, List<Source> sources) {
        this.ledgerPath = Objects.requireNonNull(ledgerPath);
        var registry = new HashMap<String, Source>();
        for (var source : sources)
            if (registry.putIfAbsent(source.dataset(), source) != null)
                throw new IllegalArgumentException("Duplicate source adapter");
        this.sources = Map.copyOf(registry);
    }

    public Map<String, Object> inspect(LocalDate date, boolean revalidate) throws Exception {
        Objects.requireNonNull(date);
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var reports = new ArrayList<Map<String, Object>>();
        for (String dataset : new TreeSet<>(DataReadiness.REQUIRED_SOURCES)) {
            var item = new LinkedHashMap<String, Object>();
            item.put("dataset", dataset);
            reports.add(item);
            var source = sources.get(dataset);
            if (source == null) {
                item.put("status", "ADAPTER_NOT_CONNECTED");
                continue;
            }
            try {
                if (!dataset.equals(source.table().get())) {
                    item.put("status", "FORMAL_TARGET_NOT_BOUND");
                    continue;
                }
                String target = source.target().get();
                String cursor = null;
                var candidates = new ArrayList<SyncRunLedger.RunSummary>();
                boolean exhausted = false;
                for (int page = 0; page < 20; page++) {
                    var rows = ledger.coveringHistory(source.jobId(), target, date, cursor, 1000);
                    candidates.addAll(rows);
                    if (rows.size() < 1000) {
                        exhausted = true;
                        break;
                    }
                    cursor = rows.getLast().id();
                }
                if (!exhausted) throw new IllegalStateException("Source candidate budget exceeded");
                item.put("candidateCount", candidates.size());
                item.put(
                        "candidateStates",
                        candidates.stream()
                                .map(
                                        r ->
                                                Map.of(
                                                        "runId",
                                                        r.id(),
                                                        "state",
                                                        r.state(),
                                                        "logicalDate",
                                                        r.logicalDate()))
                                .toList());
                var chosen =
                        candidates.stream()
                                .filter(
                                        r ->
                                                r.state() == SyncRunState.VERIFIED
                                                        || r.state() == SyncRunState.VERIFIED_EMPTY)
                                .max(
                                        Comparator.comparing(SyncRunLedger.RunSummary::updatedAt)
                                                .thenComparing(SyncRunLedger.RunSummary::id));
                if (chosen.isEmpty()) {
                    item.put(
                            "status",
                            candidates.isEmpty() ? "NO_COVERING_RUN" : "NO_COMPLETED_CANDIDATE");
                    continue;
                }
                item.put("selectedRunId", chosen.get().id());
                if (!revalidate) {
                    item.put("status", "REVALIDATION_REQUIRED");
                    continue;
                }
                // 全部适配器使用原冻结区间，并重新获取该区间的完整来源数据。
                // 串行执行以遵守共用凭据预算的租约约束。
                String receipt = source.revalidator().run(chosen.get().id());
                if (!target.equals(source.target().get()))
                    throw new IllegalStateException("Source target changed after revalidation");
                item.put("receipt", receipt);
                item.put("status", "SOURCE_REVALIDATED");
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()
                        || failure instanceof CancellationException) throw failure;
                item.put("status", "INSPECTION_FAILED");
                item.put("failureType", failure.getClass().getName());
            }
        }
        return Map.of(
                "schema",
                "java.formal_source_readiness_inspection.v1",
                "dataDate",
                date,
                "readOnly",
                !revalidate,
                "databaseRowsWritten",
                0,
                "dataReady",
                false,
                "certificateIssued",
                false,
                "requiredSources",
                DataReadiness.REQUIRED_SOURCES,
                "sources",
                reports);
    }
}
