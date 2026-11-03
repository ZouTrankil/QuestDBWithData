package com.zoutrankil.market.cn.observation.application;

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
import com.zoutrankil.data.domain.stock.reference.StockShockDatasets;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.market.cn.observation.mapper.ContractTableDefinition;
import com.zoutrankil.market.cn.observation.port.ContractTableTarget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 一次冻结一个明确来源范围；目录、报告期与公告日分别校验，不猜全市场覆盖。 */
public final class ContractTableSyncOwner implements DatasetImplementation, SyncJobOwner {
    private final SourceContract source;
    private final DatasetDefinition definition;
    private final ContractTableTarget target;
    private final TusharePageService pages;
    private final Path ledger;

    public ContractTableSyncOwner(
            String dataset, ContractTableTarget target, TusharePageService pages, Path ledger) {
        this.definition = ContractTableDefinition.from(dataset);
        this.source = SourceContract.load(dataset);
        this.target = Objects.requireNonNull(target);
        this.pages = Objects.requireNonNull(pages);
        this.ledger = ledger.toAbsolutePath().normalize();
    }

    public String datasetId() {
        return definition.datasetId();
    }

    public DatasetDefinition definition() {
        return Set.of("stk_shock", "stk_high_shock").contains(datasetId())
                ? definition(2)
                : definition;
    }

