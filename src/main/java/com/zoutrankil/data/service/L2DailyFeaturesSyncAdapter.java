package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.L2DailyFeatures;
import com.zoutrankil.data.domain.L2DailyFeaturesDataset;
import com.zoutrankil.data.domain.L2DailyFeaturesKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.repository.L2DailyFeaturesWritePort;
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
    private final L2DailyFeaturesWritePort port;

    L2DailyFeaturesSyncAdapter(L2DailyFeaturesParquetSource source,
            L2DailyFeaturesParquetSource.Inspection inspection, List<String> symbols,
            SyncJobDefinition definition, L2DailyFeaturesWritePort port) {
        this.source = Objects.requireNonNull(source);
        this.inspection = Objects.requireNonNull(inspection);
        this.symbols = List.copyOf(symbols);
        this.maxRows = definition.budget().maxRows();
        this.maxFiles = definition.budget().maxSlices();
        this.timeout = definition.timeout();
        this.port = Objects.requireNonNull(port);
    }

    @Override public void preflight(SyncJobDefinition.FrozenRequest request) throws Exception {
        if (!request.definition().equals(L2DailyFeaturesJobService.definition())
                || !inspection.from().equals(request.from()) || !inspection.to().equals(request.to()))
            throw new IllegalArgumentException("Frozen D086 request differs from its Parquet interval");
        String expectedRoot = request.parameters().get("source_root_id").toString();
        if (!expectedRoot.equals(source.sourceRootIdentity()))
            throw new IllegalStateException("D086 source root changed after planning");
        port.preflight();
        var current = source.inspect(request.from(), request.to(), symbols, maxRows, maxFiles);
        if (!inspection.equals(current)) throw new IllegalStateException("D086 source changed after planning");
    }

    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<L2DailyFeatures> consumer, BooleanSupplier cancelled) throws Exception {
        return source.stream(inspection, symbols, maxRows, maxFiles, consumer, cancelled, timeout);
    }

    @Override public VerifiedBatchExecutor.Codec<L2DailyFeatures, L2DailyFeaturesKey> codec() {
        return L2DailyFeaturesWritePort.CODEC;
    }

    @Override public VerifiedBatchExecutor.Port<L2DailyFeatures, L2DailyFeaturesKey> port() { return port; }
}
