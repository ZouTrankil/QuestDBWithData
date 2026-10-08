package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.EtfMarketOverviewDailyCacheMapper;
import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.derived.port.EtfMarketOverviewPublicationCodec;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Typed caller rows are assertions of the original owner result, never arbitrary cache payloads. */
public final class EtfMarketOverviewCachePreparedWriteAdapter
        implements SyncJobRunner.Adapter<EtfMarketOverviewCachePublicationEnvelope, EtfMarketOverviewDailyCacheKey>,
                   WriteGroupMemberAdapter {
    private final WriteGroupPlan.Member member;
    private final FrozenRequest request;
    private final EtfMarketOverviewPublicationSession port;
    private final List<EtfMarketOverviewDailyCache> input;
    private final Path evidence;
    private String sourcesFingerprint;

    public EtfMarketOverviewCachePreparedWriteAdapter(WriteGroupPlan plan, String memberId,
            EtfMarketOverviewPublicationSession port, Path evidence) {
        member = plan.members().stream().filter(value -> value.memberId().equals(memberId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown D101 prepared member"));
        if (!member.definition().equals(EtfMarketOverviewDailyCacheDataset.DEFINITION))
            throw new IllegalArgumentException("Exact D101 registered definition required");
        this.port = Objects.requireNonNull(port);
        this.evidence = Objects.requireNonNull(evidence).toAbsolutePath().normalize();
        var mapper = new EtfMarketOverviewDailyCacheMapper();
        input = member.batch().rows().stream().map(mapper::fromValues)
                .sorted(Comparator.comparing(EtfMarketOverviewDailyCache::tradeDate)).toList();
        if (input.isEmpty() || input.size() > 31
                || input.stream().map(EtfMarketOverviewDailyCache::tradeDate).distinct().count() != input.size()
                || ChronoUnit.DAYS.between(input.getFirst().tradeDate(), input.getLast().tradeDate()) + 1 > 31)
            throw new IllegalArgumentException("One current generation per date, within 31 calendar days required");
        var parameter = new Parameter(ParameterType.STRING, true, 128, 1, Set.of());
        var definition = new SyncJobDefinition("write.etf_market_overview_daily_cache", 1,
                member.definition().datasetId(), member.definition().schemaVersion(), member.definition().owner(),
                Set.of(Mode.INGEST), Mode.INGEST,
                Map.of("groupBatch", parameter, "memberBatch", parameter,
                       "planFingerprint", parameter, "payloadFingerprint", parameter),
                "prepared.local", "prepared.single_page", "questdb.full_key_values",
                new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)), Duration.ofMinutes(5),
                new Budget(31, 31, 31, input.size(), 64 * 1024), 0, List.of(), Frequency.MANUAL,
                ZoneOffset.UTC, true, false);
        request = definition.freeze(null, Map.of("groupBatch", plan.batchId(), "memberBatch", member.batchId(),
                "planFingerprint", plan.fingerprint(), "payloadFingerprint", member.batch().fingerprint()),
                null, null, plan.logicalDate());
    }

    @Override public WriteGroupPlan.Member member() { return member; }
    @Override public FrozenRequest request() { return request; }
    private void requireRequest(FrozenRequest actual) {
        if (!SyncRequestIdentity.fingerprint(request, member.targetId())
                .equals(SyncRequestIdentity.fingerprint(actual, member.targetId())))
            throw new IllegalArgumentException("D101 prepared request differs from the frozen member");
    }

    /** Validate all typed rows against fresh original-owner previews before any member can publish. */
    @Override public void preflight(FrozenRequest actual) throws Exception {
        requireRequest(actual);
        for (var row : input) requireExpected(row);
    }

    private EtfMarketOverviewCachePublicationEnvelope requireExpected(EtfMarketOverviewDailyCache row) throws Exception {
        var expected = port.requireExactEnvelope(row);
        if (!expected.knownSourceDate() || expected.cache() == null || !expected.cache().equals(row)
                || !member.targetId().equals(expected.targetId()))
            throw new IllegalArgumentException("Typed cache row differs from current original source generation or values");
        if (sourcesFingerprint == null) sourcesFingerprint = expected.sourcesFingerprint();
        else if (!sourcesFingerprint.equals(expected.sourcesFingerprint()))
            throw new IllegalStateException("D101 source vector changed during prepared input validation");
        return expected;
    }

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest actual,
            SyncJobRunner.PageConsumer<EtfMarketOverviewCachePublicationEnvelope> consumer,
            BooleanSupplier cancelled) throws Exception {
        requireRequest(actual); port.cancellationProbe(cancelled);
        Files.createDirectories(evidence);
        var artifact = evidence.resolve("prepared-etf-input-" + UUID.randomUUID() + ".json");
        var json = JobDefinitionJson.mapper();
        var canonicalLines = new ArrayList<String>();
        var payloadHash = java.security.MessageDigest.getInstance("SHA-256");
        payloadHash.update((member.definition().datasetId() + ":" + member.definition().schemaVersion()
                + ":" + member.definition().objectName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var values : member.batch().rows()) {
            var storage = new LinkedHashMap<String,Object>();
            for (var column : member.definition().columns()) {
                var value = values.get(column.logicalName(), Object.class);
                storage.put(column.storageName(), value == null ? null
                        : com.zoutrankil.data.domain.DatasetStorageValues.storageValue(column, value));
            }
            String line = json.writeValueAsString(storage);
            canonicalLines.add(line);
            payloadHash.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            payloadHash.update((byte) '\n');
        }
        if (!member.batch().fingerprint().equals(HexFormat.of().formatHex(payloadHash.digest())))
            throw new IllegalStateException("Prepared input exact canonical bytes differ from frozen payload fingerprint");
        FileEvidenceStore.writeNewUtf8(artifact,JobDefinitionJson.mapper().writeValueAsString(Map.of(
                "datasetId", member.definition().datasetId(), "memberId", member.memberId(),
                "groupBatch", actual.parameters().get("groupBatch"),
                "memberBatch", actual.parameters().get("memberBatch"),
                "planFingerprint", actual.parameters().get("planFingerprint"),
                "payloadFingerprint", member.batch().fingerprint(), "rows", member.batch().rows(),
                "canonicalPayloadLines", canonicalLines,
                "semantics", "Caller assertions; original Python owner determines and publishes every value")));
        String artifactSha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(artifact)));
        var units = new ArrayList<EtfMarketOverviewCachePublicationEnvelope>();
        for (var row : input) {
            check(cancelled);
            var original = requireExpected(row);
            var responseEvidence = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original.responseEvidence());
            responseEvidence.put("prepared_input_path", artifact.toString());
            responseEvidence.put("prepared_input_sha256", artifactSha);
            var expected = new EtfMarketOverviewCachePublicationEnvelope(original.tradeDate(), original.sourceVersion(),
                    original.cache(), original.receipt(), original.sources(), original.targets(),
                    original.sourcesFingerprint(), original.targetsFingerprint(), original.targetId(), original.sourceFingerprint(),
                    original.sourceRows(), original.knownSourceDate(), original.previewPath(), original.previewSha256(),
                    responseEvidence.toString(), original.previewResponse());
            port.bind(expected);
            consumer.accept(new SyncJobRunner.Page<>(List.of(expected),
                    EtfMarketOverviewPublicationCodec.fingerprint(expected), expected.responseEvidence(),
                    row.tradeDate().toString()));
            check(cancelled); units.add(expected);
        }
        var before = port.preview(input.getFirst().tradeDate());
        for (var expected : units) {
            check(cancelled); port.bind(expected);
            var actualRows = port.readback(List.of(expected.key()));
            if (actualRows == null || actualRows.size() != 1 || !codec().equivalent(expected, actualRows.getFirst())
                    || !port.walSettled())
                throw new IllegalStateException("Prepared cache/receipt full-key values no longer match");
        }
        var after = port.preview(input.getFirst().tradeDate());
        if (!sourcesFingerprint.equals(after.sourcesFingerprint()) || !before.targets().equals(after.targets()))
            throw new IllegalStateException("D101 target/source changed during final complete prepared readback");
        return new SyncJobRunner.SourceCompletion(units.size(), units.size(), true,
                JobDefinitionJson.mapper().writeValueAsString(Map.of("sourceComplete", true,
                        "preparedInput", artifact.toString(), "validatedPublicationUnits", units.size(),
                        "sourcesFingerprint", sourcesFingerprint, "targetId", member.targetId())));
    }

    @Override public VerifiedBatchExecutor.Codec<EtfMarketOverviewCachePublicationEnvelope,EtfMarketOverviewDailyCacheKey> codec() {
        return EtfMarketOverviewPublicationCodec.CODEC;
    }
    @Override public VerifiedBatchExecutor.Port<EtfMarketOverviewCachePublicationEnvelope,EtfMarketOverviewDailyCacheKey> port() { return port; }
    @Override public DatasetIntervalLock.Scope conflictScope(FrozenRequest actual) {
        return DatasetIntervalLock.Scope.allDates(member.definition().datasetId());
    }
    @Override public Duration visibilityTimeout() { return port.visibilityTimeout(); }
    @Override public boolean recoveryRequired(String runId) { return port.unresolved(); }
    @Override public SyncJobRunner.Result execute(SyncRunLedger ledger, DatasetIntervalLock locks,
            String child, String parent, String prior, String target, FrozenRequest actual,
            BooleanSupplier cancelled) throws Exception {
        var runner = new SyncJobRunner<EtfMarketOverviewCachePublicationEnvelope,EtfMarketOverviewDailyCacheKey>(ledger, locks);
        return prior == null ? runner.run(child,parent,target,actual,this,cancelled)
                : runner.resume(child,parent,prior,target,actual,this,cancelled);
    }
    @Override public String revalidate(SyncRunLedger ledger,String prior,String target,FrozenRequest actual,
            BooleanSupplier cancelled,Path evidence) throws Exception {
        return VerifiedRunRecovery.revalidate(ledger,prior,target,actual,this,cancelled,evidence);
    }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("D101 prepared input cancelled");
    }
}
