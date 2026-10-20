package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Reuse a completed child only after fresh bounded source and exact current target readback. */
public final class VerifiedRunRecovery {
    private static final ObjectMapper JSON = JobDefinitionJson.mapper();

    private VerifiedRunRecovery() {}

    public static <T, K> String revalidate(
            SyncRunLedger ledger,
            String priorRun,
            String targetId,
            SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.Adapter<T, K> adapter,
            BooleanSupplier cancelled,
            Path receiptDirectory)
            throws Exception {
        var previous = ledger.get(priorRun);
        if (previous.state() != SyncRunState.VERIFIED
                && previous.state() != SyncRunState.VERIFIED_EMPTY)
            throw new IllegalStateException("Only completed children can be reused");
        var json = JSON;
        var original = ledger.getRun(priorRun);
        String requestFingerprint = SyncRequestIdentity.fingerprint(request, targetId);
        if (!targetId.equals(original.targetId())
                || !requestFingerprint.equals(
                        SyncRequestIdentity.fingerprint(
                                original.frozenJson(), original.targetId())))
            throw new IllegalStateException("Revalidation request differs from completed run");
        long expected =
                previous.state() == SyncRunState.VERIFIED_EMPTY
                        ? 0
                        : json.readTree(previous.payloadJson())
                                .path("verification")
                                .path("expectedRows")
                                .asLong(-1);
        if (expected < 0 || expected > request.definition().budget().maxRows())
            throw new IllegalStateException("Invalid previous verified row budget");
        if (previous.state() == SyncRunState.VERIFIED && expected == 0)
            throw new IllegalStateException("Verified child has no source rows");
        long started = System.nanoTime();
        BooleanSupplier stopped =
                () ->
                        cancelled.getAsBoolean()
                                || Thread.currentThread().isInterrupted()
                                || System.nanoTime() - started
                                        >= request.definition().timeout().toNanos();
        if (stopped.getAsBoolean()) throw new CancellationException("Revalidation stopped");
        var recovery = VerifiedSliceRecovery.load(ledger, priorRun, request, targetId);
        long[] totals = {0, 0};
        var proofs = new ArrayList<VerifiedSliceRecovery.Readback>();
        var fingerprints = new HashSet<String>();
        var keys = new HashSet<K>();
        adapter.preflight(request);
        var completion =
                adapter.fetch(
                        request,
                        page -> {
                            if (stopped.getAsBoolean())
                                throw new CancellationException("Revalidation stopped");
                            if (++totals[0]
                                            > Math.min(
                                                    request.definition().budget().maxPages(),
                                                    request.definition().budget().maxSlices())
                                    || (totals[1] += page.rows().size()) > expected)
                                throw new IllegalStateException(
                                        "Completed source changed or exceeded frozen budget");
                            for (T row : page.rows()) {
                                if (!keys.add(Objects.requireNonNull(adapter.codec().key(row))))
                                    throw new IllegalStateException(
                                            "Repeated key in completed source");
                            }
                            if (!page.rows().isEmpty()) {
                                if (!fingerprints.add(page.sourceFingerprint()))
                                    throw new IllegalStateException(
                                            "Repeated completed source checkpoint");
                                var proof =
                                        recovery.revalidate(page, adapter.codec(), adapter.port());
                                if (proof == null)
                                    throw new IllegalStateException(
                                            "Completed source revision changed");
                                proofs.add(proof);
                            }
                        },
                        stopped);
        if (stopped.getAsBoolean())
            throw new CancellationException("Completed child revalidation cancelled");
        if (completion == null
                || !completion.complete()
                || completion.evidence() == null
                || completion.evidence().isBlank()
                || completion.pages() != totals[0]
                || completion.rows() != totals[1]
                || totals[1] != expected)
            throw new IllegalStateException(
                    "Completed source coverage no longer matches verified child");
        Files.createDirectories(receiptDirectory);
        Path receipt = receiptDirectory.resolve("revalidated-" + UUID.randomUUID() + ".json");
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "java.source_revalidation.v2");
        report.put("priorRunId", priorRun);
        report.put("targetId", targetId);
        report.put("requestFingerprint", requestFingerprint);
        report.put("jobId", original.jobId());
        report.put("jobVersion", original.jobVersion());
        report.put("from", request.from());
        report.put("to", request.to());
        report.put("logicalDate", request.logicalDate());
        report.put("verifiedAt", Instant.now().toString());
        report.put("expectedRows", expected);
        report.put("actualRows", totals[1]);
        report.put("pages", totals[0]);
        report.put("sourceEvidence", completion.evidence());
        report.put("sliceReadbacks", proofs);
        report.put("passed", true);
        FileEvidenceStore.writeNewUtf8(
                receipt, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return receipt.toString();
    }
}
