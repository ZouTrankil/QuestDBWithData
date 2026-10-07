package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.EtfMarketOverviewCacheDelegatedPort;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Canonical bounded prewarm lifecycle; original Python read() remains the sole publisher. */
@Service
public final class EtfMarketOverviewDailyCacheJobService implements SyncJobOwner {
    public static final String JOB_ID = "data.etf_market_overview_daily_cache";
    public record Plan(FrozenRequest request, String targetId, EtfMarketOverviewCachePublicationEnvelope source) {}
    public record MaterializationResult(SyncJobRunner.Result result, long sourceScanRows,
            EtfMarketOverviewCachePublicationEnvelope source, EtfMarketOverviewCachePublicationEnvelope target,
            String targetSnapshotError) {}
    public record Status(String runId, SyncRunState state, String targetId, String logicalDate,
            int verifiedPublicationUnits, int unresolvedSlices, boolean cancellationRequested,
            EtfMarketOverviewCachePublicationEnvelope currentTarget, String currentTargetError) {}
    private final EtfMarketOverviewCacheOwnerGateway gateway;
    private final JdbcTemplate jdbc;
    private final Path ledgerPath;
    private final Function<EtfMarketOverviewCachePublicationEnvelope, EtfMarketOverviewCacheDelegatedPort> portFactory;

    @Autowired
    public EtfMarketOverviewDailyCacheJobService(EtfMarketOverviewCacheOwnerGateway gateway, JdbcTemplate jdbc,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(gateway, jdbc, Path.of(ledgerPath), null);
    }

    EtfMarketOverviewDailyCacheJobService(Path ledgerPath, EtfMarketOverviewCacheOwnerGateway gateway,
            Function<EtfMarketOverviewCachePublicationEnvelope, EtfMarketOverviewCacheDelegatedPort> portFactory) {
        this(gateway, null, ledgerPath, Objects.requireNonNull(portFactory));
    }

    private EtfMarketOverviewDailyCacheJobService(EtfMarketOverviewCacheOwnerGateway gateway, JdbcTemplate jdbc,
            Path ledgerPath, Function<EtfMarketOverviewCachePublicationEnvelope, EtfMarketOverviewCacheDelegatedPort> factory) {
        this.gateway = Objects.requireNonNull(gateway);
        this.jdbc = jdbc;
        this.ledgerPath = Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();
        this.portFactory = factory;
    }

    @Override public String datasetId() { return EtfMarketOverviewDailyCacheDataset.DEFINITION.datasetId(); }
    @Override public Set<Mode> supportedSyncModes() { return definition().supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(definition()); }

