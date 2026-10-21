package com.zoutrankil.market.cn.futures.application;

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
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.DailySyncEndDate;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.market.cn.futures.mapper.FuturesDayDefinition;
import com.zoutrankil.market.cn.futures.port.FuturesDayTarget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 逐日冻结一个明确合约的来源；不靠股票日历或最大日期推断期货覆盖。 */
public final class FuturesDaySyncOwner implements DatasetImplementation, SyncJobOwner {
    private final SourceContract source;
    private final DatasetDefinition definition;
    private final FuturesDayTarget target;
    private final TusharePageService pages;
    private final Path ledger;

    public FuturesDaySyncOwner(
            String dataset, FuturesDayTarget target, TusharePageService pages, Path ledger) {
        this.definition = FuturesDayDefinition.from(dataset);
        this.source = SourceContract.load(dataset);
        this.target = Objects.requireNonNull(target);
        this.pages = Objects.requireNonNull(pages);
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
                "futures_day_owner",
                supportedSyncModes(),
                Mode.INCREMENTAL,
                Map.of(
                        "code", new Parameter(ParameterType.STRING, true, 32, 1, Set.of()),
                        "observedAt", new Parameter(ParameterType.STRING, true, 64, 1, Set.of())),
                "tushare.shared",
                "futures.explicit_contract_days31",
                "questdb.full_key_values",
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                Duration.ofMinutes(40),
                new Budget(31, 1, 1, 31, 1024 * 1024),
                0,
                List.of(),
                Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"),
                true,
                false);
    }

    private void scope(String code, LocalDate from, LocalDate to, LocalDate logical) {
        long days =
                ChronoUnit.DAYS.between(Objects.requireNonNull(from), Objects.requireNonNull(to))
                        + 1;
        if (days < 1
                || days > 31
                || to.isAfter(logical)
                || to.isAfter(
                        DailySyncEndDate.resolve(null, ZonedDateTime.now(DailySyncEndDate.ZONE))))
            throw new IllegalArgumentException("期货请求需 1..31 个明确自然日，且已过盘后来源边界");
        sourceRequest(code, from);
        sourceRequest(code, to);
    }

    private SourceCollector.Request sourceRequest(String code, LocalDate date) {
        return new SourceCollector.Request(datasetId(), date, Set.of(code), source.version(), code);
    }

    public FrozenRequest plan(
            String code, LocalDate from, LocalDate to, LocalDate logical, Mode mode) {
        scope(code, from, to, Objects.requireNonNull(logical));
        // 新计划只取一次真实观测开始时刻；字段进入原请求，重启和恢复不再取现在。
        return job().freeze(
                        mode,
                        Map.of(
                                "code",
                                code,
                                "observedAt",
                                Instant.now().truncatedTo(ChronoUnit.MICROS).toString()),
                        from,
                        to,
                        logical);
    }

    private Instant validate(FrozenRequest request) {
        if (!request.definition().equals(job())) throw new IllegalArgumentException("期货原任务定义不匹配");
        scope(
                (String) request.parameters().get("code"),
                request.from(),
                request.to(),
                request.logicalDate());
        String wire = (String) request.parameters().get("observedAt");
        Instant at = Instant.parse(wire);
        if (!at.toString().equals(wire) || at.getNano() % 1000 != 0 || at.isAfter(Instant.now()))
            throw new IllegalArgumentException("期货原观测时刻必须为不丢精度的真实 UTC 时间点");
        return at;
    }

    public SyncJobRunner.Result run(FrozenRequest request) throws Exception {
        return execute(request, null);
    }

    public SyncJobRunner.Result resume(String previous) throws Exception {
        var original = FrozenRunRequest.restore(ledger, previous, job());
        if (!original.targetId().equals(target.targetId()))
            throw new IllegalStateException("期货原物理目标已改变");
        return execute(original.request(), previous);
    }

    private record Collected(
            List<Map<String, Object>> rows, List<LocalDate> emptyDates, Path manifest) {}

    private Collected collect(
            FrozenRequest request, Instant observed, Path evidence, BooleanSupplier stopped)
            throws Exception {
        var collector = new SourceCollector(evidence);
        var rows = new ArrayList<Map<String, Object>>();
        var empty = new ArrayList<LocalDate>();
        var receipts = new ArrayList<Object>();
        String code = (String) request.parameters().get("code");
        for (LocalDate date = request.from();
                !date.isAfter(request.to());
                date = date.plusDays(1)) {
            if (stopped.getAsBoolean()) throw new CancellationException("期货采集已取消");
            var result =
                    collector.collect(
                            sourceRequest(code, date),
                            (SourceCollector.ContractFetcher)
                                    (contract, params) ->
                                            pages.fetcher(contract, stopped).fetch(params));
            if (!result.completeCoverage() || result.state() != BusinessState.VERIFYING)
                throw new IllegalStateException("期货来源分页、字段或合约范围尚未核验完成");
            var raw = collector.read(result.fingerprint());
            if (raw.rows().size() > 1) throw new IllegalStateException("期货单合约单日出现多条业务键");
            if (raw.rows().isEmpty()) empty.add(date);
            for (var row : raw.rows())
                rows.add(FuturesDayDefinition.physical(datasetId(), row, observed));
            receipts.add(
                    Map.of(
                            "date",
                            date,
                            "artifact",
                            result.artifact(),
                            "fingerprint",
                            result.fingerprint(),
                            "rows",
                            raw.rows().size()));
        }
        Path manifest = evidence.resolve("source-manifest.json");
        FileEvidenceStore.writeNewDurable(
                manifest,
                JobDefinitionJson.canonicalMapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "schema",
                                        "cn.futures.day.source.v1",
                                        "request",
                                        request,
                                        "receipts",
                                        receipts,
                                        "emptyDates",
                                        empty)));
        return new Collected(List.copyOf(rows), List.copyOf(empty), manifest);
    }

    private void stable(String identity) throws Exception {
        if (!identity.equals(target.targetId())) throw new IllegalStateException("期货正式表物理身份已改变");
    }

    private Path admit(FrozenRequest request, Collected collected, String identity, Path evidence)
            throws Exception {
        stable(identity);
        target.preflight();
        var keys =
                collected.emptyDates().stream()
                        .map(
                                date -> {
                                    var row =
                                            Map.<String, Object>of(
                                                    "ts_code",
                                                    request.parameters().get("code"),
                                                    "trade_date",
                                                    date.atStartOfDay(ZoneOffset.UTC)
                                                            .toInstant()
                                                            .toString());
                                    return VerifiedMarketDataStore.codec(definition).key(row);
                                })
                        .toList();
        if (!keys.isEmpty() && !target.readback(keys).isEmpty())
            throw new IllegalStateException("期货源为空但原目标仍有该业务键，不删旧值也不认证完成");
        stable(identity);
        target.preflight();
        Path proof = evidence.resolve("completion.json");
        FileEvidenceStore.writeNewDurable(
                proof,
                JobDefinitionJson.canonicalMapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "sourceManifest",
                                        collected.manifest(),
                                        "sourceManifestSha256",
                                        FileEvidenceStore.sha256(
                                                Files.readAllBytes(collected.manifest())),
                                        "targetId",
                                        identity,
                                        "emptyKeysVerifiedAbsent",
                                        keys,
                                        "sourceComplete",
                                        true)));
        return proof;
    }

    /** 只保存来源与结构检查证据；不创建正式运行、锁或向 QuestDB 发送数据。 */
    public Map<String, Object> checkSource(FrozenRequest request) throws Exception {
        Instant observed = validate(request);
        String identity = target.targetId();
        target.preflight();
        Path evidence =
                ledger.getParent()
                        .resolve("sync-evidence")
                        .resolve("futures-source-" + UUID.randomUUID());
        var collected =
                collect(request, observed, evidence, () -> Thread.currentThread().isInterrupted());
        Path proof = admit(request, collected, identity, evidence);
        return Map.of(
                "dataset",
                datasetId(),
                "sourceCoverageVerified",
                true,
                "targetDataVerified",
                false,
                "questdbWritten",
                false,
                "rows",
                collected.rows().size(),
                "emptyDates",
                collected.emptyDates(),
                "targetId",
                identity,
                "manifest",
                proof);
    }

    private SyncJobRunner.Result execute(FrozenRequest request, String previous) throws Exception {
        Instant observed = validate(request);
        String identity = target.targetId(),
                id = "futures-" + datasetId() + "-" + UUID.randomUUID();
        Path evidence = ledger.getParent().resolve("sync-evidence").resolve(id);
        var codec = VerifiedMarketDataStore.codec(definition);
        var adapter =
                new SyncJobRunner.Adapter<Map<String, Object>, String>() {
                    public void preflight(FrozenRequest r) throws Exception {
                        stable(identity);
                        target.preflight();
                    }

                    public DatasetIntervalLock.Scope conflictScope(FrozenRequest r) {
                        return DatasetIntervalLock.Scope.allDates(datasetId());
                    }

                    public int maxWriteBatchRows(FrozenRequest r) {
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
                                stable(identity);
                                target.preflight();
                            }

                            public void send(List<Map<String, Object>> rows) throws Exception {
                                stable(identity);
                                target.send(rows);
                                stable(identity);
                            }

                            public List<Map<String, Object>> readback(List<String> keys)
                                    throws Exception {
                                stable(identity);
                                var rows = target.readback(keys);
                                stable(identity);
                                return rows;
                            }

                            public boolean walSettled() throws Exception {
                                stable(identity);
                                return target.walSettled();
                            }
                        };
                    }

                    public SyncJobRunner.SourceCompletion fetch(
                            FrozenRequest frozen,
                            SyncJobRunner.PageConsumer<Map<String, Object>> consumer,
                            BooleanSupplier stopped)
                            throws Exception {
                        var collected = collect(frozen, observed, evidence, stopped);
                        Path proof = admit(frozen, collected, identity, evidence);
                        consumer.accept(
                                new SyncJobRunner.Page<>(
                                        collected.rows(),
                                        FileEvidenceStore.sha256(
                                                JobDefinitionJson.canonicalMapper()
                                                        .writeValueAsBytes(collected.rows())),
                                        proof.toString(),
                                        null));
                        stable(identity);
                        if (!target.walSettled())
                            throw new IllegalStateException("期货结束核对时 WAL 尚未稳定");
                        return new SyncJobRunner.SourceCompletion(
                                1, collected.rows().size(), true, proof.toString());
                    }
                };
        return SyncRunExecution.execute(
                ledger, id, previous, identity, request, "期货原运行取消状态不可读", () -> adapter);
    }
}
