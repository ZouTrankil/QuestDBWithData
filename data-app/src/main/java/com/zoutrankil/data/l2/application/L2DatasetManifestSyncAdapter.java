package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.L2DatasetManifest;
import com.zoutrankil.data.domain.L2DatasetManifestKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.l2.port.L2DatasetManifestWriteSession;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One local Parquet page at a time, with file hashes as stable source-version evidence. */
final class L2DatasetManifestSyncAdapter
        implements SyncJobRunner.Adapter<L2DatasetManifest, L2DatasetManifestKey> {
    private final L2DatasetManifestParquetSource source;
    private final L2DatasetManifestParquetSource.Inspection inspection;
    private final List<String> symbols;
    private final int maxRows;
    private final int maxFiles;
    private final Duration timeout;
    private final L2DatasetManifestWriteSession port;

    L2DatasetManifestSyncAdapter(L2DatasetManifestParquetSource source,
            L2DatasetManifestParquetSource.Inspection inspection, List<String> symbols,
            SyncJobDefinition definition, L2DatasetManifestWriteSession port) {
        this.source = Objects.requireNonNull(source);
        this.inspection = Objects.requireNonNull(inspection);
        this.symbols = List.copyOf(symbols);
        this.maxRows = definition.budget().maxRows();
        this.maxFiles = definition.budget().maxSlices();
        this.timeout = definition.timeout();
        this.port = Objects.requireNonNull(port);
    }

    @Override public void preflight(SyncJobDefinition.FrozenRequest request) throws Exception {
        if (!request.definition().equals(L2DatasetManifestJobService.definition())
                || !inspection.from().equals(request.from()) || !inspection.to().equals(request.to()))
            throw new IllegalArgumentException("Frozen D085 request differs from the inspected Parquet interval");
        String expectedRoot = request.parameters().get("source_root_id").toString();
        if (!expectedRoot.equals(source.sourceRootIdentity()))
            throw new IllegalStateException("D085 source root changed after planning");
        port.preflight();
        var current = source.inspect(request.from(), request.to(), symbols, maxRows, maxFiles);
        if (!inspection.equals(current)) throw new IllegalStateException("D085 source changed after planning");
    }

    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<L2DatasetManifest> consumer, BooleanSupplier cancelled) throws Exception {
        return source.stream(inspection, symbols, maxRows, maxFiles, consumer, cancelled, timeout);
    }

    @Override public VerifiedBatchExecutor.Codec<L2DatasetManifest, L2DatasetManifestKey> codec() {
        return port.codec();
    }

    @Override public VerifiedBatchExecutor.Port<L2DatasetManifest, L2DatasetManifestKey> port() { return port; }
}
