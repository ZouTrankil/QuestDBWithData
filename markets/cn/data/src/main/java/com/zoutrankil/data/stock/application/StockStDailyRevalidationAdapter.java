package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.stock.basic.StockStDaily;
import com.zoutrankil.data.domain.stock.basic.StockStDailyKey;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.stock.domain.StockStDailyState;
import com.zoutrankil.data.stock.port.StockDateWriteSession;
import com.zoutrankil.data.stock.port.StockStDailyTarget;
import com.zoutrankil.data.stock.port.StockStDailyWriteSession;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Source/readback only: never constructs staging or publication objects. */
public final class StockStDailyRevalidationAdapter
        implements SyncJobRunner.Adapter<StockStDaily, StockStDailyKey> {
    private final StockStDailySource source;
    private final StockStTradingDates calendar;
    private final StockStDailyTarget target;
    private final String physical;
    private final StockDateWriteSession<StockStDaily, StockStDailyKey> reader;
    private final Path evidence;

    public StockStDailyRevalidationAdapter(
            StockStDailySource source,
            StockStTradingDates calendar,
            StockStDailyTarget target,
            Path evidence) {
        this.source = source;
        this.calendar = calendar;
        this.target = target;
        this.evidence = evidence;
        physical = target.physicalTargetId();
        reader = target.newWriter(physical);
    }

    private void requireIdentity() {
        if (!physical.equals(target.physicalTargetId()))
            throw new IllegalStateException("ST physical generation changed during readback");
    }

    @Override
    public void preflight(SyncJobDefinition.FrozenRequest request) throws Exception {
        StockStDailySyncAdapter.validateFrozenRequest(request);
        int version =
                reader instanceof StockStDailyWriteSession.Versioned selected
                        ? selected.datasetVersion()
                        : 1;
        if (StockStDailyTarget.versionOf(target) != request.definition().datasetVersion()
                || version != request.definition().datasetVersion())
            throw new IllegalArgumentException("ST 再验证作用域与原冻结布局不同");
        if (!target.targetId().equals(request.parameters().get("targetId")))
            throw new IllegalStateException("ST logical target changed");
        if (!StockStDailySyncAdapter.decodeTradeDates(request)
                .equals(calendar.read(request.from(), request.to())))
            throw new IllegalStateException("ST frozen calendar changed");
        requireIdentity();
        reader.preflight();
    }

    @Override
    public SyncJobRunner.SourceCompletion fetch(
            SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<StockStDaily> consumer,
            BooleanSupplier cancelled)
            throws Exception {
        var dates = StockStDailySyncAdapter.decodeTradeDates(request);
        var allRows = new ArrayList<StockStDaily>();
        var receipts = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<LocalDate>();
        source.fetchWindow(
                request.from(),
                request.to(),
                dates,
                page -> {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                        throw new CancellationException();
                    LocalDate day =
                            LocalDate.parse(page.cursor(), DateTimeFormatter.BASIC_ISO_DATE);
                    if (!dates.contains(day) || !seen.add(day))
                        throw new IllegalStateException("Unexpected or repeated ST source day");
                    var reopened =
                            StockStDailySource.reopen(
                                    Path.of(page.responseEvidence()),
                                    page.sourceFingerprint(),
                                    day);
                    var values = new HashMap<StockStDailyKey, byte[]>();
                    for (var row : reopened.rows())
                        if (values.putIfAbsent(row.key(), codec().canonicalBytes(row)) != null)
                            throw new IllegalStateException("Duplicate retained ST source key");
                    if (values.size() != page.rows().size())
                        throw new IllegalStateException("ST retained source row count changed");
                    for (var row : page.rows())
                        if (!Arrays.equals(values.remove(row.key()), codec().canonicalBytes(row)))
                            throw new IllegalStateException("ST retained source values changed");
                    if ((long) allRows.size() + page.rows().size()
                            > request.definition().budget().maxRows())
                        throw new IllegalStateException("ST revalidation source budget exceeded");
                    allRows.addAll(page.rows());
                    consumer.accept(page);
                    receipts.add(
                            Map.of(
                                    "date",
                                    day.toString(),
                                    "source",
                                    page.responseEvidence(),
                                    "fingerprint",
                                    page.sourceFingerprint()));
                },
                cancelled);
        if (seen.size() != dates.size()) throw new IllegalStateException("Missing ST source dates");
        requireIdentity();
        var expected = target.fingerprintWindow(allRows, request.from(), request.to());
        var actual = target.openTable().window(request.from(), request.to());
        if (!StockStDailyState.sameContent(expected, actual))
            throw new IllegalStateException(
                    "ST full window differs, including empty dates or extra keys");
        requireIdentity();
        Path receipt = evidence.resolve("source-revalidated.json");
        FileEvidenceStore.writeNewDurable(
                receipt,
                JobDefinitionJson.canonicalMapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "physicalTargetId",
                                        physical,
                                        "window",
                                        actual,
                                        "sourceReceipts",
                                        receipts,
                                        "passed",
                                        true)));
        return new SyncJobRunner.SourceCompletion(
                seen.size(), allRows.size(), true, receipt.toString());
    }

    @Override
    public VerifiedBatchExecutor.Codec<StockStDaily, StockStDailyKey> codec() {
        return reader.codec();
    }

    @Override
    public VerifiedBatchExecutor.Port<StockStDaily, StockStDailyKey> port() {
        return reader;
    }
}
