package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** D008's bounded trade-date source and single-dataset verified runner adapter. */
public final class DailyBasicSyncAdapter implements SyncJobRunner.Adapter<DailyBasic, DailyBasicKey> {
    private static final int MAX_WINDOW_DAYS = 366;
    private static final int REVISION_DAYS = 30;
    private final DailyBasicSource source;
    private final DailyBasicTradingDates tradingDates;
    private final VerifiedWriteSession<DailyBasic, DailyBasicKey> port;
    private final Path evidenceRoot;

    public DailyBasicSyncAdapter(DailyBasicSource source, DailyBasicTradingDates tradingDates,
                                 VerifiedWriteSession<DailyBasic, DailyBasicKey> port, Path evidenceRoot) {
        this.source = Objects.requireNonNull(source); this.tradingDates = Objects.requireNonNull(tradingDates);
        this.port = Objects.requireNonNull(port); this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
    }

    public static SyncJobDefinition definition(boolean enabled) {
        return new SyncJobDefinition("data.daily_basic", 1, "daily_basic", 1, "daily_basic_owner",
                Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE), Mode.INCREMENTAL,
                Map.of("trade_dates", new Parameter(ParameterType.STRING, true, 4096, 1, Set.of()),
                        "checkpointAnchor", new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
                "tushare.shared", "daily_basic.trade_date", "questdb.full_key_values",
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)), Duration.ofHours(12),
                new Budget(MAX_WINDOW_DAYS, MAX_WINDOW_DAYS, MAX_WINDOW_DAYS,
                        MAX_WINDOW_DAYS * DailyBasicSource.API_ROW_CAP, 1024 * 1024),
                REVISION_DAYS, List.of(new JobRef("data.exchange_calendar", 1)), Frequency.DAILY,
                ZoneId.of("Asia/Shanghai"), enabled, true);
    }

    public static String encodeTradeDates(List<LocalDate> dates) {
        if (dates.size() > MAX_WINDOW_DAYS || dates.stream().distinct().count() != dates.size()
                || !dates.equals(dates.stream().sorted().toList()))
            throw new IllegalArgumentException("Unique ascending bounded trade dates required");
        String encoded = dates.isEmpty() ? "NONE" : String.join(",", dates.stream()
                .map(date -> date.toString().replace("-", "")).toList());
        if (encoded.length() > 4096) throw new IllegalArgumentException("Frozen daily_basic calendar exceeds request bound");
        return encoded;
    }

    public static List<LocalDate> decodeTradeDates(FrozenRequest request) {
        Object raw = request.parameters().get("trade_dates");
        if (!(raw instanceof String encoded)) throw new IllegalArgumentException("Frozen calendar text required");
        if (encoded.equals("NONE")) return List.of();
        if (encoded.isBlank()) throw new IllegalArgumentException("Empty calendar marker is invalid");
        var parsed = Arrays.stream(encoded.split(",", -1)).map(value -> {
            if (!value.matches("[0-9]{8}")) throw new IllegalArgumentException("Basic trade date required");
            return LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        }).toList();
        if (parsed.size() > MAX_WINDOW_DAYS || parsed.stream().distinct().count() != parsed.size()
                || !parsed.equals(parsed.stream().sorted().toList())
                || parsed.stream().anyMatch(date -> date.isBefore(request.from()) || date.isAfter(request.to())))
            throw new IllegalArgumentException("Frozen trade dates are duplicate, unordered or outside the request window");
        return parsed;
    }

    @Override public void preflight(FrozenRequest request) {
        var expected = definition(true);
        if (!request.definition().equals(expected) || request.from() == null || request.to() == null
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE).contains(request.mode()))
            throw new IllegalArgumentException("Frozen daily_basic definition and finite date window required");
        Object rawAnchor = request.parameters().get("checkpointAnchor");
        if (request.mode() == Mode.INCREMENTAL) {
            if (!(rawAnchor instanceof LocalDate anchor) || anchor.isAfter(request.to()) || request.from().isAfter(anchor))
                throw new IllegalArgumentException("daily_basic incremental requires its frozen bootstrap anchor");
        } else if (rawAnchor != null) {
            throw new IllegalArgumentException("Non-incremental daily_basic requests cannot carry a checkpoint anchor");
        }
        var actualCalendar = tradingDates.read(request.from(), request.to());
        if (!actualCalendar.equals(decodeTradeDates(request)))
            throw new IllegalStateException("Exchange calendar changed after daily_basic request planning");
        port.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<DailyBasic> consumer, BooleanSupplier cancelled) throws Exception {
        var dates = decodeTradeDates(request);
        int pages = 0, rows = 0;
        var files = new ArrayList<String>();
        for (var date : dates) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("daily_basic source cancelled");
            var page = source.fetch(date, cancelled);
            consumer.accept(page); // Empty trade dates also emit a durable, explicitly complete slice.
            pages++; rows = Math.addExact(rows, page.rows().size()); files.add(page.responseEvidence());
        }
        Files.createDirectories(evidenceRoot);
        Path completion = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        JobDefinitionJson.canonicalMapper()
                .writeValue(completion.toFile(), Map.of("endpoint", "daily_basic", "from", request.from(),
                        "to", request.to(), "tradeDates", dates, "pages", pages, "rows", rows,
                        "complete", true, "sourceEvidence", files));
        return new SyncJobRunner.SourceCompletion(pages, rows, true, completion.toString());
    }

    @Override public VerifiedBatchExecutor.Codec<DailyBasic, DailyBasicKey> codec() { return port.codec(); }
    @Override public VerifiedBatchExecutor.Port<DailyBasic, DailyBasicKey> port() { return port; }
}
