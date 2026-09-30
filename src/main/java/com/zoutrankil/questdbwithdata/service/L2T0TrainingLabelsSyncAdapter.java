package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabels;
import com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelsDataset;
import com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelsKey;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.mapper.L2T0TrainingLabelsMapper;
import com.zoutrankil.questdbwithdata.repository.L2T0TrainingLabelsWritePort;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Bridges selected, manifest-certified feature Parquet rows into verified D089 batches. */
final class L2T0TrainingLabelsSyncAdapter
        implements SyncJobRunner.Adapter<L2T0TrainingLabels, L2T0TrainingLabelsKey> {
    private final L2T0TrainingLabelsParquetSource source;
    private final L2T0TrainingLabelsParquetSource.Inspection inspection;
    private final List<String> symbols;
    private final int maxRows;
    private final int maxFiles;
    private final Duration timeout;
    private final L2T0TrainingLabelsWritePort port;

    L2T0TrainingLabelsSyncAdapter(L2T0TrainingLabelsParquetSource source,
            L2T0TrainingLabelsParquetSource.Inspection inspection, List<String> symbols,
            SyncJobDefinition definition, L2T0TrainingLabelsWritePort port) {
        this.source = Objects.requireNonNull(source);
        this.inspection = Objects.requireNonNull(inspection);
        this.symbols = List.copyOf(symbols);
        this.maxRows = definition.budget().maxRows();
        this.maxFiles = definition.budget().maxSlices();
        this.timeout = definition.timeout();
        this.port = Objects.requireNonNull(port);
    }

    @Override public void preflight(SyncJobDefinition.FrozenRequest request) throws Exception {
        if (!request.definition().equals(L2T0TrainingLabelsJobService.definition())
                || !inspection.from().equals(request.from()) || !inspection.to().equals(request.to()))
            throw new IllegalArgumentException("Frozen D089 request differs from its Parquet interval");
        String expectedRoot = request.parameters().get("source_root_id").toString();
        if (!expectedRoot.equals(source.sourceRootIdentity()))
            throw new IllegalStateException("D089 source root changed after planning");
        port.preflight();
        var current = source.inspect(request.from(), request.to(), symbols, maxRows, maxFiles);
        if (!inspection.equals(current)) throw new IllegalStateException("D089 source changed after planning");
    }

    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<L2T0TrainingLabels> consumer, BooleanSupplier cancelled) throws Exception {
        return source.stream(inspection, symbols, maxRows, maxFiles, consumer, cancelled, timeout);
    }

    @Override public VerifiedBatchExecutor.Codec<L2T0TrainingLabels, L2T0TrainingLabelsKey> codec() {
        return L2T0TrainingLabelsWritePort.CODEC;
    }

    @Override public VerifiedBatchExecutor.Port<L2T0TrainingLabels, L2T0TrainingLabelsKey> port() { return port; }
}
