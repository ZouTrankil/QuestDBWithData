package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** A frozen typed write input becomes one bounded INGEST page in the existing verified runner. */
public final class PreparedWriteAdapter<T, K>
        implements SyncJobRunner.Adapter<T, K>, WriteGroupMemberAdapter {
    private final WriteGroupPlan.Member member;
    private final FrozenRequest request;
    private final Function<DatasetValues, T> decode;
    private final Function<T, DatasetValues> encode;
    private final VerifiedBatchExecutor.Codec<T, K> codec;
    private final VerifiedBatchExecutor.Port<T, K> port;
    private final Supplier<String> currentTarget;
    private final Path evidence;

    public PreparedWriteAdapter(
            WriteGroupPlan plan,
            String memberId,
            Function<DatasetValues, T> decode,
            Function<T, DatasetValues> encode,
            VerifiedBatchExecutor.Codec<T, K> codec,
            VerifiedBatchExecutor.Port<T, K> port,
            Supplier<String> currentTarget,
            Path evidence) {
        this.member =
                plan.members().stream()
                        .filter(m -> m.memberId().equals(memberId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown prepared member"));
        this.decode = Objects.requireNonNull(decode);
        this.encode = Objects.requireNonNull(encode);
        this.codec = Objects.requireNonNull(codec);
        this.port = Objects.requireNonNull(port);
        this.currentTarget = Objects.requireNonNull(currentTarget);
        this.evidence = Objects.requireNonNull(evidence);
        var parameter = new Parameter(ParameterType.STRING, true, 128, 1, Set.of());
        var definition =
                new SyncJobDefinition(
                        "write." + member.definition().datasetId(),
                        1,
                        member.definition().datasetId(),
                        member.definition().schemaVersion(),
                        member.definition().owner(),
                        Set.of(Mode.INGEST),
                        Mode.INGEST,
                        Map.of(
                                "groupBatch",
                                parameter,
                                "memberBatch",
                                parameter,
                                "planFingerprint",
                                parameter,
                                "payloadFingerprint",
                                parameter),
                        "prepared.local",
                        "prepared.single_page",
                        "questdb.full_key_values",
                        new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(30)),
                        Duration.ofMinutes(20),
                        new Budget(1, 1, 1, Math.max(1, member.batch().rows().size()), 1024 * 1024),
                        0,
                        List.of(),
                        Frequency.MANUAL,
                        ZoneOffset.UTC,
                        true,
                        false);
        this.request =
                definition.freeze(
                        null,
                        Map.of(
                                "groupBatch",
                                plan.batchId(),
                                "memberBatch",
                                member.batchId(),
                                "planFingerprint",
                                plan.fingerprint(),
                                "payloadFingerprint",
                                member.batch().fingerprint()),
                        null,
                        null,
                        plan.logicalDate());
    }

    public FrozenRequest request() {
        return request;
    }

    public WriteGroupPlan.Member member() {
        return member;
    }

    private void requireTarget() {
        if (!member.targetId().equals(currentTarget.get()))
            throw new IllegalStateException("Write owner target identity changed");
    }

    private void requireRequest(FrozenRequest actual) {
        if (!SyncRequestIdentity.fingerprint(request, member.targetId())
                .equals(SyncRequestIdentity.fingerprint(actual, member.targetId())))
            throw new IllegalArgumentException("Write request differs from frozen member");
    }

    private List<T> materialize() {
        var typed =
                member.batch().rows().stream()
                        .map(row -> Objects.requireNonNull(decode.apply(row)))
                        .toList();
        var roundTrip =
                DatasetWritePreparation.prepare(
                        member.definition(),
                        typed,
                        encode,
                        new DatasetWritePreparation.Limits(10_000, 16 * 1024 * 1024));
        if (!roundTrip.fingerprint().equals(member.batch().fingerprint()))
            throw new IllegalArgumentException("Write owner mapping changes frozen row values");
        return typed;
    }

    @Override
    public void preflight(FrozenRequest actual) throws Exception {
        requireRequest(actual);
        materialize();
        requireTarget();
        port.preflight();
    }

    @Override
    public SyncJobRunner.SourceCompletion fetch(
            FrozenRequest actual, SyncJobRunner.PageConsumer<T> consumer, BooleanSupplier cancelled)
            throws Exception {
        requireRequest(actual);
        requireTarget();
        if (cancelled.getAsBoolean()) throw new CancellationException("Prepared write cancelled");
        var typed = materialize();
        Files.createDirectories(evidence);
        Path receipt = evidence.resolve("prepared-input-" + UUID.randomUUID() + ".json");
        FileEvidenceStore.writeNewUtf8(
                receipt,
                JobDefinitionJson.mapper()
                        .writeValueAsString(
                                Map.of(
                                        "sourceKind",
                                        "prepared-write-request",
                                        "memberId",
                                        member.memberId(),
                                        "batchId",
                                        member.batchId(),
                                        "targetId",
                                        member.targetId(),
                                        "definition",
                                        member.definition(),
                                        "logicalDate",
                                        actual.logicalDate(),
                                        "fingerprint",
                                        member.batch().fingerprint(),
                                        "rows",
                                        member.batch().rows())));
        if (cancelled.getAsBoolean()) throw new CancellationException("Prepared write cancelled");
        consumer.accept(
                new SyncJobRunner.Page<>(
                        typed, member.batch().fingerprint(), receipt.toString(), null));
        return new SyncJobRunner.SourceCompletion(1, typed.size(), true, receipt.toString());
    }

    @Override
    public VerifiedBatchExecutor.Codec<T, K> codec() {
        return codec;
    }

    @Override
    public VerifiedBatchExecutor.Port<T, K> port() {
        return new VerifiedBatchExecutor.Port<>() {
            public void preflight() throws Exception {
                requireTarget();
                port.preflight();
            }

            public void send(List<T> rows) throws Exception {
                requireTarget();
                port.send(rows);
            }

            public List<T> readback(List<K> keys) throws Exception {
                requireTarget();
                return port.readback(keys);
            }

            public boolean walSettled() throws Exception {
                requireTarget();
                return port.walSettled();
            }

            public boolean uncertainSenderStopped() throws Exception {
                return port.uncertainSenderStopped();
            }
        };
    }

    @Override
    public SyncJobRunner.Result execute(
            SyncRunLedger ledger,
            DatasetIntervalLock locks,
            String child,
            String parent,
            String prior,
            String target,
            FrozenRequest actual,
            BooleanSupplier cancelled)
            throws Exception {
        var runner = new SyncJobRunner<T, K>(ledger, locks);
        return prior == null
                ? runner.run(child, parent, target, actual, this, cancelled)
                : runner.resume(child, parent, prior, target, actual, this, cancelled);
    }

    @Override
    public String revalidate(
            SyncRunLedger ledger,
            String prior,
            String target,
            FrozenRequest actual,
            BooleanSupplier cancelled,
            Path evidence)
            throws Exception {
        return VerifiedRunRecovery.revalidate(
                ledger, prior, target, actual, this, cancelled, evidence);
    }
}
