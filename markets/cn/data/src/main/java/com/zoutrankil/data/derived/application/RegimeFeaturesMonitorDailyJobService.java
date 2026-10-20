package com.zoutrankil.data.derived.application;

import static com.zoutrankil.data.domain.derived.policy.MarketSentimentDailyCalculation.finite;
import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.ALGORITHM_VERSION;
import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.REQUIRED_CONTEXT_FIELDS;
import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.aggregate;
import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.margin;
import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.merge;
import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.northbound;
import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.row;
import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.styleAndBreadth;
import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.valuation;
import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.warmupFrom;

import com.zoutrankil.data.derived.domain.NativeDailyWindowRules;
import com.zoutrankil.data.derived.domain.RegimeFeaturesMonitorDailySourceData.Margins;
import com.zoutrankil.data.derived.port.NativeDailyWindowSession;
import com.zoutrankil.data.derived.port.RegimeFeaturesMonitorDailySourceReadPort;
import com.zoutrankil.data.derived.port.RegimeFeaturesMonitorDailyTarget;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.derived.NativeDailyWindowSnapshot;
import com.zoutrankil.data.domain.derived.RegimeFeaturesMonitorDailyDataset;
import com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.Day;
import com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.Stock;
import com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.Valuation;
import com.zoutrankil.data.domain.derived.table.RegimeFeaturesMonitorDailyRow;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.LedgerReadModels;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Native Java owner for the existing 21-field proxy-style context table.
 * The persistent batch synchronizes dependencies before invoking this calculation.
 * The calculation reads pinned inputs and publishes a verified date window.
 */
