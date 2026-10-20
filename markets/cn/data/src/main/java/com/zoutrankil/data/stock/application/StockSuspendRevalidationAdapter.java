package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.domain.stock.market.StockSuspendKey;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.stock.domain.StockSuspendState;
import com.zoutrankil.data.stock.mapper.StockSuspendLayoutMapper;
import com.zoutrankil.data.stock.port.StockSuspendTarget;
import com.zoutrankil.data.stock.port.StockSuspendWriteSession;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Source/readback only: no staging, append intent, or publication. */
final class StockSuspendRevalidationAdapter
        implements SyncJobRunner.Adapter<StockSuspend, StockSuspendKey> {
    private final StockSuspendSource source;
    private final StockSuspendTarget target;
    private final StockSuspendWriteSession reader;
    private final String physical;
    private final Path evidence;

    StockSuspendRevalidationAdapter(
            TusharePageService pages,
            StockSuspendTarget target,
            Path evidence,
            String expectedPhysical) {
        this.target = target;
        this.evidence = evidence;
        source = new StockSuspendSource(pages, evidence.resolve("source"));
        physical = Objects.requireNonNull(expectedPhysical);
        // 固定控制服务已核验的代际，不能在两次读取之间重新绑定同名新表。
        requireIdentity();
        reader = target.newWriter(physical);
    }

    private void requireIdentity() {
        if (!physical.equals(target.physicalTargetId()))
            throw new IllegalStateException("Suspension generation changed during revalidation");
    }

    public void preflight(SyncJobDefinition.FrozenRequest request) {
        StockSuspendSyncAdapter.validateRequest(request);
        StockSuspendSyncAdapter.requireLayout(reader, request.definition().datasetVersion());
        if (StockSuspendTarget.versionOf(target) != request.definition().datasetVersion())
            throw new IllegalArgumentException("停牌再验证作用域与原冻结布局不同");
        if (!target.targetId().equals(request.parameters().get("targetId")))
            throw new IllegalStateException("Suspension logical target changed");
        requireIdentity();
        reader.preflight();
    }

    public SyncJobRunner.SourceCompletion fetch(
            SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<StockSuspend> consumer,
            BooleanSupplier cancelled)
            throws Exception {
        var rows = new ArrayList<StockSuspend>();
        var receipts = new ArrayList<String>();
        for (var date = request.from(); !date.isAfter(request.to()); date = date.plusDays(1)) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException();
            requireIdentity();
            var result = source.fetch(new StockSuspendSource.Query(date), cancelled);
            rows.addAll(result.rows());
            if (rows.size() > request.definition().budget().maxRows())
                throw new IllegalStateException("Suspension source exceeds frozen budget");
            consumer.accept(
                    new SyncJobRunner.Page<>(
                            result.rows(), result.fingerprint(), result.receipt(), null));
            receipts.add(result.receipt());
        }
        if (!reader.walSettled()) throw new IllegalStateException("停牌再验证 WAL 未就绪");
        var snapshot = target.openTable(target.tableName()).snapshot();
        if (!physical.equals(target.physicalTargetId(target.tableName(), snapshot.identity())))
            throw new IllegalStateException("Suspension readback generation changed");
        var actual =
                snapshot.rows().stream()
                        .filter(
                                row ->
                                        !row.tradeDate().isBefore(request.from())
                                                && !row.tradeDate().isAfter(request.to()))
                        .toList();
        if (!StockSuspendState.sortedUnique(rows).equals(StockSuspendState.sortedUnique(actual)))
            throw new IllegalStateException("Suspension complete window differs from fresh source");
        requireIdentity();
        Files.createDirectories(evidence);
        var receipt = evidence.resolve("source-revalidated.json");
        FileEvidenceStore.writeNewDurable(
                receipt,
                JobDefinitionJson.mapper()
                        .writeValueAsBytes(
                                StockSuspendLayoutMapper.bind(
                                        Map.of(
                                                "sourceSlices",
                                                receipts,
                                                "sourceRows",
                                                rows.size(),
                                                "physicalTarget",
                                                physical,
                                                "complete",
                                                true),
                                        CnTradeDateLayout.fromVersion(
                                                request.definition().datasetVersion()))));
        return new SyncJobRunner.SourceCompletion(
                receipts.size(), rows.size(), true, receipt.toString());
    }

    public VerifiedBatchExecutor.Codec<StockSuspend, StockSuspendKey> codec() {
        return reader.codec();
    }

    public VerifiedBatchExecutor.Port<StockSuspend, StockSuspendKey> port() {
        return reader;
    }
}
