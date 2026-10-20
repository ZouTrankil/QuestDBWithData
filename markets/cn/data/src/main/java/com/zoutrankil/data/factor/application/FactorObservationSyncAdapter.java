package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Publishes a bounded, frozen native-computation result through the existing run ledger. */
public final class FactorObservationSyncAdapter
        implements SyncJobRunner.Adapter<GlobalFactorObservation, GlobalObservationCoverage.Key> {
    private final List<GlobalFactorObservation> rows;
    private final boolean sourceComplete;
    private final Path evidence;
    private final String fingerprint;
    private final FactorObservationWritePort session;

    public FactorObservationSyncAdapter(
            List<GlobalFactorObservation> rows,
            boolean sourceComplete,
            Path evidence,
            String fingerprint,
            FactorObservationWritePort session) {
        if (rows.isEmpty() || rows.size() > 100_000)
            throw new IllegalArgumentException("Bounded nonempty result required");
        this.rows = List.copyOf(rows);
        this.sourceComplete = sourceComplete;
        this.evidence = Objects.requireNonNull(evidence).toAbsolutePath().normalize();
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen SHA-256 evidence fingerprint required");
        this.fingerprint = fingerprint;
        this.session = Objects.requireNonNull(session);
    }

    @Override
    public void preflight(SyncJobDefinition.FrozenRequest request) throws Exception {
        if (!"factor_observation".equals(request.definition().datasetId())
                || request.from() == null
                || request.to() == null
                || !fingerprint.equals(request.parameters().get("factorEvidenceSha256")))
            throw new IllegalArgumentException(
                    "Frozen factor observation interval and evidence required");
        if (rows.size() > request.definition().budget().maxRows())
            throw new IllegalArgumentException("Computed observations exceed job row budget");
        var keys = new HashSet<GlobalObservationCoverage.Key>();
        for (var row : rows) {
            if (row.signalDate().isBefore(request.from())
                    || row.signalDate().isAfter(request.to())
                    || !keys.add(GlobalObservationCoverage.key(row)))
                throw new IllegalArgumentException(
                        "Duplicate or out-of-window computed observation");
        }
        verifyEvidence();
        session.preflight();
        if (!session.targetIdentity().equals(request.parameters().get("targetId")))
            throw new IllegalStateException(
                    "Connected observation target differs from frozen request");
    }

    @Override
    public SyncJobRunner.SourceCompletion fetch(
            SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<GlobalFactorObservation> consumer,
            BooleanSupplier cancelled)
            throws Exception {
        verifyEvidence();
        int pages = 0;
        int pageRows = FactorObservationJobService.pageRows(request.definition().version());
        for (int start = 0; start < rows.size(); start += pageRows) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException("Factor publication cancelled between pages");
            int end = Math.min(start + pageRows, rows.size());
            String pageFingerprint =
                    FileEvidenceStore.sha256(
                            ("factor-observation-page-v1\n"
                                            + fingerprint
                                            + "\n"
                                            + start
                                            + "\n"
                                            + end)
                                    .getBytes(StandardCharsets.UTF_8));
            consumer.accept(
                    new SyncJobRunner.Page<>(
                            rows.subList(start, end),
                            pageFingerprint,
                            evidence.toString(),
                            Integer.toString(pages++)));
        }
        return new SyncJobRunner.SourceCompletion(
                pages, rows.size(), sourceComplete, evidence.toString());
    }

    private void verifyEvidence() throws Exception {
        var snapshot = FactorObservationEvidence.read(evidence);
        if (!fingerprint.equals(snapshot.fileSha256()))
            throw new IllegalStateException(
                    "Factor computation evidence changed after request freeze");
        if (sourceComplete != snapshot.sourceComplete() || !rows.equals(snapshot.rows()))
            throw new IllegalStateException("Computed observations differ from frozen evidence");
    }

    @Override
    public VerifiedBatchExecutor.Codec<GlobalFactorObservation, GlobalObservationCoverage.Key>
            codec() {
        return session.codec();
    }

    @Override
    public VerifiedBatchExecutor.Port<GlobalFactorObservation, GlobalObservationCoverage.Key>
            port() {
        return session;
    }

    /** Observed O3 application reached 84s for 2000 rows; preserve exact verification with a finite wait. */
    @Override
    public int maxWriteBatchRows(SyncJobDefinition.FrozenRequest request) {
        return FactorObservationJobService.pageRows(request.definition().version());
    }

    @Override
    public Duration visibilityTimeout() {
        return Duration.ofMinutes(3);
    }
}
