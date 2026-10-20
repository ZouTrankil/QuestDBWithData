package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bridges selected, manifest-certified feature Parquet rows into verified D086 batches. */
final class L2DailyFeaturesSyncAdapter
        implements SyncJobRunner.Adapter<L2DailyFeatures, L2DailyFeaturesKey> {
    private final L2DailyFeaturesParquetSource source;
    private final L2DailyFeaturesParquetSource.Inspection inspection;
    private final List<String> symbols;
    private final int maxRows;
    private final int maxFiles;
    private final Duration timeout;
    private final L2DailyFeaturesWriteSession port;
    private final SyncJobDefinition definition;
    private final boolean verifyScope;

    L2DailyFeaturesSyncAdapter(
            L2DailyFeaturesParquetSource source,
            L2DailyFeaturesParquetSource.Inspection inspection,
            List<String> symbols,
            SyncJobDefinition definition,
            L2DailyFeaturesWriteSession port) {
        this(source, inspection, symbols, definition, port, definition.version() == 2);
    }

    L2DailyFeaturesSyncAdapter(
            L2DailyFeaturesParquetSource source,
            L2DailyFeaturesParquetSource.Inspection inspection,
            List<String> symbols,
            SyncJobDefinition definition,
            L2DailyFeaturesWriteSession port,
            boolean verifyScope) {
        this.verifyScope = verifyScope;
        this.definition = Objects.requireNonNull(definition);
        if (!definition.equals(L2DailyFeaturesJobService.definition(definition.version())))
            throw new IllegalArgumentException("L2 日特征来源只接 canonical 原版本定义");
        this.source = Objects.requireNonNull(source);
        this.inspection = Objects.requireNonNull(inspection);
        this.symbols = List.copyOf(symbols);
        this.maxRows = definition.budget().maxRows();
        this.maxFiles = definition.budget().maxSlices();
        this.timeout = definition.timeout();
        this.port = Objects.requireNonNull(port);
        // 无版本标记的旧会话只属于 v1；显式新会话必须匹配原定义。
        int writerVersion =
                port instanceof L2DailyFeaturesWriteSession.Versioned selected
                        ? selected.datasetVersion()
                        : 1;
        if (writerVersion != definition.datasetVersion())
            throw new IllegalArgumentException("L2 来源会话布局与冻结契约不同");
    }

    @Override
    public void preflight(SyncJobDefinition.FrozenRequest request) throws Exception {
        if (!request.definition().equals(definition)
                || !inspection.from().equals(request.from())
                || !inspection.to().equals(request.to()))
            throw new IllegalArgumentException(
                    "Frozen D086 request differs from its Parquet interval");
        // v2 计划把完整 inspection 固定在请求里；旧请求不补字段或换身份。
        if (definition.version() == 2
                && !JobDefinitionJson.mapper()
                        .writeValueAsString(inspection)
                        .equals(request.parameters().get("source_inspection")))
            throw new IllegalStateException("L2 原冻结来源 inspection 已变化");
        String expectedRoot = request.parameters().get("source_root_id").toString();
        if (!expectedRoot.equals(source.sourceRootIdentity()))
            throw new IllegalStateException("D086 source root changed after planning");
        port.preflight();
        var current = source.inspect(request.from(), request.to(), symbols, maxRows, maxFiles);
        if (!inspection.equals(current))
            throw new IllegalStateException("D086 source changed after planning");
    }

    @Override
    public SyncJobRunner.SourceCompletion fetch(
            SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<L2DailyFeatures> consumer,
            BooleanSupplier cancelled)
            throws Exception {
        var fingerprints = new ArrayList<String>();
        var completion =
                source.stream(
                        inspection,
                        symbols,
                        maxRows,
                        maxFiles,
                        page -> {
                            fingerprints.add(page.sourceFingerprint());
                            consumer.accept(page);
                        },
                        cancelled,
                        timeout);
        if (!verifyScope) return completion;
        // 发送后的最终验收独立重读完整来源与全部键值，在同一稳定版本内再数所选范围。
        // 数量只用于排除多余键，不能替代来源、完整值或 WAL 验证。
        String before = port.stableVersion();
        if (before == null || before.isBlank()) throw new IllegalStateException("L2 稳定版本缺失");
        var counts = new HashMap<LocalDate, Integer>();
        var keys = new HashSet<L2DailyFeaturesKey>();
        var secondFingerprints = new ArrayList<String>();
        var checked =
                source.stream(
                        inspection,
                        symbols,
                        maxRows,
                        maxFiles,
                        page -> {
                            if (cancelled.getAsBoolean())
                                throw new CancellationException("L2 范围复核取消");
                            secondFingerprints.add(page.sourceFingerprint());
                            var expected = new HashMap<L2DailyFeaturesKey, L2DailyFeatures>();
                            for (var row : page.rows()) {
                                if (row.tradeDate().isBefore(request.from())
                                        || row.tradeDate().isAfter(request.to())
                                        || !keys.add(row.key()))
                                    throw new IllegalStateException("L2 来源键重复或越界");
                                expected.put(row.key(), row);
                                counts.merge(row.tradeDate(), 1, Math::addExact);
                            }
                            if (!expected.isEmpty()) {
                                var actual = port.readback(List.copyOf(expected.keySet()));
                                var seen = new HashSet<L2DailyFeaturesKey>();
                                for (var row : actual) {
                                    var original = expected.get(row.key());
                                    if (original == null
                                            || !seen.add(row.key())
                                            || !port.codec().equivalent(original, row))
                                        throw new IllegalStateException("L2 完整范围值回读不一致");
                                }
                                if (seen.size() != expected.size())
                                    throw new IllegalStateException("L2 完整范围缺少业务键");
                            }
                        },
                        cancelled,
                        timeout);
        if (completion == null
                || checked == null
                || !completion.complete()
                || !checked.complete()
                || !fingerprints.equals(secondFingerprints)
                || completion.rows() != keys.size()
                || checked.rows() != completion.rows()
                || checked.pages() != completion.pages())
            throw new IllegalStateException("L2 完整来源范围改变");
        for (LocalDate day = request.from(); !day.isAfter(request.to()); day = day.plusDays(1)) {
            if (cancelled.getAsBoolean()) throw new CancellationException("L2 范围复核取消");
            if (port.countRows(day, symbols) != counts.getOrDefault(day, 0))
                throw new IllegalStateException("L2 所选范围存在多余或缺失的目标键");
        }
        if (!before.equals(port.stableVersion()) || !port.walSettled())
            throw new IllegalStateException("L2 完整复核期间版本变化或 WAL 未稳定");
        String evidence =
                JobDefinitionJson.mapper()
                        .writeValueAsString(
                                Map.of(
                                        "schema",
                                        "java.l2_daily_features_scope.v2",
                                        "layout",
                                        definition.datasetVersion(),
                                        "stableVersion",
                                        before,
                                        "counts",
                                        counts,
                                        "sourceCompletion",
                                        completion.evidence(),
                                        "passed",
                                        true));
        return new SyncJobRunner.SourceCompletion(
                completion.pages(), completion.rows(), true, evidence);
    }

    @Override
    public VerifiedBatchExecutor.Codec<L2DailyFeatures, L2DailyFeaturesKey> codec() {
        return port.codec();
    }

    @Override
    public VerifiedBatchExecutor.Port<L2DailyFeatures, L2DailyFeaturesKey> port() {
        return port;
    }
}
