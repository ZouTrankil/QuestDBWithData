package com.zoutrankil.market.cn.observation.application;

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
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.market.cn.observation.client.SnapshotEventSource;
import com.zoutrankil.market.cn.observation.mapper.SnapshotEventDefinition;
import com.zoutrankil.market.cn.observation.port.SnapshotEventTables;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 单日全来源的多重记录 owner；所有发送只发生在有界stage，正式表只做日志化整窗切换。 */
public final class SnapshotEventSyncOwner implements DatasetImplementation, SyncJobOwner {
    private final String dataset;
    private final SnapshotEventTables tables;
    private final SnapshotEventSource source;
    private final Path ledger;

    public SnapshotEventSyncOwner(
            String dataset, SnapshotEventTables tables, TusharePageService pages, Path ledger) {
        SnapshotEventDefinition.dateField(dataset);
        this.dataset = dataset;
        this.tables = tables;
        this.source = new SnapshotEventSource(pages);
        this.ledger = ledger.toAbsolutePath().normalize();
    }

    public String datasetId() {
        return dataset;
    }

    public DatasetDefinition definition() {
        return SnapshotEventDefinition.definition(dataset);
    }

    public Set<Mode> supportedSyncModes() {
        return Set.of(Mode.RECONCILE, Mode.BACKFILL);
    }

    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(job());
    }

    public SyncJobDefinition job() {
        return new SyncJobDefinition(
                "data." + dataset,
                2,
                dataset,
                2,
                "snapshot_event_owner",
                supportedSyncModes(),
                Mode.RECONCILE,
                Map.of("migrateLegacy", new Parameter(ParameterType.BOOLEAN, true, 1, 1, Set.of())),
                "tushare.shared",
                "contract_table.explicit_scope1",
                "questdb.full_key_values",
                new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofMinutes(1)),
                Duration.ofMinutes(40),
                new Budget(1, 1, 1, SnapshotEventDefinition.MAX_SOURCE_ROWS, 16 * 1024 * 1024),
                0,
                List.of(),
                Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"),
                true,
                false);
    }

    public FrozenRequest plan(LocalDate date, LocalDate logical, boolean migrateLegacy) {
        if (date == null
                || logical == null
                || date.isAfter(logical)
                || logical.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw new IllegalArgumentException("需要明确已发生的来源日期及业务日");
        return job().freeze(
                        Mode.RECONCILE,
                        Map.of("migrateLegacy", migrateLegacy),
                        date,
                        date,
                        logical);
    }

    private void validate(FrozenRequest request) {
        if (!request.definition().equals(job())
                || !request.from().equals(request.to())
                || request.from().isAfter(request.logicalDate())
                || !request.parameters().keySet().equals(Set.of("migrateLegacy")))
            throw new IllegalArgumentException("快照请求不符合原v2协议");
    }

    private void layout(FrozenRequest request, SnapshotEventTables.Snapshot before) {
        if (before.identity().schemaVersion() == 1
                && !Boolean.TRUE.equals(request.parameters().get("migrateLegacy")))
            throw new IllegalArgumentException("旧键表须显式选择 migrateLegacy=true；将保留原表backup并切换新物理对象");
    }

    public Map<String, Object> checkSource(FrozenRequest request) throws Exception {
        validate(request);
        var before = tables.snapshot(dataset);
        String id = "snapshot-event-" + dataset + "-" + UUID.randomUUID();
        Path folder = ledger.getParent().resolve("sync-evidence").resolve(id);
        Files.createDirectories(folder);
        var frozen =
                source.collect(
                        dataset,
                        request.from(),
                        folder,
                        () -> Thread.currentThread().isInterrupted());
        var expected = SnapshotEventPublication.expected(dataset, before, frozen);
        if (!before.equals(tables.snapshot(dataset)))
            throw new IllegalStateException("来源核查期间正式表已改变");
        return Map.of(
                "dataset",
                dataset,
                "sourceRows",
                frozen.rows().size(),
                "sourceComplete",
                true,
                "raw",
                frozen.rawPath(),
                "rawSha256",
                frozen.rawSha256(),
                "targetSchemaVersion",
                before.identity().schemaVersion(),
                "targetId",
                before.identity().targetId(),
                "expectedRows",
                expected.size(),
                "productionWritten",
                false);
    }

    public SyncJobRunner.Result run(FrozenRequest request) throws Exception {
        return execute(request, null);
    }

    public SyncJobRunner.Result resume(String previous) throws Exception {
        var restored = FrozenRunRequest.restore(ledger, previous, job());
        Path evidence = new SnapshotEventPublication(dataset, ledger, tables).folder(previous);
        if (Files.exists(evidence.resolve("stage-intent.json")))
            throw new IllegalStateException("原stage有副作用，必须核对原运行，不可恢复重发");
        if (!tables.snapshot(dataset).identity().targetId().equals(restored.targetId()))
            throw new IllegalStateException("恢复原来源请求时物理目标已改变");
        return execute(restored.request(), previous);
    }

    private SyncJobRunner.Result execute(FrozenRequest request, String previous) throws Exception {
        validate(request);
        String id = "snapshot-event-" + dataset + "-" + UUID.randomUUID();
        var initial = tables.snapshot(dataset);
        layout(request, initial);
        return SyncRunExecution.execute(
                ledger,
                id,
                previous,
                initial.identity().targetId(),
                request,
                "快照取消状态不可读",
                () -> {
                    var publication = new SnapshotEventPublication(dataset, ledger, tables);
                    return new SyncJobRunner.Adapter<Map<String, Object>, String>() {
                        SnapshotEventTables.Snapshot stage;
                        final VerifiedBatchExecutor.Codec<Map<String, Object>, String> codec =
                                VerifiedMarketDataStore.codec(definition());

                        public void preflight(FrozenRequest r) throws Exception {
                            publication.requireNoPending();
                            tables.requireNoNativeDependencies();
                            if (!initial.equals(tables.snapshot(dataset)))
                                throw new IllegalStateException("原目标已变化");
                        }

                        public DatasetIntervalLock.Scope conflictScope(FrozenRequest r) {
                            return DatasetIntervalLock.Scope.allDates(dataset);
                        }

                        public int maxWriteBatchRows(FrozenRequest r) {
                            return 1000;
                        }

                        public boolean recoveryRequired(String run) throws Exception {
                            return Files.exists(
                                    publication.folder(run).resolve("stage-intent.json"));
                        }

                        public VerifiedBatchExecutor.Codec<Map<String, Object>, String> codec() {
                            return codec;
                        }

                        public VerifiedBatchExecutor.Port<Map<String, Object>, String> port() {
                            return new VerifiedBatchExecutor.Port<>() {
                                public void preflight() throws Exception {
                                    if (stage == null
                                            || !stage.equals(tables.snapshot(stageName())))
                                        throw new IllegalStateException("已核验stage改变");
                                }

                                // stage 已完成真实发送；通用批次在这里仅验原件，禁止再次追加。
                                public void send(List<Map<String, Object>> rows) throws Exception {
                                    preflight();
                                    var indexed = index(stage.rows());
                                    for (var row : rows)
                                        if (!Objects.equals(indexed.get(codec.key(row)), row))
                                            throw new IllegalStateException("核验批次不属于原stage");
                                }

                                public List<Map<String, Object>> readback(List<String> keys)
                                        throws Exception {
                                    preflight();
                                    var indexed = index(tables.snapshot(stageName()).rows());
                                    var found = new ArrayList<Map<String, Object>>();
                                    for (String key : keys)
                                        if (indexed.containsKey(key)) found.add(indexed.get(key));
                                    return found;
                                }

                                public boolean walSettled() throws Exception {
                                    preflight();
                                    return true;
                                }

                                private String stageName() {
                                    return prepared.stage();
                                }
                            };
                        }

                        SnapshotEventPublication.Prepared prepared;

                        Map<String, Map<String, Object>> index(List<Map<String, Object>> rows) {
                            var map = new HashMap<String, Map<String, Object>>();
                            for (var row : rows)
                                if (map.putIfAbsent(codec.key(row), row) != null)
                                    throw new IllegalStateException("stage 序号重复");
                            return map;
                        }

                        public SyncJobRunner.SourceCompletion fetch(
                                FrozenRequest r,
                                SyncJobRunner.PageConsumer<Map<String, Object>> consumer,
                                BooleanSupplier cancelled)
                                throws Exception {
                            try (var lock = publication.lock()) {
                                publication.requireNoPending();
                                var before = tables.snapshot(dataset);
                                if (!initial.equals(before))
                                    throw new IllegalStateException("来源开始前目标已改变");
                                Path folder = publication.folder(id);
                                Files.createDirectories(folder);
                                var frozen = source.collect(dataset, r.from(), folder, cancelled);
                                prepared = publication.prepare(id, before, frozen);
                                if (!before.equals(tables.snapshot(dataset)))
                                    throw new IllegalStateException("抓取来源时目标已改变");
                                stage =
                                        tables.stage(
                                                prepared.stage(),
                                                prepared.expected(),
                                                folder.resolve("stage-intent.json"),
                                                cancelled);
                                // 先登记并验收所有stage来源页，再发布；发布故障只续接重命名，不能补造页。
                                consumer.accept(
                                        new SyncJobRunner.Page<>(
                                                frozen.rows(),
                                                frozen.rawSha256(),
                                                frozen.rawPath(),
                                                null));
                                publication.publish(prepared, stage, cancelled);
                                Path proof = folder.resolve("completion.json");
                                FileEvidenceStore.writeNewDurable(
                                        proof,
                                        JobDefinitionJson.mapper()
                                                .writeValueAsBytes(
                                                        Map.of(
                                                                "dataset",
                                                                dataset,
                                                                "sourceRows",
                                                                frozen.rows().size(),
                                                                "sourceComplete",
                                                                true,
                                                                "sourceFingerprint",
                                                                frozen.rawSha256(),
                                                                "fullTargetFingerprint",
                                                                stage.fingerprint(),
                                                                "targetId",
                                                                tables.snapshot(dataset)
                                                                        .identity()
                                                                        .targetId(),
                                                                "backup",
                                                                prepared.backup(),
                                                                "source",
                                                                frozen.rawPath())));
                                return new SyncJobRunner.SourceCompletion(
                                        1, frozen.rows().size(), true, proof.toString());
                            }
                        }
                    };
                });
    }

    public SnapshotEventTables.Snapshot reconcile(String run, boolean writerStopped)
            throws Exception {
        return new SnapshotEventPublication(dataset, ledger, tables).reconcile(run, writerStopped);
    }
}
