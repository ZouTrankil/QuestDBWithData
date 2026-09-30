package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.L2IntradayBarFeatures;
import com.zoutrankil.data.domain.L2IntradayBarFeaturesDataset;
import com.zoutrankil.data.domain.L2IntradayBarFeaturesKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.mapper.L2IntradayBarFeaturesMapper;
import com.zoutrankil.data.repository.L2IntradayBarFeaturesWritePort;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Bridges selected, manifest-certified feature Parquet rows into verified D087 batches. */
final class L2IntradayBarFeaturesSyncAdapter
        implements SyncJobRunner.Adapter<L2IntradayBarFeatures, L2IntradayBarFeaturesKey> {
    private final L2IntradayBarFeaturesParquetSource source;
    private final L2IntradayBarFeaturesParquetSource.Inspection inspection;
    private final List<String> symbols;
    private final int maxRows;
    private final int maxFiles;
    private final Duration timeout;
    private final L2IntradayBarFeaturesWritePort port;

    L2IntradayBarFeaturesSyncAdapter(L2IntradayBarFeaturesParquetSource source,
            L2IntradayBarFeaturesParquetSource.Inspection inspection, List<String> symbols,
            SyncJobDefinition definition, L2IntradayBarFeaturesWritePort port) {
        this.source = Objects.requireNonNull(source);
        this.inspection = Objects.requireNonNull(inspection);
        this.symbols = List.copyOf(symbols);
        this.maxRows = definition.budget().maxRows();
        this.maxFiles = definition.budget().maxSlices();
        this.timeout = definition.timeout();
        this.port = Objects.requireNonNull(port);
    }

    @Override public void preflight(SyncJobDefinition.FrozenRequest request) throws Exception {
        if (!request.definition().equals(L2IntradayBarFeaturesJobService.definition())
                || !inspection.from().equals(request.from()) || !inspection.to().equals(request.to()))
            throw new IllegalArgumentException("Frozen D087 request differs from its Parquet interval");
        String expectedRoot = request.parameters().get("source_root_id").toString();
        if (!expectedRoot.equals(source.sourceRootIdentity()))
            throw new IllegalStateException("D087 source root changed after planning");
        port.preflight();
        var current = source.inspect(request.from(), request.to(), symbols, maxRows, maxFiles);
        if (!inspection.equals(current)) throw new IllegalStateException("D087 source changed after planning");
    }

    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<L2IntradayBarFeatures> consumer, BooleanSupplier cancelled) throws Exception {
        return source.stream(inspection, symbols, maxRows, maxFiles, consumer, cancelled, timeout);
    }

    @Override public VerifiedBatchExecutor.Codec<L2IntradayBarFeatures, L2IntradayBarFeaturesKey> codec() {
        return L2IntradayBarFeaturesWritePort.CODEC;
    }

    @Override public VerifiedBatchExecutor.Port<L2IntradayBarFeatures, L2IntradayBarFeaturesKey> port() { return port; }
}