public final class RegimeFeaturesMonitorDailyJobService
        implements DatasetImplementation, SyncJobOwner {
    public static final String JOB_ID = "data.regime_features_monitor_daily";
    public static final String PRODUCER = "java.regime_features_monitor_daily";
    private static final String FORMAL = "regime_features_monitor_daily",
            PREFIX = "java_regime_features_monitor_daily";
    public static final List<String> SOURCES = RegimeFeaturesMonitorDailyDataset.DEPENDENCIES;
    // Every required input now has a registered Java owner, including ChinaBond.
    public static final List<String> REGISTERED_DEPENDENCIES = SOURCES;

    public record Plan(
            FrozenRequest request,
            String targetId,
            LocalDate warmupFrom,
            List<String> dependencies,
            String sourcePin,
            NativeDailyWindowSnapshot<RegimeFeaturesMonitorDailyRow> targetBefore) {}

    public record MaterializationResult(
            SyncJobRunner.Result result,
            int historyDates,
            long sourceRawRows,
            String sourceFingerprint,
            String fullTargetFingerprint,
            String evidence) {}

    private record Computed(
            List<RegimeFeaturesMonitorDailyRow> rows,
            int historyDates,
            long panelRows,
            long valuationRows,
            String fingerprint,
            List<Map<String, Object>> panelCoverage,
            List<Map<String, Object>> marginAvailability,
            List<LocalDate> expectedDates,
            List<LocalDate> ignoredGovDates,
            int northboundDates,
            int valuationDates,
            int govDates,
            List<Map<String, Object>> stockScopeAudits) {}

    private final RegimeFeaturesMonitorDailySourceReadPort storage;
    private final RegimeFeaturesMonitorDailyTarget target;
    private final Function<
                    NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow>,
                    NativeDailyWindowPublication<RegimeFeaturesMonitorDailyRow>>
            publications;
    private final Path ledgerPath;
    private final String table;

    public RegimeFeaturesMonitorDailyJobService(
            RegimeFeaturesMonitorDailySourceReadPort storage,
            RegimeFeaturesMonitorDailyTarget target,
            Function<
                            NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow>,
                            NativeDailyWindowPublication<RegimeFeaturesMonitorDailyRow>>
                    publications,
            String ledger,
            String table) {
        this.storage = Objects.requireNonNull(storage);
        this.target = Objects.requireNonNull(target);
        this.publications = Objects.requireNonNull(publications);
        ledgerPath = Path.of(ledger).toAbsolutePath().normalize();
        NativeDailyWindowRules.requireTarget(table, FORMAL, PREFIX);
        this.table = table;
    }

    private NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> port() {
        return target.writer(table);
    }

    private NativeDailyWindowPublication<RegimeFeaturesMonitorDailyRow> publication(
            NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> port) {
        return publications.apply(port);
    }

    private String sourcePin() throws Exception {
        return storage.sourcePin();
    }

    @Override
    public String datasetId() {
        return FORMAL;
    }

    @Override
    public Set<Mode> supportedSyncModes() {
        return jobDefinition().supportedModes();
    }

    @Override
    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(jobDefinition());
    }

    @Override
    public DatasetDefinition definition() {
        return RegimeFeaturesMonitorDailyDataset.definition(table);
    }

    public static SyncJobDefinition jobDefinition() {
        var parameters = new LinkedHashMap<String, Parameter>();
        parameters.put("target_id", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()));
        parameters.put("source_pin", new Parameter(ParameterType.STRING, true, 64, 1, Set.of()));
        parameters.put("target_hash", new Parameter(ParameterType.STRING, true, 64, 1, Set.of()));
        parameters.put("warmup_from", new Parameter(ParameterType.DATE, true, 10, 1, Set.of()));
        return new SyncJobDefinition(
                JOB_ID,
                4,
                FORMAL,
                1,
                "regime_features_monitor_daily_owner",
                Set.of(Mode.MATERIALIZE),
                Mode.MATERIALIZE,
                parameters,
                "questdb.materialize",
                "regime_features_monitor_daily.range366",
                "questdb.full_key_values",
                new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                Duration.ofMinutes(20),
                new Budget(366, 1, 1, 366, 1024 * 1024),
                0,
                List.of(),
                Frequency.DAILY,
                ZoneId.of("Asia/Shanghai"),
                true,
                true);
    }

    public Plan plan(LocalDate from, LocalDate to, LocalDate logicalDate) throws Exception {
        return plan(from, to, logicalDate, Mode.MATERIALIZE);
    }

    public Plan plan(LocalDate from, LocalDate to, LocalDate logicalDate, Mode mode)
            throws Exception {
        if (mode != Mode.MATERIALIZE)
            throw new IllegalArgumentException(
                    "Native regime-monitor owner supports MATERIALIZE; publication recovery uses the explicit finish command");
        if (from == null
                || to == null
                || logicalDate == null
                || from.isAfter(to)
                || to.isAfter(logicalDate)
                || ChronoUnit.DAYS.between(from, to) >= 366)
            throw new IllegalArgumentException(
                    "Explicit bounded from/to <= logicalDate, at most 366 calendar days required");
        requireNoPendingPublication();
        var before = port().formalSnapshot();
        String pin = sourcePin();
        LocalDate warmup = warmupFrom(from);
        var request =
                jobDefinition()
                        .freeze(
                                mode,
                                Map.of(
                                        "target_id",
                                        before.targetId(),
                                        "source_pin",
                                        pin,
                                        "target_hash",
                                        before.fingerprint(),
                                        "warmup_from",
                                        warmup),
                                from,
                                to,
                                logicalDate);
        return new Plan(request, before.targetId(), warmup, SOURCES, pin, before);
    }

    public MaterializationResult run(Plan plan) throws Exception {
        if (plan == null
                || !plan.request.definition().equals(jobDefinition())
                || !plan.sourcePin.equals(plan.request.parameters().get("source_pin"))
                || !plan.targetId.equals(plan.request.parameters().get("target_id"))
                || !plan.targetBefore.targetId().equals(plan.targetId)
                || !plan.targetBefore
                        .fingerprint()
                        .equals(plan.request.parameters().get("target_hash"))
                || !plan.warmupFrom.equals(warmupFrom(plan.request.from())))
            throw new IllegalArgumentException("Frozen native regime-monitor plan required");
        String run = "regime-monitor-" + UUID.randomUUID();
        var write = port();
        var adapter = new Adapter(plan, run, write);
        var runner =
                new SyncJobRunner<RegimeFeaturesMonitorDailyRow, Instant>(
                        new SyncRunLedger(ledgerPath), new DatasetIntervalLock(ledgerPath));
        var result =
                runner.run(
                        run,
                        null,
                        plan.targetId,
                        plan.request,
                        adapter,
                        () -> Thread.currentThread().isInterrupted());
        return new MaterializationResult(
                result,
                adapter.historyDates,
                adapter.rawRows,
                adapter.sourceFingerprint,
                adapter.finalFingerprint,
                adapter.evidence == null ? null : adapter.evidence.toString());
    }

    private final class Adapter
            implements SyncJobRunner.Adapter<RegimeFeaturesMonitorDailyRow, Instant> {
        final Plan plan;
        final String run;
        final NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> write;
        int historyDates;
        long rawRows;
        String sourceFingerprint, finalFingerprint;
        Path evidence;

        Adapter(
                Plan plan,
                String run,
                NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> write) {
            this.plan = plan;
            this.run = run;
            this.write = write;
        }

        public void preflight(FrozenRequest request) throws Exception {
            requireNoPendingPublication();
            write.requireSame(plan.targetBefore);
            if (!sourcePin().equals(plan.sourcePin))
                throw new IllegalStateException("Source changed since planning");
        }

        public DatasetIntervalLock.Scope conflictScope(FrozenRequest request) {
            return DatasetIntervalLock.Scope.allDates(datasetId());
        }

        public VerifiedBatchExecutor.Codec<RegimeFeaturesMonitorDailyRow, Instant> codec() {
            return write.codec();
        }

        public VerifiedBatchExecutor.Port<RegimeFeaturesMonitorDailyRow, Instant> port() {
            return write;
        }

        public boolean recoveryRequired(String ignored) throws Exception {
            return publication(write).findForRun(run).isPresent();
        }

        public SyncJobRunner.SourceCompletion fetch(
                FrozenRequest request,
                SyncJobRunner.PageConsumer<RegimeFeaturesMonitorDailyRow> consumer,
                BooleanSupplier cancelled)
                throws Exception {
            check(cancelled);
            var source = calculate(plan, cancelled);
            historyDates = source.historyDates;
            rawRows = source.panelRows;
            sourceFingerprint = source.fingerprint;
            evidence =
                    ledgerPath
                            .getParent()
                            .resolve("sync-evidence")
                            .resolve(run)
                            .resolve("source.json");
            Files.createDirectories(evidence.getParent());
            writeEvidence(
                    evidence,
                    Map.ofEntries(
                            Map.entry("producer", PRODUCER),
                            Map.entry("algorithmVersion", ALGORITHM_VERSION),
                            Map.entry("warmupFrom", plan.warmupFrom),
                            Map.entry("from", request.from()),
                            Map.entry("to", request.to()),
                            Map.entry("historyDates", historyDates),
                            Map.entry("sourceRawRows", rawRows),
                            Map.entry("valuationRawRows", source.valuationRows),
                            Map.entry("sourceFingerprint", sourceFingerprint),
                            Map.entry("sources", SOURCES),
                            Map.entry("externalReadOnlySources", List.of("cn_bond_yield_curve")),
                            Map.entry("panelCoverage", source.panelCoverage),
                            Map.entry("stockScopeAudits", source.stockScopeAudits),
                            Map.entry("marginAvailability", source.marginAvailability),
                            Map.entry("expectedSseTradingDates", source.expectedDates),
                            Map.entry("requiredContextFields", REQUIRED_CONTEXT_FIELDS),
                            Map.entry(
                                    "qualityPolicy",
                                    "proxy_style describes stock-bucket proxies; optional partial-market margin is null and separately evidenced"),
                            Map.entry("northboundObservedDates", source.northboundDates),
                            Map.entry("valuationObservedDates", source.valuationDates),
                            Map.entry("gov10YObservedDates", source.govDates),
                            Map.entry("ignoredGovNonValuationDates", source.ignoredGovDates),
                            Map.entry("rows", source.rows)));
            if (!sourcePin().equals(plan.sourcePin))
                throw new IllegalStateException("Dependency source changed during calculation");
            write.prepare(plan.targetBefore, request.from(), request.to());
            consumer.accept(
                    new SyncJobRunner.Page<>(
                            source.rows, sourceFingerprint, evidence.toString(), null));
            check(cancelled);
            if (!sourcePin().equals(plan.sourcePin))
                throw new IllegalStateException("Dependency source changed before publication");
            var locks = new DatasetIntervalLock(ledgerPath);
            var lease = locks.findOwned(run, DatasetIntervalLock.Scope.allDates(datasetId()));
            if (lease == null)
                throw new IllegalStateException("Whole regime-monitor dataset lease missing");
            var published =
                    publication(write)
                            .publish(
                                    run,
                                    lease,
                                    plan.targetBefore,
                                    request.from(),
                                    request.to(),
                                    source.rows,
                                    Map.of(
                                            "sourceEvidence",
                                            evidence.toString(),
                                            "sourceEvidenceSha256",
                                            fileHash(evidence),
                                            "sourceFingerprint",
                                            sourceFingerprint),
                                    cancelled);
            finalFingerprint = published.published().fingerprint();
            return new SyncJobRunner.SourceCompletion(
                    1, source.rows.size(), true, published.evidence().toString());
        }
    }

    private Computed calculate(Plan plan, BooleanSupplier cancelled) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        var calendar = storage.calendar(plan.warmupFrom, plan.request.to(), hash);
        var expectedOutput =
                new TreeSet<>(calendar.subSet(plan.request.from(), true, plan.request.to(), true));
        if (expectedOutput.isEmpty())
            throw new IllegalStateException(
                    "Requested window has no authoritative SSE trading dates");
        var stockDays = new ArrayList<Day>();
        var observedPanelDates = new TreeSet<LocalDate>();
        var panelCoverage = new ArrayList<Map<String, Object>>();
        var stockScopeAudits = new ArrayList<Map<String, Object>>();
        long[] panelRows = {0}, valuationRows = {0};
        var valuations = new TreeMap<LocalDate, Valuation>();
        for (LocalDate lower = plan.warmupFrom; !lower.isAfter(plan.request.to()); ) {
            check(cancelled);
            LocalDate upper = lower.withDayOfMonth(1).plusMonths(1);
            if (upper.isAfter(plan.request.to().plusDays(1))) upper = plan.request.to().plusDays(1);
            storage.readPanelMonth(
                    lower,
                    upper,
                    calendar,
                    panelRows,
                    hash,
                    cancelled,
                    new RegimeFeaturesMonitorDailySourceReadPort.PanelDayConsumer() {
                        @Override
                        public void stockScopeAudit(Map<String, Object> proof) {
                            stockScopeAudits.add(proof);
                        }

                        @Override
                        public void accept(
                                LocalDate date,
                                List<Stock> stocks,
                                Set<String> codes,
                                int basic,
                                int limits) {
                            finishPanelDay(
                                    date,
                                    stocks,
                                    codes,
                                    basic,
                                    limits,
                                    stockDays,
                                    observedPanelDates,
                                    panelCoverage);
                        }
                    });
            storage.readValuations(
                    lower, upper, calendar, valuations, valuationRows, hash, cancelled);
            lower = upper;
        }
        hash.update(canonicalBytes(stockScopeAudits));
        if (!observedPanelDates.equals(calendar))
            throw new IllegalStateException(
                    "Warmup/output panel missing SSE trading dates: "
                            + difference(calendar, observedPanelDates));
        styleAndBreadth(stockDays);
        check(cancelled);
        var north = storage.readNorthbound(plan.warmupFrom, plan.request.to(), hash);
        var margin = storage.readMargins(plan.warmupFrom, plan.request.to(), hash);
        var availability = marginAvailability(margin, hash);
        var excluded = new HashSet<LocalDate>();
        for (var item : availability)
            if (Boolean.FALSE.equals(item.get("admitted")))
                excluded.add((LocalDate) item.get("tradeDate"));
        var bonds = storage.readGov(plan.warmupFrom, plan.request.to(), hash);
        var merged =
                merge(
                        stockDays,
                        northbound(north),
                        margin(margin.balances(), excluded),
                        valuation(valuations, bonds));
        Instant observed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var output = new ArrayList<RegimeFeaturesMonitorDailyRow>();
        var outputDates = new TreeSet<LocalDate>();
        for (var day : merged.values())
            if (!day.date.isBefore(plan.request.from()) && !day.date.isAfter(plan.request.to())) {
                if (!expectedOutput.contains(day.date))
                    throw new IllegalStateException(
                            "Derived product contains a non-trading output date " + day.date);
                for (String field : REQUIRED_CONTEXT_FIELDS)
                    if (!finite(day.get(field)))
                        throw new IllegalStateException(
                                "Required input-package context field is unavailable on "
                                        + day.date
                                        + ": "
                                        + field);
                output.add(row(day, observed));
                outputDates.add(day.date);
            }
        if (!outputDates.equals(expectedOutput))
            throw new IllegalStateException(
                    "Missing requested derived output dates: "
                            + difference(expectedOutput, outputDates));
        var ignoredGov = bonds.keySet().stream().filter(d -> !valuations.containsKey(d)).toList();
        return new Computed(
                List.copyOf(output),
                stockDays.size(),
                panelRows[0],
                valuationRows[0],
                HexFormat.of().formatHex(hash.digest()),
                List.copyOf(panelCoverage),
                List.copyOf(availability),
                List.copyOf(expectedOutput),
                ignoredGov,
                north.size(),
                valuations.size(),
                bonds.size(),
                List.copyOf(stockScopeAudits));
    }

    private void finishPanelDay(
            LocalDate date,
            List<Stock> stocks,
            Set<String> codes,
            int basic,
            int limits,
            List<Day> days,
            Set<LocalDate> observed,
            List<Map<String, Object>> evidence) {
        if (codes.isEmpty()
                || basic != codes.size()
                || limits != codes.size()
                || stocks.isEmpty()
                || !observed.add(date))
            throw new IllegalStateException(
                    "Incomplete/duplicate stock panel on "
                            + date
                            + ": factor="
                            + codes.size()
                            + ", basic="
                            + basic
                            + ", limits="
                            + limits
                            + ", tradable="
                            + stocks.size());
        days.add(aggregate(date, stocks));
        evidence.add(
                Map.of(
                        "tradeDate",
                        date,
                        "factorRows",
                        codes.size(),
                        "basicMatches",
                        basic,
                        "limitMatches",
                        limits,
                        "tradableRows",
                        stocks.size()));
    }

    private static List<Map<String, Object>> marginAvailability(Margins margins, MessageDigest hash)
            throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : margins.exchanges().entrySet()) {
            var previous =
                    margins
                            .exchanges()
                            .headMap(entry.getKey(), false)
                            .descendingMap()
                            .entrySet()
                            .stream()
                            .limit(20)
                            .toList();
            var positive = new TreeMap<String, Integer>();
            var expected = new TreeSet<String>();
            var missing = new TreeSet<String>();
            for (String exchange : List.of("SH", "SZ", "BJ")) {
                int n = (int) previous.stream().filter(e -> e.getValue().get(exchange) > 0).count();
                positive.put(exchange, n);
                if (n >= 3) expected.add(exchange);
            }
            for (String exchange : expected)
                if (entry.getValue().get(exchange) == 0) missing.add(exchange);
            result.add(
                    Map.ofEntries(
                            Map.entry("tradeDate", entry.getKey()),
                            Map.entry("counts", entry.getValue()),
                            Map.entry("expectedExchanges", expected),
                            Map.entry("missingExchanges", missing),
                            Map.entry("positiveObservationCounts", positive),
                            Map.entry("priorObservedDays", previous.size()),
                            Map.entry(
                                    "priorObservedDates",
                                    previous.stream().map(Map.Entry::getKey).toList()),
                            Map.entry("admitted", missing.isEmpty()),
                            Map.entry(
                                    "reason",
                                    missing.isEmpty()
                                            ? "recent_exchange_scope_available"
                                            : "entire_recent_exchange_missing"),
                            Map.entry("policy", "prior_20_observed_dates_min_3_positive_dates")));
        }
        hash.update(canonicalBytes(result));
        return result;
    }

    private void requireNoPendingPublication() throws Exception {
        publication(port()).requireNoPendingPublication();
    }

    public LedgerReadModels.Entry status(String run) throws Exception {
        return LedgerReadModels.entry(SyncRunLedger.openReadOnly(ledgerPath).get(run));
    }

    public NativeDailyWindowSnapshot<RegimeFeaturesMonitorDailyRow> finishInterrupted(
            String runId, boolean writerStopped) throws Exception {
        var run = SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);
        if (!JOB_ID.equals(run.jobId()))
            throw new IllegalArgumentException("Run belongs to another owner");
        return publication(port()).finishInterrupted(runId, writerStopped, PRODUCER, null);
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Native regime-monitor calculation cancelled");
    }

    private static <T> Set<T> difference(Set<T> expected, Set<T> seen) {
        var result = new HashSet<>(expected);
        result.removeAll(seen);
        return result;
    }

    private static void hashLine(MessageDigest hash, String line) {
        hash.update((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] canonicalBytes(Object value) throws Exception {
        return JobDefinitionJson.canonicalMapper().writeValueAsBytes(value);
    }

    private static String fileHash(Path path) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void writeEvidence(Path path, Object value) throws Exception {
        byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(value);
        try (var channel =
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
