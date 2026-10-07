package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.EtfMarketOverviewCachePublicationEnvelope;
import com.zoutrankil.data.domain.EtfMarketOverviewDailyCacheKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.repository.EtfMarketOverviewCacheDelegatedPort;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** One verified publication unit per known source day, including an empty JOIN receipt. */
public final class EtfMarketOverviewDailyCacheMaterializeAdapter
        implements SyncJobRunner.Adapter<EtfMarketOverviewCachePublicationEnvelope, EtfMarketOverviewDailyCacheKey> {
    private final EtfMarketOverviewCacheOwnerGateway gateway;
    private final EtfMarketOverviewCacheDelegatedPort port;
    private final EtfMarketOverviewCachePublicationEnvelope frozen;
    private final LinkedHashMap<String, EtfMarketOverviewCachePublicationEnvelope> verified = new LinkedHashMap<>();
    private SyncJobDefinition.FrozenRequest active;
    private EtfMarketOverviewCachePublicationEnvelope lastVerificationSnapshot;
    private long sourceScanRows;

    public EtfMarketOverviewDailyCacheMaterializeAdapter(EtfMarketOverviewCacheOwnerGateway gateway,
            EtfMarketOverviewCacheDelegatedPort port, EtfMarketOverviewCachePublicationEnvelope frozen) {
        this.gateway = Objects.requireNonNull(gateway);
        this.port = Objects.requireNonNull(port);
        this.frozen = Objects.requireNonNull(frozen);
    }

    @Override public DatasetIntervalLock.Scope conflictScope(SyncJobDefinition.FrozenRequest request) {
        // The owner publishes cache plus shared coverage; target snapshots cover both whole tables.
        return DatasetIntervalLock.Scope.allDates(request.definition().datasetId());
    }

    @Override public void preflight(SyncJobDefinition.FrozenRequest request) throws Exception {
        requireRequest(request);
        active = request;
        requireFrozen(gateway.preview(request.from()));
        port.bind(frozen);
        port.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<EtfMarketOverviewCachePublicationEnvelope> consumer,
            BooleanSupplier cancelled) throws Exception {
        requireRequest(request);
        active = request;
        port.cancellationProbe(cancelled);
        verified.clear(); sourceScanRows = 0;
        var units = new ArrayList<EtfMarketOverviewCachePublicationEnvelope>();
        for (LocalDate day = request.from(); !day.isAfter(request.to()); day = day.plusDays(1)) {
            check(cancelled);
            var expected = gateway.preview(day); // SELECT only: no Python read or publisher before SUBMITTED.
            requireFrozen(expected);
            if (!day.equals(expected.tradeDate())) throw new IllegalStateException("D101 preview date differs");
            if (!expected.knownSourceDate()) continue;
            sourceScanRows = Math.addExact(sourceScanRows, expected.sourceRows());
            port.bind(expected);
            String fingerprint = EtfMarketOverviewCacheDelegatedPort.fingerprint(expected);
            consumer.accept(new SyncJobRunner.Page<>(List.of(expected), fingerprint,
                    expected.responseEvidence(), day.toString()));
            check(cancelled);
            units.add(expected);
        }
        // Recheck the complete prefix, with one stable target frontier around every exact value/receipt read.
        var before = gateway.preview(request.from());
        requireFrozen(before);
        for (var expected : units) {
            check(cancelled);
            verifyOne(expected);
            verified.put(EtfMarketOverviewCacheDelegatedPort.fingerprint(expected), expected);
        }
        var after = gateway.preview(request.from());
        requireFrozen(after);
        if (!before.targets().equals(after.targets()))
            throw new IllegalStateException("D101 cache/coverage changed during complete prefix verification");
        lastVerificationSnapshot = after;
        return new SyncJobRunner.SourceCompletion(units.size(), units.size(), true, evidence(request, units));
    }

    /** Read-only recovery of the whole original request; no owner invocation or native write. */
    public SyncJobRunner.SourceCompletion revalidateOnly(SyncJobDefinition.FrozenRequest request,
                                                         BooleanSupplier cancelled) throws Exception {
        return fetch(request, page -> verifyOne(page.rows().getFirst()), cancelled);
    }

    private void verifyOne(EtfMarketOverviewCachePublicationEnvelope expected) throws Exception {
        port.bind(expected);
        var actual = port.readback(List.of(expected.key()));
        if (actual == null || actual.size() != 1 || !codec().equivalent(expected, actual.getFirst())
                || !port.walSettled())
            throw new IllegalStateException("D101 exact cache/receipt publication unit is absent or changed");
    }

    @Override public VerifiedBatchExecutor.Codec<EtfMarketOverviewCachePublicationEnvelope, EtfMarketOverviewDailyCacheKey> codec() {
        return EtfMarketOverviewCacheDelegatedPort.CODEC;
    }

    @Override public VerifiedBatchExecutor.Port<EtfMarketOverviewCachePublicationEnvelope, EtfMarketOverviewDailyCacheKey> port() {
        if (active == null || active.mode() != SyncJobDefinition.Mode.RECONCILE) return port;
        return new VerifiedBatchExecutor.Port<>() {
            @Override public void preflight() throws Exception { port.preflight(); }
            @Override public void send(List<EtfMarketOverviewCachePublicationEnvelope> rows) throws Exception {
                if (rows.size() != 1) throw new IllegalArgumentException("D101 one-day validation unit required");
                verifyOne(rows.getFirst()); // No delegate invocation, no cache write, no receipt write.
            }
            @Override public List<EtfMarketOverviewCachePublicationEnvelope> readback(List<EtfMarketOverviewDailyCacheKey> keys)
                    throws Exception { return port.readback(keys); }
            @Override public boolean walSettled() throws Exception { return port.walSettled(); }
            @Override public boolean uncertainSenderStopped() { return true; }
        };
    }

    @Override public boolean recoveryRequired(String runId) throws Exception { return port.unresolved(); }
    @Override public Duration visibilityTimeout() { return port.visibilityTimeout(); }
    public long sourceScanRows() { return sourceScanRows; }
    public EtfMarketOverviewCachePublicationEnvelope lastVerificationSnapshot() { return lastVerificationSnapshot; }
    public Map<String, EtfMarketOverviewCachePublicationEnvelope> verifiedUnits() { return Map.copyOf(verified); }

    public void requireUnchangedVerificationSnapshot() throws Exception {
        if (lastVerificationSnapshot == null)
            throw new IllegalStateException("D101 lacks complete actual verification");
        var current = gateway.preview(active.from());
        requireFrozen(current);
        if (!lastVerificationSnapshot.targets().equals(current.targets()))
            throw new IllegalStateException("D101 cache/coverage changed after complete verification");
    }

    private void requireRequest(SyncJobDefinition.FrozenRequest request) {
        if (request == null || !EtfMarketOverviewDailyCacheJobService.definition().equals(request.definition())
                || request.from() == null || request.to() == null
                || !frozen.sourcesFingerprint().equals(request.parameters().get("source_version"))
                || !frozen.targetId().equals(request.parameters().get("target_id"))
                || request.mode() == SyncJobDefinition.Mode.INCREMENTAL
                    && !request.from().equals(request.parameters().get("bootstrap_from")))
            throw new IllegalArgumentException("Exact D101 source vector, target and complete anchor prefix required");
    }

    private void requireFrozen(EtfMarketOverviewCachePublicationEnvelope current) {
        if (current == null || !frozen.sourcesFingerprint().equals(current.sourcesFingerprint())
                || !frozen.targetId().equals(current.targetId()))
            throw new IllegalStateException("D101 source vector or physical cache/coverage target changed");
    }

    private String evidence(SyncJobDefinition.FrozenRequest request,
                            List<EtfMarketOverviewCachePublicationEnvelope> units) throws Exception {
        var details = new LinkedHashMap<String,Object>();
        details.put("owner", "python.MarketBarometerReadThroughCache.read");
        details.put("mode", request.mode().name());
        details.put("sourceVersion", frozen.sourcesFingerprint());
        details.put("targetId", frozen.targetId());
        details.put("fromInclusive", request.from().toString());
        details.put("toExclusive", request.to().plusDays(1).toString());
        details.put("validatedPublicationUnits", units.size());
        details.put("cacheRows", units.stream().filter(unit -> unit.cache() != null).count());
        details.put("emptyReceiptUnits", units.stream().filter(unit -> unit.cache() == null).count());
        details.put("maxKnownSourceDate", units.isEmpty() ? null : units.getLast().tradeDate().toString());
        details.put("sourceScanRows", sourceScanRows);
        details.put("unitFingerprints", List.copyOf(verified.keySet()));
        details.put("sourceComplete", true);
        details.put("targets", lastVerificationSnapshot.targets());
        if (request.mode() == SyncJobDefinition.Mode.RECONCILE) {
            details.put("delegate_invocations", 0); details.put("cache_writes", 0); details.put("receipt_writes", 0);
        }
        return JobDefinitionJson.mapper().writeValueAsString(details);
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("D101 materialization cancelled");
    }
}