    /** 新增写入 owner 后仍提供原 v2 公告日读取映射，不能让公开 API 退回裸日期列。 */
    public DatasetDefinition definition(int version) {
        if (version == 1) return definition;
        if (version != 2 || !Set.of("stk_shock", "stk_high_shock").contains(datasetId()))
            throw new IllegalArgumentException("契约表布局版本不支持");
        var read =
                datasetId().equals("stk_shock")
                        ? StockShockDatasets.SHOCK
                        : StockShockDatasets.HIGH_SHOCK;
        return new DatasetDefinition(
                read.datasetId(),
                read.schemaVersion(),
                read.provider(),
                definition.owner(),
                read.objectName(),
                read.objectKind(),
                read.columns(),
                read.businessKey(),
                read.dedupKey(),
                read.designatedTimestamp(),
                read.partition(),
                read.wal(),
                read.capabilities(),
                read.dependencies(),
                read.storageRationale());
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
                "contract_table_owner",
                supportedSyncModes(),
                Mode.INCREMENTAL,
                Map.of(
                        "code", new Parameter(ParameterType.STRING, true, 32, 1, Set.of()),
                        "observedAt", new Parameter(ParameterType.STRING, true, 64, 1, Set.of())),
                "tushare.shared",
                "contract_table.explicit_scope1",
                "questdb.full_key_values",
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                Duration.ofMinutes(40),
                new Budget(1, 1, 1, source.maxRows(), 32 * 1024 * 1024),
                0,
                List.of(),
                Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"),
                true,
                false);
    }

    private void scope(String code, LocalDate from, LocalDate to, LocalDate logical) {
        if (!Objects.requireNonNull(from).equals(to)
                || from.isAfter(logical)
                || logical.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw new IllegalArgumentException("一次只接一个明确来源日期或报告期，不能请求未来业务日");
        if (datasetId().equals("fut_basic")
                && !Set.of("CFFEX", "DCE", "CZCE", "SHFE", "INE", "GFEX").contains(code))
            throw new IllegalArgumentException("目录范围必须为明确交易所，不能用合约代码代替");
        if (datasetId().equals("fut_basic") && !from.equals(logical))
            throw new IllegalArgumentException("目录只查询当前观察日，不提供历史快照回溯");
        if (datasetId().equals("fut_holding")
                && !code.matches("(?:IF|IH|IC|IM|TF|TS|TL|T)[0-9]{4}"))
            throw new IllegalArgumentException("持仓排名正式范围需 CFFEX 具体合约符号，如 IM2612，不能用 IF 品种冒充合约");
        sourceRequest(code, from);
    }

    private SourceCollector.Request sourceRequest(String code, LocalDate date) {
        if (Set.of("disclosure_date", "broker_recommend", "sge_daily").contains(datasetId())) {
            if (!"ALL".equals(code))
                throw new IllegalArgumentException("聚合查询须覆盖完整来源范围，code 必须为 ALL");
            return new SourceCollector.Request(datasetId(), date, Set.of(), source.version(), null);
        }
        return new SourceCollector.Request(datasetId(), date, Set.of(code), source.version(), code);
    }

    public FrozenRequest plan(
            String code, LocalDate from, LocalDate to, LocalDate logical, Mode mode) {
        scope(code, from, to, Objects.requireNonNull(logical));
        if (datasetId().equals("fut_basic")
                && !logical.equals(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw new IllegalArgumentException("当前目录不能补造过去的观察时刻");
        // 同一请求冻结真实开始时刻；恢复不改时刻，过日目录必须另建新观察。
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
        if (!request.definition().equals(job())) throw new IllegalArgumentException("契约表原任务定义不匹配");
        scope(
                (String) request.parameters().get("code"),
                request.from(),
                request.to(),
                request.logicalDate());
        String wire = (String) request.parameters().get("observedAt");
        Instant at = Instant.parse(wire);
        if (!at.toString().equals(wire) || at.getNano() % 1000 != 0 || at.isAfter(Instant.now()))
            throw new IllegalArgumentException("契约表原观测时刻必须为不丢精度的真实 UTC 时间点");
        if (datasetId().equals("fut_basic")
                && (!request.logicalDate()
                                .equals(at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())
                        || !request.logicalDate()
                                .equals(LocalDate.now(ZoneId.of("Asia/Shanghai")))))
            throw new IllegalArgumentException("目录原观察已跨日，不能用当前来源认证旧快照");
        return at;
    }

    public SyncJobRunner.Result run(FrozenRequest request) throws Exception {
        return execute(request, null);
    }

    public SyncJobRunner.Result resume(String previous) throws Exception {
        var original = FrozenRunRequest.restore(ledger, previous, job());
        if (!original.targetId().equals(target.targetId()))
            throw new IllegalStateException("契约表原物理目标已改变");
        return execute(original.request(), previous);
    }

    private record Collected(List<Map<String, Object>> rows, Path manifest) {}

    private Collected collect(
            FrozenRequest request, Instant observed, Path evidence, BooleanSupplier stopped)
            throws Exception {
        if (stopped.getAsBoolean()) throw new CancellationException("契约表采集已取消");
        var collector = new SourceCollector(evidence);
        String code = request.parameters().get("code").toString();
        var result =
                collector.collect(
                        sourceRequest(code, request.from()),
                        (SourceCollector.ContractFetcher)
                                (contract, params) ->
                                        pages.fetcher(contract, stopped).fetch(params));
        if (!result.completeCoverage() || result.state() != BusinessState.VERIFYING)
            throw new IllegalStateException("来源分页、字段或请求范围尚未核验完成");
        var raw = collector.read(result.fingerprint());
        var rows = ContractTableDefinition.physicalRows(datasetId(), raw.rows(), observed);
        Path manifest = evidence.resolve("source-manifest.json");
        FileEvidenceStore.writeNewDurable(
                manifest,
                JobDefinitionJson.canonicalMapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "schema",
                                        "cn.contract.table.source.v1",
                                        "request",
                                        request,
                                        "artifact",
                                        result.artifact(),
                                        "fingerprint",
                                        result.fingerprint(),
                                        "rows",
                                        rows.size(),
                                        "sourceRows",
                                        raw.rows().size(),
                                        "scope",
                                        datasetId().equals("fut_basic")
                                                ? "当前交易所目录，不证明历史挂牌范围"
                                                : "明确请求范围，不证明全历史完整")));
        return new Collected(rows, manifest);
    }

    private void stable(String identity) throws Exception {
        if (!identity.equals(target.targetId())) throw new IllegalStateException("契约表正式表物理身份已改变");
    }

    private record Admission(Path proof, List<Map<String, Object>> retained) {}

    private Admission admit(
            FrozenRequest request, Collected collected, String identity, Path evidence)
            throws Exception {
        stable(identity);
        target.preflight();
        var codec = VerifiedMarketDataStore.codec(definition);
        var incoming = new HashMap<String, Map<String, Object>>();
        for (var row : collected.rows())
            if (incoming.putIfAbsent(codec.key(row), row) != null)
                throw new IllegalStateException("来源出现相同物理键，禁止覆盖不同事件");
        var oldRows = target.readScope(request.parameters().get("code").toString(), request.from());
        var seen = new HashSet<String>();
        var retained = new ArrayList<Map<String, Object>>();
        for (var old : oldRows) {
            String key = codec.key(old);
            if (!seen.add(key)) throw new IllegalStateException("原表存在重复业务键");
            var next = incoming.get(key);
            // UPSERT 不会移除消失键；旧范围未被来源解释时，保留原值并拒绝完整认证。
            if (next == null) {
                // 目录保留旧合约，披露计划保留旧公告版本；本次来源键单列，不认证旧行来源完整。
                if (Set.of("fut_basic", "disclosure_date").contains(datasetId())
                        && !collected.rows().isEmpty()) {
                    retained.add(old);
                    continue;
                }
                throw new IllegalStateException("来源范围缺少原目标键，需核查修订或移除原因");
            }
            // 旧增减持键没有变动区间，区间不同就不能当作同一事件修订。
            if (datasetId().equals("stk_holdertrade")
                    && (!Objects.equals(old.get("begin_date"), next.get("begin_date"))
                            || !Objects.equals(old.get("close_date"), next.get("close_date"))))
                throw new IllegalStateException("增减持区间在旧键下冲突，不能覆盖另一事件");
            if (datasetId().equals("fina_mainbz")
                    && !Objects.equals(old.get("bz_code"), next.get("bz_code")))
                throw new IllegalStateException("主营分类在旧键下冲突，不能覆盖另一分类");
            if (old.get("update_time") != null
                    && Instant.parse(old.get("update_time").toString())
                            .isAfter(
                                    Instant.parse(
                                            request.parameters().get("observedAt").toString())))
                throw new IllegalStateException("目标已有更晚观察，禁止旧请求覆盖新观察");
        }
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
                                        "existingScopeRowsChecked",
                                        oldRows.size(),
                                        "retainedLegacyKeys",
                                        retained.stream().map(codec::key).toList(),
                                        "retainedLegacySha256",
                                        FileEvidenceStore.sha256(
                                                JobDefinitionJson.canonicalMapper()
                                                        .writeValueAsBytes(retained)),
                                        "sourceComplete",
                                        true)));
        return new Admission(proof, List.copyOf(retained));
    }

    /** 只保存来源与结构检查证据；不创建正式运行、锁或向 QuestDB 发送数据。 */
    public Map<String, Object> checkSource(FrozenRequest request) throws Exception {
        Instant observed = validate(request);
        String identity = target.targetId();
        target.preflight();
        Path evidence =
                ledger.getParent()
                        .resolve("sync-evidence")
                        .resolve("contract-table-source-" + UUID.randomUUID());
        var collected =
                collect(request, observed, evidence, () -> Thread.currentThread().isInterrupted());
        var admission = admit(request, collected, identity, evidence);
        Path proof = admission.proof();
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
                "code",
                request.parameters().get("code"),
                "targetId",
                identity,
                "manifest",
                proof);
    }

    private SyncJobRunner.Result execute(FrozenRequest request, String previous) throws Exception {
        Instant observed = validate(request);
        String identity = target.targetId(),
                id = "contract-table-" + datasetId() + "-" + UUID.randomUUID();
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
                        var admission = admit(frozen, collected, identity, evidence);
                        Path proof = admission.proof();
                        consumer.accept(
                                new SyncJobRunner.Page<>(
                                        collected.rows(),
                                        FileEvidenceStore.sha256(
                                                JobDefinitionJson.canonicalMapper()
                                                        .writeValueAsBytes(collected.rows())),
                                        proof.toString(),
                                        null));
                        // 分批回读之外再核对原请求全范围，避免消失键或并发新增键逃过验收。
                        var actual =
                                target.readScope(
                                        frozen.parameters().get("code").toString(), frozen.from());
                        var expected = new HashMap<String, String>();
                        for (var row : admission.retained())
                            expected.put(
                                    codec.key(row),
                                    FileEvidenceStore.sha256(codec.canonicalBytes(row)));
                        for (var row : collected.rows())
                            expected.put(
                                    codec.key(row),
                                    FileEvidenceStore.sha256(codec.canonicalBytes(row)));
                        var found = new HashMap<String, String>();
                        for (var row : actual)
                            if (found.putIfAbsent(
                                            codec.key(row),
                                            FileEvidenceStore.sha256(codec.canonicalBytes(row)))
                                    != null) throw new IllegalStateException("全范围回读出现重复业务键");
                        if (!found.equals(expected))
                            throw new IllegalStateException("全范围键值回读与冻结来源不一致");
                        stable(identity);
                        if (!target.walSettled())
                            throw new IllegalStateException("契约表结束核对时 WAL 尚未稳定");
                        return new SyncJobRunner.SourceCompletion(
                                1, collected.rows().size(), true, proof.toString());
                    }
                };
        return SyncRunExecution.execute(
                ledger, id, previous, identity, request, "契约表原运行取消状态不可读", () -> adapter);
    }
}
