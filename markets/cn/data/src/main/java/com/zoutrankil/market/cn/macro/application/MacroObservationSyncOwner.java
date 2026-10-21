package com.zoutrankil.market.cn.macro.application;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.source.SourceCollector;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.market.cn.macro.port.MacroObservationTarget;
import com.zoutrankil.market.cn.observation.mapper.BusinessDateObservationMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 统计期采集接回正式 owner；未发布、缺期和冲突都在正式发送前阻断。 */
public final class MacroObservationSyncOwner implements DatasetImplementation, SyncJobOwner {
    private final SourceContract source;
    private final DatasetDefinition definition;
    private final MacroObservationTarget target;
    private final TusharePageService pages;
    private final Path ledger;

    public MacroObservationSyncOwner(
            SourceContract source,
            DatasetDefinition definition,
            MacroObservationTarget target,
            TusharePageService pages,
            Path ledger) {
        this.source = source;
        this.definition = definition;
        this.target = target;
        this.pages = pages;
        this.ledger = ledger.toAbsolutePath().normalize();
    }

    public String datasetId() {
        return definition.datasetId();
    }

    public DatasetDefinition definition() {
        return definition;
    }

    public Set<Mode> supportedSyncModes() {
        return Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE);
    }

    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(job());
    }

    public SyncJobDefinition job() {
        return new SyncJobDefinition(
                "data." + datasetId(),
                1,
                datasetId(),
                1,
                "macro_observation_owner",
                supportedSyncModes(),
                Mode.INCREMENTAL,
                Map.of(),
                "tushare.shared",
                "macro.observation_periods12",
                "questdb.full_key_values",
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                Duration.ofMinutes(20),
                new Budget(3660, 1, 1, 12, source.maxBytes()),
                0,
                List.of(),
                datasetId().equals("cn_gdp") ? Frequency.EVENT : Frequency.MONTHLY,
                ZoneId.of("Asia/Shanghai"),
                true,
                false);
    }

    public FrozenRequest plan(LocalDate from, LocalDate to, LocalDate logicalDate, Mode mode) {
        if (to.isAfter(logicalDate)) throw new IllegalArgumentException("不能采集未来统计期");
        sourceRequest(from, to);
        long months = ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to.withDayOfMonth(1));
        long periods = datasetId().equals("cn_gdp") ? months / 3 + 1 : months + 1;
        if (periods > 12) throw new IllegalArgumentException("每次最多 12 个明确统计期");
        return job().freeze(mode, Map.of(), from, to, logicalDate);
    }

    private SourceCollector.Request sourceRequest(LocalDate from, LocalDate to) {
        return new SourceCollector.Request(
                datasetId(), to, Set.of(), source.version(), null, from, to);
    }

    public SyncJobRunner.Result run(FrozenRequest request) throws Exception {
        return execute(request, null);
    }

    public SyncJobRunner.Result resume(String previous) throws Exception {
        var saved = FrozenRunRequest.restore(ledger, previous, job());
        if (!saved.targetId().equals(target.targetId()))
            throw new IllegalStateException("宏观恢复目标已重建");
        return execute(saved.request(), previous);
    }

    private SyncJobRunner.Result execute(FrozenRequest request, String previous) throws Exception {
        if (!request.definition().equals(job())) throw new IllegalArgumentException("宏观任务定义不匹配");
        plan(request.from(), request.to(), request.logicalDate(), request.mode());
        String id = "macro-" + datasetId() + "-" + UUID.randomUUID(), identity = target.targetId();
        Path evidence = ledger.getParent().resolve("sync-evidence").resolve(id);
        var collector = new SourceCollector(evidence);
        var codec = VerifiedMarketDataStore.codec(definition);
        var adapter =
                new SyncJobRunner.Adapter<Map<String, Object>, String>() {
                    void stable() throws Exception {
                        if (!identity.equals(target.targetId()))
                            throw new IllegalStateException("宏观物理目标已改变");
                    }

                    public void preflight(FrozenRequest frozen) throws Exception {
                        stable();
                        target.preflight();
                    }

                    public DatasetIntervalLock.Scope conflictScope(FrozenRequest frozen) {
                        return DatasetIntervalLock.Scope.allDates(datasetId());
                    }

                    public int maxWriteBatchRows(FrozenRequest frozen) {
                        return 100;
                    }

                    public Duration visibilityTimeout() {
                        return Duration.ofMinutes(3);
                    }

                    public VerifiedBatchExecutor.Codec<Map<String, Object>, String> codec() {
                        return codec;
                    }

                    public VerifiedBatchExecutor.Port<Map<String, Object>, String> port() {
                        return new VerifiedBatchExecutor.Port<>() {
                            public void preflight() throws Exception {
                                stable();
                                target.preflight();
                            }

                            public void send(List<Map<String, Object>> rows) throws Exception {
                                stable();
                                target.send(rows);
                                stable();
                            }

                            public List<Map<String, Object>> readback(List<String> keys)
                                    throws Exception {
                                stable();
                                var rows = target.readback(keys);
                                stable();
                                return rows;
                            }

                            public boolean walSettled() throws Exception {
                                stable();
                                return target.walSettled();
                            }
                        };
                    }

                    public SyncJobRunner.SourceCompletion fetch(
                            FrozenRequest frozen,
                            SyncJobRunner.PageConsumer<Map<String, Object>> consumer,
                            BooleanSupplier stopped)
                            throws Exception {
                        var collected =
                                collector.collect(
                                        sourceRequest(frozen.from(), frozen.to()),
                                        (SourceCollector.ContractFetcher)
                                                (contract, parameters) ->
                                                        pages.fetcher(contract, stopped)
                                                                .fetch(parameters));
                        if (!collected.completeCoverage()
                                || collected.state() != BusinessState.VERIFYING)
                            throw new IllegalStateException("宏观统计期未全部发布或来源校验未通过，尚未发送正式数据");
                        var raw = collector.read(collected.fingerprint());
                        var rows =
                                raw.rows().stream()
                                        .map(
                                                row ->
                                                        BusinessDateObservationMapper.physical(
                                                                definition, row))
                                        .toList();
                        stable();
                        consumer.accept(
                                new SyncJobRunner.Page<>(
                                        rows,
                                        FileEvidenceStore.sha256(
                                                JobDefinitionJson.canonicalMapper()
                                                        .writeValueAsBytes(rows)),
                                        collected.artifact(),
                                        null));
                        return new SyncJobRunner.SourceCompletion(
                                1, rows.size(), true, collected.artifact());
                    }
                };
        return SyncRunExecution.execute(
                ledger, id, previous, identity, request, "宏观取消状态不可读", () -> adapter);
    }
}
