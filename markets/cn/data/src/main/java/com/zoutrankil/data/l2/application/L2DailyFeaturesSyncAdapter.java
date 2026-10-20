package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
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

    L2DailyFeaturesSyncAdapter(
            L2DailyFeaturesParquetSource source,
            L2DailyFeaturesParquetSource.Inspection inspection,
            List<String> symbols,
            SyncJobDefinition definition,
            L2DailyFeaturesWriteSession port) {
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
        return source.stream(inspection, symbols, maxRows, maxFiles, consumer, cancelled, timeout);
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
