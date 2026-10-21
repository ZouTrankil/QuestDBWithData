package com.zoutrankil.market.cn.chips.application;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.source.SourceCollector;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
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
import com.zoutrankil.data.service.DailyTradingSessions;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.market.cn.chips.mapper.CyqPerfDefinition;
import com.zoutrankil.market.cn.chips.port.CyqPerfTarget;
import com.zoutrankil.market.cn.observation.mapper.BusinessDateObservationMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 按明确证券集合采集单个开市日；全部来源齐备后才开始正式发送。 */
public final class CyqPerfSyncOwner implements DatasetImplementation, SyncJobOwner {
    public static final int MAX_CODES = 6000;
    private final SourceContract source = SourceContract.load("cyq_perf");
    private final CyqPerfTarget target;
    private final TusharePageService pages;
    private final ExchangeCalendarReadPort calendars;
    private final Path ledger;

    public CyqPerfSyncOwner(
            CyqPerfTarget target,
            TusharePageService pages,
            ExchangeCalendarReadPort calendars,
            Path ledger) {
        this.target = Objects.requireNonNull(target);
        this.pages = Objects.requireNonNull(pages);
        this.calendars = Objects.requireNonNull(calendars);
        this.ledger = ledger.toAbsolutePath().normalize();
    }

    public DatasetDefinition definition() {
        return CyqPerfDefinition.DEFINITION;
    }

    public String datasetId() {
        return "cyq_perf";
    }

