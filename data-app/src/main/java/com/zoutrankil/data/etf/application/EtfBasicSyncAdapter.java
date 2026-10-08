package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Emits one bounded market-wide response as at most two ledger/write pages. */
public final class EtfBasicSyncAdapter implements SyncJobRunner.Adapter<EtfBasic, EtfBasicKey> {
    public static final int RUNNER_PAGE_ROWS = 10_000;
    private final EtfBasicSource source;
    private final VerifiedWriteSession<EtfBasic, EtfBasicKey> port;
    private final Path evidenceRoot;

    public EtfBasicSyncAdapter(TusharePageService pages, VerifiedWriteSession<EtfBasic, EtfBasicKey> port, Path evidenceRoot) {
        this.source = new EtfBasicSource(pages, evidenceRoot.resolve("source"));
        this.port = Objects.requireNonNull(port);
        this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
    }

    public static Instant observedAt(FrozenRequest request) {
        validateRequest(request);
        String text = (String) request.parameters().get("observedAt");
        Instant value = Instant.parse(text);
        com.zoutrankil.data.domain.temporal.TemporalValues.requirePrecision(value,
                com.zoutrankil.data.domain.temporal.TemporalValues.Precision.MICROS);
        if (!value.toString().equals(text)) throw new IllegalArgumentException("Canonical frozen ISO observation instant required");
        return value;
    }

    public static void validateRequest(FrozenRequest request) {
        if (request == null || !request.definition().equals(EtfBasicSyncJobOwner.DEFINITION)
                || !request.definition().datasetId().equals(EtfBasicDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion() != EtfBasicDataset.DEFINITION.schemaVersion()
                || request.mode() != Mode.SNAPSHOT || request.from() != null || request.to() != null
                || !request.parameters().keySet().equals(Set.of("targetId", "observedAt")))
            throw new IllegalArgumentException("Frozen full etf_basic SNAPSHOT request required");
        Object target = request.parameters().get("targetId");
        if (!(target instanceof String id) || !id.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen etf_basic physical target identity required");
        Object observed = request.parameters().get("observedAt");
        if (!(observed instanceof String text) || text.length() > 40)
            throw new IllegalArgumentException("Frozen etf_basic observation timestamp required");
        Instant instant;
        try { instant = Instant.parse(text); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Frozen ISO observation timestamp required", invalid); }
        com.zoutrankil.data.domain.temporal.TemporalValues.requirePrecision(instant,
                com.zoutrankil.data.domain.temporal.TemporalValues.Precision.MICROS);
        if (!instant.toString().equals(text)) throw new IllegalArgumentException("Canonical frozen ISO observation instant required");
    }

    @Override public void preflight(FrozenRequest request) {
        validateRequest(request);
        port.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<EtfBasic> consumer, BooleanSupplier cancelled) throws Exception {
        Instant observedAt = observedAt(request);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("etf_basic snapshot cancelled before source request");
        var result = source.fetch(observedAt, cancelled);
        if (result.rows().isEmpty() || result.rows().size() > EtfBasicSyncJobOwner.MAX_ROWS)
            throw new IllegalStateException("Only nonempty under-cap etf_basic snapshots can be emitted");
        var chunkFingerprints = new ArrayList<String>();
        int pages = 0;
        for (int start = 0; start < result.rows().size(); start += RUNNER_PAGE_ROWS) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("etf_basic snapshot cancelled between runner pages");
            var chunk = List.copyOf(result.rows().subList(start,
                    Math.min(result.rows().size(), start + RUNNER_PAGE_ROWS)));
            String fingerprint = chunkFingerprint(result.fingerprint(), pages + 1, chunk);
            consumer.accept(new SyncJobRunner.Page<>(chunk, fingerprint, result.receipt(), Integer.toString(pages + 1)));
            chunkFingerprints.add(fingerprint);
            pages++;
        }
        var completion = new LinkedHashMap<String, Object>();
        completion.put("endpoint", EtfBasicSource.ENDPOINT);
        completion.put("sourceContractVersion", EtfBasicSource.CONTRACT_VERSION);
        completion.put("mode", request.mode().name()); completion.put("market", "E");
        completion.put("observedAt", observedAt); completion.put("sourcePages", result.sourcePages());
        completion.put("runnerPages", pages); completion.put("sourceRows", result.rows().size());
        completion.put("sourceFingerprint", result.fingerprint()); completion.put("sourceReceipt", result.receipt());
        completion.put("runnerFingerprints", chunkFingerprints); completion.put("complete", true);
        byte[] body = JobDefinitionJson.canonicalMapper()
                .writeValueAsBytes(completion);
        if (body.length > 64 * 1024 * 1024) throw new IllegalArgumentException("etf_basic run evidence exceeds 64 MiB");
        Files.createDirectories(evidenceRoot);
        Path file = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        FileEvidenceStore.writeNew(file,body);
        return new SyncJobRunner.SourceCompletion(pages, result.rows().size(), true, file.toString());
    }

    private String chunkFingerprint(String sourceFingerprint, int chunkNumber, List<EtfBasic> rows) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(sourceFingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0); digest.update(Integer.toString(chunkNumber).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (var row : rows) {
            byte[] encoded = port.codec().canonicalBytes(row);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.length).array()); digest.update(encoded);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override public VerifiedBatchExecutor.Codec<EtfBasic, EtfBasicKey> codec() { return port.codec(); }
    @Override public VerifiedBatchExecutor.Port<EtfBasic, EtfBasicKey> port() { return port; }
}
