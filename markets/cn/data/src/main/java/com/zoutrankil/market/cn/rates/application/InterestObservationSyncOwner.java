package com.zoutrankil.market.cn.rates.application;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.source.SourceCollector;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.market.cn.observation.mapper.BusinessDateObservationMapper;
import com.zoutrankil.market.cn.observation.mapper.DateObservationCodec;
import com.zoutrankil.market.cn.observation.port.DateObservationTarget;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 逐观测日核对原来源，包括合法空日；不按股票日历删日，也不把利率日期当作事件时刻。 */
public final class InterestObservationSyncOwner implements DatasetImplementation, SyncJobOwner {
    public static final Set<String> DATASETS = Set.of("shibor", "hibor", "shibor_lpr");
    private final SourceContract source;
    private final DatasetDefinition definition;
    private final DateObservationTarget target;
    private final TusharePageService pages;
    private final Path ledger;

    public static DatasetDefinition writable(DatasetDefinition read) {
        if (!DATASETS.contains(read.datasetId())) throw new IllegalArgumentException("未知利率观测表");
        return new DatasetDefinition(
                read.datasetId(),
                read.schemaVersion(),
                read.provider(),
                "interest_observation_owner",
                read.objectName(),
                read.objectKind(),
                read.columns(),
                read.businessKey(),
                read.dedupKey(),
                read.designatedTimestamp(),
                read.partition(),
                read.wal(),
                Set.of(Capability.READ, Capability.WRITE),
                read.dependencies(),
                "沿用已核对的 v2 逻辑映射和原 YEAR/WAL 物理布局；新增正式 owner，不改原列、来源请求或旧归档");
    }

    public InterestObservationSyncOwner(
            SourceContract source,
            DatasetDefinition definition,
            DateObservationTarget target,
            TusharePageService pages,
            Path ledger) {
        if (!DATASETS.contains(source.dataset())
                || !source.dataset().equals(definition.datasetId()))
            throw new IllegalArgumentException("利率来源和目标定义不一致");
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
                definition.schemaVersion(),
                "interest_observation_owner",
                supportedSyncModes(),
                Mode.INCREMENTAL,
                Map.of(),
                "tushare.shared",
                "interest.observation_days31",
                "questdb.full_key_values",
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                Duration.ofMinutes(20),
                new Budget(31, 31, 31, 31, source.maxBytes()),
                0,
                List.of(),
                Frequency.DAILY,
                ZoneId.of("Asia/Shanghai"),
                true,
                false);
    }

    public FrozenRequest plan(LocalDate from, LocalDate to, LocalDate logicalDate, Mode mode) {
        if (from.isAfter(to) || to.isAfter(logicalDate) || ChronoUnit.DAYS.between(from, to) >= 31)
            throw new IllegalArgumentException("利率同步需要不超过 31 个明确自然日，不能取未来日期");
        return job().freeze(mode, Map.of(), from, to, logicalDate);
    }

    public SyncJobRunner.Result run(FrozenRequest request) throws Exception {
        return execute(request, null);
    }

    public SyncJobRunner.Result resume(String previous) throws Exception {
        var saved = FrozenRunRequest.restore(ledger, previous, job());
        if (!saved.targetId().equals(target.targetId()))
            throw new IllegalStateException("利率原物理目标已重建");
        return execute(saved.request(), previous);
    }

    private SyncJobRunner.Result execute(FrozenRequest request, String previous) throws Exception {
        if (!request.definition().equals(job())) throw new IllegalArgumentException("利率冻结定义不符");
        plan(request.from(), request.to(), request.logicalDate(), request.mode());
        String id = "interest-" + datasetId() + "-" + UUID.randomUUID(),
                identity = target.targetId();
        Path evidence = ledger.getParent().resolve("sync-evidence").resolve(id);
        var collector = new SourceCollector(evidence);
        var codec = new DateObservationCodec(definition);
        var adapter =
                new SyncJobRunner.Adapter<Map<String, Object>, String>() {
                    void stable() throws Exception {
                        if (!identity.equals(target.targetId()))
                            throw new IllegalStateException("利率物理目标已改变");
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
                        int count = 0, total = 0;
                        var receipts = new ArrayList<String>();
                        for (LocalDate date = frozen.from();
                                !date.isAfter(frozen.to());
                                date = date.plusDays(1)) {
                            if (stopped.getAsBoolean()) throw new CancellationException();
                            var request =
                                    new SourceCollector.Request(
                                            datasetId(), date, Set.of(), source.version(), null);
                            var collected =
                                    collector.collect(
                                            request,
                                            (SourceCollector.ContractFetcher)
                                                    (contract, parameters) ->
                                                            pages.fetcher(contract, stopped)
                                                                    .fetch(parameters));
                            if (!collected.completeCoverage()
                                    || collected.state() != BusinessState.VERIFYING)
                                throw new IllegalStateException("利率观测日来源核验未完成");
                            var rows =
                                    collector.read(collected.fingerprint()).rows().stream()
                                            .map(
                                                    row ->
                                                            BusinessDateObservationMapper.physical(
                                                                    definition, row))
                                            .toList();
                            stable();
                            String fingerprint =
                                    FileEvidenceStore.sha256(
                                            JobDefinitionJson.canonicalMapper()
                                                    .writeValueAsBytes(
                                                            Map.of(
                                                                    "source",
                                                                    collected.fingerprint(),
                                                                    "rows",
                                                                    rows)));
                            consumer.accept(
                                    new SyncJobRunner.Page<>(
                                            rows, fingerprint, collected.artifact(), null));
                            receipts.add(collected.artifact());
                            count++;
                            total += rows.size();
                        }
                        stable();
                        Path proof = evidence.resolve("completion.json");
                        FileEvidenceStore.writeNewDurable(
                                proof,
                                JobDefinitionJson.canonicalMapper()
                                        .writeValueAsBytes(
                                                Map.of(
                                                        "from",
                                                        frozen.from(),
                                                        "to",
                                                        frozen.to(),
                                                        "targetId",
                                                        identity,
                                                        "pages",
                                                        count,
                                                        "rows",
                                                        total,
                                                        "receipts",
                                                        receipts,
                                                        "sourceComplete",
                                                        true)));
                        return new SyncJobRunner.SourceCompletion(
                                count, total, true, proof.toString());
                    }
                };
        return SyncRunExecution.execute(
                ledger, id, previous, identity, request, "利率取消状态不可读", () -> adapter);
    }
}
