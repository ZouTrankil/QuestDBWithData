package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.DailyWritePort;
import com.zoutrankil.data.repository.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.StockDetailInfoReadRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** One bounded Tushare request slice per SSE open trade date. */
public final class DailySyncAdapter implements SyncJobRunner.Adapter<DailyMarketBar, DailyMarketBar.Key> {
    private final TusharePageService pages;
    private final ExchangeCalendarReadRepository calendars;
    private final StockDetailInfoReadRepository stockDetails;
    private final DailyWritePort port;
    private final Path evidence;

    public DailySyncAdapter(TusharePageService pages, ExchangeCalendarReadRepository calendars,
                            StockDetailInfoReadRepository stockDetails, DailyWritePort port, Path evidence) {
        this.pages = Objects.requireNonNull(pages);
        this.calendars = Objects.requireNonNull(calendars);
        this.stockDetails = Objects.requireNonNull(stockDetails);
        this.port = Objects.requireNonNull(port);
        this.evidence = Objects.requireNonNull(evidence).toAbsolutePath().normalize();
    }

    public static SyncJobDefinition definition(boolean enabled) {
        return new SyncJobDefinition("data.daily", 1, "daily", 1, "daily_owner",
                Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE), Mode.INCREMENTAL,
                Map.of(), "tushare.shared", "daily.trade_date", "questdb.full_key_values",
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)), Duration.ofHours(23),
                new Budget(366, 366, 366, 3_660_000, DailyWritePort.MAX_BATCH_BYTES),
                5, List.of(new JobRef("data.exchange_calendar", 1)),
                Frequency.DAILY, ZoneId.of("Asia/Shanghai"), enabled, true);
    }

    private static void validate(FrozenRequest request) {
        if (!request.definition().equals(definition(true))
                || !request.definition().datasetId().equals(DailyDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion() != DailyDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE).contains(request.mode())
                || request.from() == null || request.to() == null
                || request.parameters().size() != 0) {
            throw new IllegalArgumentException("Exact bounded D007 daily request required");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(request.from(), request.to()) + 1;
        if (days < 1 || days > DailyTradingSessions.MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException("Daily request exceeds its 366-calendar-day bound");
        }
    }

    @Override public void preflight(FrozenRequest request) {
        validate(request);
        DailyTradingSessions.read(calendars, request.from(), request.to());
        port.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<DailyMarketBar> consumer, BooleanSupplier cancelled) throws Exception {
        validate(request);
        var days = DailyTradingSessions.read(calendars, request.from(), request.to());
        int[] totals = new int[2];
        var receipts = new ArrayList<String>();
        for (LocalDate day : days) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Daily sync cancelled before next trade-date request");
            var page = new DailySource(pages, evidence).fetch(day, this::stockCodes, cancelled);
            consumer.accept(page);
            totals[0] = Math.addExact(totals[0], 1);
            totals[1] = Math.addExact(totals[1], page.rows().size());
            receipts.add(page.responseEvidence());
        }
        Files.createDirectories(evidence);
        Path complete = evidence.resolve("complete-" + UUID.randomUUID() + ".json");
        JobDefinitionJson.mapper().writeValue(complete.toFile(), Map.of(
                "endpoint", "daily", "complete", true, "from", request.from(), "to", request.to(),
                "tradingDates", days, "pages", totals[0], "rows", totals[1], "sourceReceipts", receipts));
        return new SyncJobRunner.SourceCompletion(totals[0], totals[1], true, complete.toString());
    }

    private List<String> stockCodes() {
        var columns = StockDetailInfoDataset.DEFINITION.columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList();
        var codes = new ArrayList<String>();
        var seen = new HashSet<String>();
        DatasetReadCursor cursor = null;
        do {
            var query = new DatasetReadQuery(columns, Map.of(), null, null, null, 1000, cursor);
            var page = stockDetails.findPage(query);
            for (var stock : page.rows()) {
                if (!seen.add(stock.tsCode())) throw new IllegalStateException("Duplicate D002 stock code in fallback inventory");
                codes.add(stock.tsCode());
            }
            if (codes.size() > 10000) throw new IllegalStateException("D002 fallback inventory exceeds its row bound");
            cursor = page.nextCursor();
        } while (cursor != null);
        if (codes.isEmpty()) throw new IllegalStateException("Nonempty D002 stock inventory required after daily response cap");
        return List.copyOf(codes);
    }

    @Override public VerifiedBatchExecutor.Codec<DailyMarketBar, DailyMarketBar.Key> codec() {
        return DailyWritePort.CODEC;
    }

    @Override public VerifiedBatchExecutor.Port<DailyMarketBar, DailyMarketBar.Key> port() { return port; }
}
