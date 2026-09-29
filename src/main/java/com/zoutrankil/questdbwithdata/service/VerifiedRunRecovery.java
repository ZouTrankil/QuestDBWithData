package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Reuse a completed child only after fresh bounded source and exact current target readback. */
public final class VerifiedRunRecovery {
    private VerifiedRunRecovery() {}
    public static <T,K> String revalidate(SyncRunLedger ledger, String priorRun, String targetId,
            SyncJobDefinition.FrozenRequest request, SyncJobRunner.Adapter<T,K> adapter,
            BooleanSupplier cancelled, Path receiptDirectory) throws Exception {
        var previous = ledger.get(priorRun);
        if (previous.state() != SyncRunState.VERIFIED && previous.state() != SyncRunState.VERIFIED_EMPTY)
            throw new IllegalStateException("Only completed children can be reused");
        var json = new ObjectMapper();
        long expected = previous.state() == SyncRunState.VERIFIED_EMPTY ? 0
                : json.readTree(previous.payloadJson()).path("verification").path("expectedRows").asLong(-1);
        if (expected < 0 || expected > request.definition().budget().maxRows())
            throw new IllegalStateException("Invalid previous verified row budget");
        if (previous.state() == SyncRunState.VERIFIED && expected == 0)
            throw new IllegalStateException("Verified child has no source rows");
        long started = System.nanoTime();
        BooleanSupplier stopped = () -> cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()
                || System.nanoTime() - started >= request.definition().timeout().toNanos();
        if (stopped.getAsBoolean()) throw new java.util.concurrent.CancellationException("Revalidation stopped");
        var recovery = VerifiedSliceRecovery.load(ledger, priorRun, request, targetId);
        long[] totals = {0, 0};
        var proofs = new ArrayList<VerifiedSliceRecovery.Readback>();
        var fingerprints = new HashSet<String>();
        var keys = new HashSet<K>();
        adapter.preflight(request);
        var completion = adapter.fetch(request, page -> {
            if (stopped.getAsBoolean()) throw new java.util.concurrent.CancellationException("Revalidation stopped");
            if (++totals[0] > Math.min(request.definition().budget().maxPages(), request.definition().budget().maxSlices())
                    || (totals[1] += page.rows().size()) > expected)
                throw new IllegalStateException("Completed source changed or exceeded frozen budget");
            for (T row : page.rows()) {
                if (!keys.add(Objects.requireNonNull(adapter.codec().key(row))))
                    throw new IllegalStateException("Repeated key in completed source");
            }
            if (!page.rows().isEmpty()) {
                if (!fingerprints.add(page.sourceFingerprint()))
                    throw new IllegalStateException("Repeated completed source checkpoint");
                var proof = recovery.revalidate(page, adapter.codec(), adapter.port());
                if (proof == null) throw new IllegalStateException("Completed source revision changed");
                proofs.add(proof);
            }
        }, stopped);
        if (stopped.getAsBoolean())
            throw new java.util.concurrent.CancellationException("Completed child revalidation cancelled");
        if (completion == null || !completion.complete() || completion.evidence() == null
                || completion.evidence().isBlank() || completion.pages() != totals[0]
                || completion.rows() != totals[1] || totals[1] != expected)
            throw new IllegalStateException("Completed source coverage no longer matches verified child");
        Files.createDirectories(receiptDirectory);
        Path receipt = receiptDirectory.resolve("revalidated-" + UUID.randomUUID() + ".json");
        Files.writeString(receipt, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "priorRunId", priorRun, "targetId", targetId, "verifiedAt", Instant.now().toString(),
                "expectedRows", expected, "actualRows", totals[1], "pages", totals[0],
                "sourceEvidence", completion.evidence(), "sliceReadbacks", proofs, "passed", true)),
                StandardOpenOption.CREATE_NEW);
        return receipt.toString();
    }
}