    public Set<Mode> supportedSyncModes() {
        return Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE);
    }

    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(job());
    }

    public SyncJobDefinition job() {
        return new SyncJobDefinition(
                "data.cyq_perf",
                1,
                "cyq_perf",
                1,
                "cyq_perf_owner",
                supportedSyncModes(),
                Mode.INCREMENTAL,
                Map.of(
                        "codes",
                        new Parameter(ParameterType.STRING_LIST, true, 9, MAX_CODES, Set.of())),
                "tushare.shared",
                "cyq_perf.explicit_codes_day",
                "questdb.full_key_values",
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                Duration.ofHours(24),
                new Budget(1, 1, 1, MAX_CODES, 8 * 1024 * 1024),
                0,
                List.of(),
                Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"),
                true,
                false);
    }

    public FrozenRequest plan(
            List<String> codes, LocalDate date, LocalDate logicalDate, Mode mode) {
        Objects.requireNonNull(date);
        Objects.requireNonNull(logicalDate);
        if (date.isAfter(logicalDate) || date.isBefore(LocalDate.of(2018, 1, 1)))
            throw new IllegalArgumentException("筹码交易日须在来源历史范围内，且不能晚于冻结业务日");
        // 与现有盘后链路一致，20:30 前不认证当日仍可能更新的来源。
        if (date.isAfter(DailySyncEndDate.resolve(null, ZonedDateTime.now(DailySyncEndDate.ZONE))))
            throw new IllegalArgumentException("筹码交易日尚未到来源完成边界");
        if (codes.isEmpty()
                || codes.size() > MAX_CODES
                || new HashSet<>(codes).size() != codes.size())
            throw new IllegalArgumentException("筹码范围须为 1..6000 只不重复证券");
        var sorted = codes.stream().sorted().toList();
        for (String code : sorted) sourceRequest(code, date);
        if (!DailyTradingSessions.read(calendars, date, date).equals(List.of(date)))
            throw new IllegalArgumentException("筹码交易日须有完整 SSE 开市日证据");
        return job().freeze(mode, Map.of("codes", sorted), date, date, logicalDate);
    }

    private SourceCollector.Request sourceRequest(String code, LocalDate date) {
        // 官方接口要求 ts_code；逐只请求，不能把空代码的市场查询当作完整来源。
        return new SourceCollector.Request("cyq_perf", date, Set.of(code), source.version(), code);
    }

    public SyncJobRunner.Result run(FrozenRequest request) throws Exception {
        return execute(request, null);
    }

    public SyncJobRunner.Result resume(String previous) throws Exception {
        var original = FrozenRunRequest.restore(ledger, previous, job());
        if (!original.targetId().equals(target.targetId()))
            throw new IllegalStateException("筹码原目标物理身份已改变");
        return execute(original.request(), previous);
    }

    @SuppressWarnings("unchecked")
    private void validate(FrozenRequest request) {
        if (!request.definition().equals(job()) || !request.from().equals(request.to()))
            throw new IllegalArgumentException("筹码原任务定义或单日范围不符");
        var replanned =
                plan(
                        (List<String>) request.parameters().get("codes"),
                        request.from(),
                        request.logicalDate(),
                        request.mode());
        if (!replanned.parameters().equals(request.parameters()))
            throw new IllegalArgumentException("筹码范围不是原规范冻结请求");
    }

    private record Collected(List<Map<String, Object>> rows, Path manifest) {}

    private Collected collect(FrozenRequest request, Path evidence, BooleanSupplier stopped)
            throws Exception {
        var collector = new SourceCollector(evidence);
        var rows = new ArrayList<Map<String, Object>>();
        var receipts = new ArrayList<Object>();
        for (Object code : (List<?>) request.parameters().get("codes")) {
            if (stopped.getAsBoolean()) throw new CancellationException("筹码采集已取消");
            var collected =
                    collector.collect(
                            sourceRequest(code.toString(), request.from()),
                            (SourceCollector.ContractFetcher)
                                    (contract, parameters) ->
                                            pages.fetcher(contract, stopped).fetch(parameters));
            if (!collected.completeCoverage() || collected.state() != BusinessState.VERIFYING)
                throw new IllegalStateException("筹码来源缺证券、字段或分页证据，尚未发送正式数据");
            var frozen = collector.read(collected.fingerprint());
            if (frozen.rows().size() != 1) throw new IllegalStateException("单证券单日筹码来源必须恰有一个完整业务键");
            rows.add(
                    BusinessDateObservationMapper.physical(definition(), frozen.rows().getFirst()));
            receipts.add(
                    Map.of(
                            "code",
                            code,
                            "fingerprint",
                            collected.fingerprint(),
                            "artifact",
                            collected.artifact(),
                            "pages",
                            frozen.sourcePages()));
        }
        Path manifest = evidence.resolve("source-manifest.json");
        FileEvidenceStore.writeNewDurable(
                manifest,
                JobDefinitionJson.canonicalMapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "schema",
                                        "cn.cyq_perf.source.v1",
                                        "request",
                                        request,
                                        "receipts",
                                        receipts)));
        return new Collected(List.copyOf(rows), manifest);
    }

    /** 只验来源和目标结构，绝不创建正式运行、锁或发送数据；不代表目标数据已验收。 */
    public Map<String, Object> checkSource(FrozenRequest request) throws Exception {
        validate(request);
        String identity = target.targetId();
        target.preflight();
        Path evidence =
                ledger.getParent()
                        .resolve("sync-evidence")
                        .resolve("cyq-source-" + UUID.randomUUID());
        var result = collect(request, evidence, () -> Thread.currentThread().isInterrupted());
        if (!identity.equals(target.targetId()) || !target.walSettled())
            throw new IllegalStateException("筹码目标在检查期间变化");
        return Map.of(
                "sourceCoverageVerified",
                true,
                "targetDataVerified",
                false,
                "questdbWritten",
                false,
                "targetId",
                identity,
                "rows",
                result.rows().size(),
                "manifest",
                result.manifest());
    }

    private SyncJobRunner.Result execute(FrozenRequest request, String previous) throws Exception {
        validate(request);
        String id = "cyq-perf-" + UUID.randomUUID(), identity = target.targetId();
        Path evidence = ledger.getParent().resolve("sync-evidence").resolve(id);
        var codec = VerifiedMarketDataStore.codec(definition());
        var adapter =
                new SyncJobRunner.Adapter<Map<String, Object>, String>() {
                    void stable() throws Exception {
                        if (!identity.equals(target.targetId()))
                            throw new IllegalStateException("筹码正式表物理身份已改变");
                    }

                    public void preflight(FrozenRequest frozen) throws Exception {
                        stable();
                        target.preflight();
                    }

                    public DatasetIntervalLock.Scope conflictScope(FrozenRequest frozen) {
                        return DatasetIntervalLock.Scope.allDates("cyq_perf");
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
                        var result = collect(frozen, evidence, stopped);
                        stable();
                        consumer.accept(
                                new SyncJobRunner.Page<>(
                                        result.rows(),
                                        FileEvidenceStore.sha256(
                                                JobDefinitionJson.canonicalMapper()
                                                        .writeValueAsBytes(result.rows())),
                                        result.manifest().toString(),
                                        null));
                        return new SyncJobRunner.SourceCompletion(
                                1, result.rows().size(), true, result.manifest().toString());
                    }
                };
        return SyncRunExecution.execute(
                ledger, id, previous, identity, request, "筹码原运行取消状态不可读", () -> adapter);
    }
}