    public static SyncJobDefinition definition() {
        var parameters = new LinkedHashMap<String,Parameter>();
        parameters.put("source_version", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()));
        parameters.put("target_id", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()));
        parameters.put("bootstrap_from", new Parameter(ParameterType.DATE, true, 10, 1, Set.of()));
        parameters.put("checkpoint_before", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()));
        return new SyncJobDefinition(JOB_ID, 1, "etf_market_overview_daily_cache", 1,
                "etf_market_overview_cache_owner", Set.of(Mode.INCREMENTAL, Mode.MATERIALIZE, Mode.RECONCILE),
                Mode.INCREMENTAL, parameters, "questdb.materialize", "etf_market_overview_daily.range31",
                "questdb.full_key_values", new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                Duration.ofMinutes(5), new Budget(31, 31, 31, 31, 64 * 1024), 0,
                List.of(ref(EtfShareSyncJobOwner.DEFINITION), ref(EtfDailySyncJobOwner.DEFINITION),
                        ref(EtfBasicSyncJobOwner.DEFINITION)), Frequency.MANUAL, ZoneId.of("Asia/Shanghai"), true, false);
    }

    private static JobRef ref(SyncJobDefinition definition) { return new JobRef(definition.jobId(), definition.version()); }

    public Plan plan(LocalDate bootstrapFrom, LocalDate requestedTo, LocalDate logicalDate, Mode mode) throws Exception {
        requireWindow(bootstrapFrom, requestedTo);
        Objects.requireNonNull(logicalDate, "Logical date required");
        mode = mode == null ? definition().defaultMode() : mode;
        if (!definition().supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported D101 mode");
        var source = gateway.preview(bootstrapFrom);
        var parameters = new LinkedHashMap<String,Object>();
        parameters.put("source_version", source.sourcesFingerprint());
        parameters.put("target_id", source.targetId());
        parameters.put("bootstrap_from", bootstrapFrom);
        if (mode == Mode.INCREMENTAL) {
            var checkpoint = checkpoint(source.targetId(), bootstrapFrom, source.sourcesFingerprint());
            if (checkpoint != null) parameters.put("checkpoint_before", checkpoint);
        }
        // A global YEAR transaction or timeless etf_basic correction can affect every old generation.
        // A checkpoint is diagnostic only: each incremental request starts at its complete bootstrap anchor.
        return new Plan(definition().freeze(mode, parameters, bootstrapFrom, requestedTo, logicalDate),
                source.targetId(), source);
    }

    public MaterializationResult run(Plan plan) throws Exception { return execute(plan, null); }

    public MaterializationResult resume(String previousRunId) throws Exception {
        var restored = FrozenRunRequest.restore(ledgerPath, previousRunId, definition());
        // A fresh source vector is checked against the original immutable parameters in runner preflight.
        return execute(new Plan(restored.request(), restored.targetId(), gateway.preview(restored.request().from())), previousRunId);
    }

    private MaterializationResult execute(Plan plan, String previousRunId) throws Exception {
        Objects.requireNonNull(plan);
        if (!definition().equals(plan.request().definition())
                || !plan.targetId().equals(plan.request().parameters().get("target_id")))
            throw new IllegalArgumentException("Exact D101 frozen definition and target required");
        var port = port(plan.source());
        var adapter = new EtfMarketOverviewDailyCacheMaterializeAdapter(gateway, port, plan.source());
        var ledger = new SyncRunLedger(ledgerPath);
        var runner = new SyncJobRunner<EtfMarketOverviewCachePublicationEnvelope, EtfMarketOverviewDailyCacheKey>(ledger,
                new DatasetIntervalLock(ledgerPath));
        String runId = "d101-" + UUID.randomUUID();
        var result = previousRunId == null
                ? runner.run(runId, null, plan.targetId(), plan.request(), adapter, () -> false)
                : runner.resume(runId, previousRunId, plan.targetId(), plan.request(), adapter, () -> false);
        var observed = safeSnapshot(plan.request().from());
        var verified = adapter.lastVerificationSnapshot();
        String error = observed.error();
        if (verified != null && observed.snapshot() != null
                && (!verified.sources().equals(observed.snapshot().sources())
                    || !verified.targets().equals(observed.snapshot().targets()))) error = "TargetChangedAfterVerification";
        return new MaterializationResult(result, adapter.sourceScanRows(), plan.source(),
                verified == null ? observed.snapshot() : verified, error);
    }

    public String targetId() throws Exception { return gateway.preview(LocalDate.now(ZoneOffset.UTC)).targetId(); }
    public EtfMarketOverviewCacheDelegatedPort delegatedPort() throws Exception {
        return port(gateway.preview(LocalDate.now(ZoneOffset.UTC)));
    }

    public Status status(String runId) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        var run = requireRun(ledger, runId);
        int verified = 0, unresolved = 0;
        for (var entry : children(ledger, runId)) {
            if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
            if (entry.state() == SyncRunState.VERIFIED)
                verified = Math.addExact(verified, JobDefinitionJson.mapper().readTree(entry.payloadJson())
                        .path("verification").path("matchedRows").asInt(0));
            else if (entry.state() != SyncRunState.VERIFIED_EMPTY) unresolved++;
        }
        var observed = safeSnapshot(LocalDate.parse(JobDefinitionJson.mapper().readTree(run.frozenJson()).path("from").asText()));
        return new Status(runId, ledger.get(runId).state(), run.targetId(), run.logicalDate(), verified, unresolved,
                ledger.cancellationRequested(runId), observed.snapshot(), observed.error());
    }

    public boolean cancel(String runId) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath); requireRun(ledger, runId);
        return ledger.requestCancellation(runId);
    }

    /** The stopped-writer flag requests proof; it is never itself proof of the original sender's termination. */
    public Status reconcile(String runId, boolean writerStopped) throws Exception {
        if (!writerStopped) throw new IllegalArgumentException("Explicit stopped-writer investigation required");
        var ledger = new SyncRunLedger(ledgerPath);
        var run = requireRun(ledger, runId);
        var prior = ledger.get(runId).state();
        if (!Set.of(SyncRunState.IN_DOUBT, SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(prior))
            throw new IllegalStateException("D101 run has no uncertain outcome to reconcile");
        var request = restoreForReconciliation(run);
        var frozen = gateway.preview(request.from());
        var port = port(frozen);
        port.reconciliationContext(ledgerPath, runId);
        if (!port.uncertainSenderStopped()) throw new IllegalStateException("D101 original owner sender is not proven stopped");
        var adapter = new EtfMarketOverviewDailyCacheMaterializeAdapter(gateway, port, frozen);
        var completion = adapter.revalidateOnly(request, () -> false);
        if (!completion.complete() || adapter.lastVerificationSnapshot() == null)
            throw new IllegalStateException("D101 incomplete original cache and receipt proof");
        var locks = new DatasetIntervalLock(ledgerPath);
        var lease = locks.findOwned(runId, adapter.conflictScope(request));
        if (lease == null || !lease.inDoubt()) throw new IllegalStateException("D101 uncertain run lost its retained exclusion");
        var sliceProofs = new LinkedHashMap<String,String>();
        for (var child : children(ledger, runId)) {
            if (child.kind() != SyncRunLedger.Kind.SLICE || child.state().terminal()) continue;
            String fingerprint = originalFingerprint(ledger, child.id());
            if (!adapter.verifiedUnits().containsKey(fingerprint))
                throw new IllegalStateException("D101 original submitted unit no longer matches its generation and values");
            sliceProofs.put(child.id(), proof(1, fingerprint, completion.evidence(), true));
        }
        adapter.requireUnchangedVerificationSnapshot();
        if (!port.uncertainSenderStopped()) throw new IllegalStateException("D101 sender proof changed after value verification");
        var state = completion.rows() == 0 ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
        String ownerProof = completion.rows() == 0
                ? JobDefinitionJson.mapper().writeValueAsString(Map.of("sourceComplete", true, "returnedRows", 0,
                    "submittedRows", 0, "responseEvidence", completion.evidence()))
                : proof(completion.rows(), SyncRequestIdentity.fingerprint(request, run.targetId()), completion.evidence(), true);
        for (var child : children(ledger, runId)) {
            if (child.kind() != SyncRunLedger.Kind.SLICE || child.state().terminal()) continue;
            var entry = ledger.get(child.id());
            if (entry.state() == SyncRunState.FETCHED) {
                ledger.transition(entry.id(), entry.revision(), SyncRunState.VALIDATED, entry.payloadJson());
                entry = ledger.get(entry.id());
            }
            if (entry.state() == SyncRunState.SUBMITTED) {
                ledger.transition(entry.id(), entry.revision(), SyncRunState.IN_DOUBT, entry.payloadJson());
                entry = ledger.get(entry.id());
            }
            ledger.transition(entry.id(), entry.revision(), SyncRunState.VERIFIED, sliceProofs.get(entry.id()));
        }
        for (var child : children(ledger, runId))
            if (child.kind() == SyncRunLedger.Kind.ATTEMPT && !child.state().terminal())
                ledger.transition(child.id(), child.revision(), state, ownerProof);
        var owner = ledger.get(runId);
        if (owner.state() == SyncRunState.IN_DOUBT) ledger.transition(runId, owner.revision(), state, ownerProof);
        else if (owner.state() != state) throw new IllegalStateException("D101 reconciled outcome differs from completed owner");
        // Keep the lease if any post-ledger proof fails; a later read-only reconciliation can release it.
        adapter.requireUnchangedVerificationSnapshot();
        if (!port.uncertainSenderStopped()) throw new IllegalStateException("D101 sender stop proof is no longer valid");
        locks.releaseAfterReconciliation(lease, true, true);
        return status(runId);
    }

    private FrozenRequest restoreForReconciliation(SyncRunLedger.Run run) throws Exception {
        var saved = JobDefinitionJson.mapper().readTree(run.frozenJson());
        var parameters = new LinkedHashMap<String,Object>();
        for (var spec : definition().parameters().entrySet()) {
            var value = saved.path("parameters").get(spec.getKey());
            if (value != null && !value.isNull()) parameters.put(spec.getKey(), spec.getValue().type() == ParameterType.DATE
                    ? LocalDate.parse(value.asText()) : value.asText());
        }
        var request = definition().freeze(Mode.valueOf(saved.path("mode").asText()), parameters,
                LocalDate.parse(saved.path("from").asText()), LocalDate.parse(saved.path("to").asText()),
                LocalDate.parse(saved.path("logicalDate").asText()));
        if (!SyncRequestIdentity.fingerprint(request, run.targetId())
                .equals(SyncRequestIdentity.fingerprint(run.frozenJson(), run.targetId()))
                || !run.targetId().equals(parameters.get("target_id")))
            throw new IllegalStateException("D101 original frozen identity changed");
        return request;
    }

    private String originalFingerprint(SyncRunLedger ledger, String sliceId) throws Exception {
        String fingerprint = null;
        for (var event : ledger.events(sliceId, -1, 100)) {
            var value = JobDefinitionJson.mapper().readTree(event.payloadJson()).path("sourceFingerprint").asText("");
            if (!value.isBlank()) {
                if (fingerprint != null && !fingerprint.equals(value)) throw new IllegalStateException("D101 ambiguous original unit");
                fingerprint = value;
            }
        }
        if (fingerprint == null) throw new IllegalStateException("D101 original unit lacks immutable source evidence");
        return fingerprint;
    }

    private static String proof(int units, String fingerprint, String evidence, boolean writerStopped) throws Exception {
        return JobDefinitionJson.mapper().writeValueAsString(Map.of("verification", Map.of("passed", true,
                "expectedRows", units, "actualRows", units, "matchedRows", units, "mismatchedRows", 0,
                "duplicateKeys", 0, "missingKeys", 0, "sourceFingerprint", fingerprint,
                "readbackEvidence", evidence, "writerStopped", writerStopped)));
    }

    private SyncRunLedger.Run requireRun(SyncRunLedger ledger, String runId) throws Exception {
        var run = ledger.getRun(runId);
        if (!JOB_ID.equals(run.jobId()) || run.jobVersion() != definition().version())
            throw new IllegalArgumentException("Run does not belong to D101");
        return run;
    }

    private List<SyncRunLedger.Entry> children(SyncRunLedger ledger, String runId) throws Exception {
        var entries = new ArrayList<SyncRunLedger.Entry>(); String cursor = null;
        while (true) {
            var page = ledger.entries(runId, cursor, 1000); if (page.isEmpty()) break;
            entries.addAll(page);
            if (entries.size() > definition().budget().maxSlices() + 2)
                throw new IllegalStateException("D101 ledger child budget exceeded");
            cursor = page.getLast().id();
        }
        return entries;
    }

    private LocalDate checkpoint(String targetId, LocalDate bootstrap, String sourceVersion) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        LocalDate latest = null; String cursor = null; int pages = 0;
        while (true) {
            var history = ledger.history(JOB_ID, cursor, 1000); if (history.isEmpty()) break;
            for (var item : history) {
                // A no-source window has zero publication units and cannot certify a new cache prefix.
                if (!targetId.equals(item.targetId()) || item.state() != SyncRunState.VERIFIED) continue;
                JsonNode saved = JobDefinitionJson.mapper().readTree(ledger.getRun(item.id()).frozenJson());
                if (!Mode.INCREMENTAL.name().equals(saved.path("mode").asText())
                        || !bootstrap.toString().equals(saved.path("from").asText())
                        || !bootstrap.toString().equals(saved.path("parameters").path("bootstrap_from").asText())
                        || !sourceVersion.equals(saved.path("parameters").path("source_version").asText())) continue;
                LocalDate end = maximumVerifiedSourceDate(ledger, item.id(), bootstrap,
                        LocalDate.parse(saved.path("to").asText()));
                if (end == null) continue;
                if (latest == null || end.isAfter(latest)) latest = end;
            }
            cursor = history.getLast().id();
            if (++pages > 100) throw new IllegalStateException("D101 checkpoint scan budget exceeded");
        }
        return latest;
    }

    private LocalDate maximumVerifiedSourceDate(SyncRunLedger ledger, String runId,
                                                LocalDate from, LocalDate to) throws Exception {
        LocalDate maximum = null;
        for (var child : children(ledger, runId)) {
            if (child.kind() != SyncRunLedger.Kind.SLICE || child.state() != SyncRunState.VERIFIED) continue;
            var proof = JobDefinitionJson.mapper().readTree(child.payloadJson()).path("verification");
            String fingerprint = proof.path("sourceFingerprint").asText("");
            if (!proof.path("passed").asBoolean(false) || proof.path("expectedRows").asInt(-1) != 1
                    || proof.path("matchedRows").asInt(-1) != 1 || fingerprint.isBlank()) continue;
            for (var event : ledger.events(child.id(), -1, 100)) {
                if (event.state() != SyncRunState.FETCHED) continue;
                var page = JobDefinitionJson.mapper().readTree(event.payloadJson());
                if (page.path("returnedRows").asInt(-1) != 1
                        || !fingerprint.equals(page.path("sourceFingerprint").asText())) continue;
                LocalDate date = LocalDate.parse(page.path("cursor").asText());
                if (date.isBefore(from) || date.isAfter(to))
                    throw new IllegalStateException("D101 checkpoint source day lies outside its frozen window");
                if (maximum == null || date.isAfter(maximum)) maximum = date;
            }
        }
        return maximum;
    }

    private static void requireWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 31)
            throw new IllegalArgumentException("Explicit D101 bootstrap prefix of at most 31 days required");
    }

    private record ObservedTarget(EtfMarketOverviewCachePublicationEnvelope snapshot, String error) {}
    private ObservedTarget safeSnapshot(LocalDate day) {
        try { return new ObservedTarget(gateway.preview(day), null); }
        catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ObservedTarget(null, failure.getClass().getSimpleName());
        }
    }

    private EtfMarketOverviewCacheDelegatedPort port(EtfMarketOverviewCachePublicationEnvelope initial) {
        return portFactory == null ? new EtfMarketOverviewCacheDelegatedPort(gateway, jdbc, initial) : portFactory.apply(initial);
    }
}
