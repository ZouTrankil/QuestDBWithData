package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.fund.EtfBasic;
import com.zoutrankil.data.domain.fund.EtfBasicDataset;
import com.zoutrankil.data.domain.fund.EtfBasicKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.domain.temporal.TemporalValues.Precision;
import com.zoutrankil.data.etf.port.EtfBasicDirectorySession;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Emits one bounded market-wide response as at most two ledger/write pages. */
public final class EtfBasicSyncAdapter implements SyncJobRunner.Adapter<EtfBasic, EtfBasicKey> {
    public static final int RUNNER_PAGE_ROWS = 10_000;
    private final EtfBasicSource source;
    private final VerifiedWriteSession<EtfBasic, EtfBasicKey> port;
    private final Path evidenceRoot;
    private final boolean requireFullDirectory;
    private boolean directoryWriteStarted;

    public EtfBasicSyncAdapter(
            TusharePageService pages,
            VerifiedWriteSession<EtfBasic, EtfBasicKey> port,
            Path evidenceRoot) {
        this(pages, port, evidenceRoot, false);
    }

    public EtfBasicSyncAdapter(
            TusharePageService pages,
            VerifiedWriteSession<EtfBasic, EtfBasicKey> port,
            Path evidenceRoot,
            boolean requireFullDirectory) {
        this.requireFullDirectory = requireFullDirectory;
        this.source = new EtfBasicSource(pages, evidenceRoot.resolve("source"));
        this.port = Objects.requireNonNull(port);
        this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
    }

    public static Instant observedAt(FrozenRequest request) {
        validateRequest(request);
        String text = (String) request.parameters().get("observedAt");
        Instant value = Instant.parse(text);
        TemporalValues.requirePrecision(value, Precision.MICROS);
        if (!value.toString().equals(text))
            throw new IllegalArgumentException("Canonical frozen ISO observation instant required");
        return value;
    }

    public static void validateRequest(FrozenRequest request) {
        if (request == null
                || !request.definition().equals(EtfBasicSyncJobOwner.DEFINITION)
                || !request.definition().datasetId().equals(EtfBasicDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion()
                        != EtfBasicDataset.DEFINITION.schemaVersion()
                || request.mode() != Mode.SNAPSHOT
                || request.from() != null
                || request.to() != null
                || !request.parameters().keySet().equals(Set.of("targetId", "observedAt")))
            throw new IllegalArgumentException("Frozen full etf_basic SNAPSHOT request required");
        Object target = request.parameters().get("targetId");
        if (!(target instanceof String id) || !id.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException(
                    "Frozen etf_basic physical target identity required");
        Object observed = request.parameters().get("observedAt");
        if (!(observed instanceof String text) || text.length() > 40)
            throw new IllegalArgumentException("Frozen etf_basic observation timestamp required");
        Instant instant;
        try {
            instant = Instant.parse(text);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Frozen ISO observation timestamp required", invalid);
        }
        TemporalValues.requirePrecision(instant, Precision.MICROS);
        if (!instant.toString().equals(text))
            throw new IllegalArgumentException("Canonical frozen ISO observation instant required");
    }

    @Override
    public void preflight(FrozenRequest request) {
        validateRequest(request);
        if (requireFullDirectory) {
            if (!(port instanceof EtfBasicDirectorySession))
                throw new IllegalArgumentException("正式ETF目录必须提供全目录回读端口");
            var observedDay = observedAt(request).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
            if (!observedDay.equals(request.logicalDate())
                    || !observedDay.equals(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
                throw new IllegalArgumentException("正式目录不提供历史快照；跨日不能重抓当前目录认证旧观察日");
        }
        port.preflight();
    }

    @Override
    public SyncJobRunner.SourceCompletion fetch(
            FrozenRequest request,
            SyncJobRunner.PageConsumer<EtfBasic> consumer,
            BooleanSupplier cancelled)
            throws Exception {
        Instant observedAt = observedAt(request);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("etf_basic snapshot cancelled before source request");
        var result = source.fetch(observedAt, cancelled);
        if (result.rows().isEmpty() || result.rows().size() > EtfBasicSyncJobOwner.MAX_ROWS)
            throw new IllegalStateException(
                    "Only nonempty under-cap etf_basic snapshots can be emitted");
        // 目录保留不在当前供应商结果中的旧基金，但不能把保留行算成本次来源覆盖。
        var expected = new TreeMap<String, String>();
        var currentKeys = new HashSet<String>();
        var retainedKeys = new ArrayList<String>();
        for (var row : result.rows()) {
            String key = EtfBasicDirectoryEvidence.key(row);
            if (!currentKeys.add(key)) throw new IllegalStateException("ETF来源存在重复完整键");
            expected.put(key, EtfBasicDirectoryEvidence.value(port.codec(), row));
        }
        if (port instanceof EtfBasicDirectorySession directory) {
            for (var row : directory.readDirectory()) {
                String key = EtfBasicDirectoryEvidence.key(row);
                if (!currentKeys.contains(key)) {
                    if (expected.putIfAbsent(
                                    key, EtfBasicDirectoryEvidence.value(port.codec(), row))
                            != null) throw new IllegalStateException("ETF旧目录键重复");
                    retainedKeys.add(key);
                }
            }
        }
        if (port instanceof EtfBasicDirectorySession) {
            Files.createDirectories(evidenceRoot);
            FileEvidenceStore.writeNewDurable(
                    evidenceRoot.resolve("full-directory-intent.json"),
                    JobDefinitionJson.canonicalMapper()
                            .writeValueAsBytes(
                                    Map.of(
                                            "expected",
                                            expected,
                                            "sourceCurrentKeys",
                                            currentKeys.stream().sorted().toList(),
                                            "retainedLegacyKeys",
                                            retainedKeys.stream().sorted().toList(),
                                            "sourceReceipt",
                                            result.receipt())));
            directoryWriteStarted = true;
        }
        var chunkFingerprints = new ArrayList<String>();
        int pages = 0;
        for (int start = 0; start < result.rows().size(); start += RUNNER_PAGE_ROWS) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException(
                        "etf_basic snapshot cancelled between runner pages");
            var chunk =
                    List.copyOf(
                            result.rows()
                                    .subList(
                                            start,
                                            Math.min(
                                                    result.rows().size(),
                                                    start + RUNNER_PAGE_ROWS)));
            String fingerprint = chunkFingerprint(result.fingerprint(), pages + 1, chunk);
            consumer.accept(
                    new SyncJobRunner.Page<>(
                            chunk, fingerprint, result.receipt(), Integer.toString(pages + 1)));
            chunkFingerprints.add(fingerprint);
            pages++;
        }
        if (port instanceof EtfBasicDirectorySession directory) {
            var actual = new TreeMap<String, String>();
            for (var row : directory.readDirectory())
                if (actual.putIfAbsent(
                                EtfBasicDirectoryEvidence.key(row),
                                EtfBasicDirectoryEvidence.value(port.codec(), row))
                        != null) throw new IllegalStateException("ETF全目录回读重复键");
            if (!expected.equals(actual))
                throw new IllegalStateException("ETF全目录与当前来源及保留旧行的完整键值不符");
        }
        var completion = new LinkedHashMap<String, Object>();
        completion.put("endpoint", EtfBasicSource.ENDPOINT);
        completion.put("sourceContractVersion", EtfBasicSource.CONTRACT_VERSION);
        completion.put("mode", request.mode().name());
        completion.put("market", "E");
        completion.put("observedAt", observedAt);
        completion.put("sourcePages", result.sourcePages());
        completion.put("runnerPages", pages);
        completion.put("sourceRows", result.rows().size());
        completion.put("sourceFingerprint", result.fingerprint());
        completion.put("sourceReceipt", result.receipt());
        completion.put("runnerFingerprints", chunkFingerprints);
        completion.put("sourceCurrentKeys", currentKeys.stream().sorted().toList());
        completion.put("retainedLegacyKeys", retainedKeys.stream().sorted().toList());
        completion.put("expectedFullDirectory", expected);
        completion.put("fullDirectoryVerified", port instanceof EtfBasicDirectorySession);
        completion.put("complete", true);
        byte[] body = JobDefinitionJson.canonicalMapper().writeValueAsBytes(completion);
        if (body.length > 64 * 1024 * 1024)
            throw new IllegalArgumentException("etf_basic run evidence exceeds 64 MiB");
        Files.createDirectories(evidenceRoot);
        Path file = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        FileEvidenceStore.writeNew(file, body);
        return new SyncJobRunner.SourceCompletion(
                pages, result.rows().size(), true, file.toString());
    }

    @Override
    public boolean recoveryRequired(String runId) {
        // 全目录准入后任一发送或最终回读失败均保留原锁，不能只凭已通过批次重发。
        return directoryWriteStarted
                || Files.exists(evidenceRoot.resolve("full-directory-intent.json"));
    }

    private String chunkFingerprint(String sourceFingerprint, int chunkNumber, List<EtfBasic> rows)
            throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(sourceFingerprint.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Integer.toString(chunkNumber).getBytes(StandardCharsets.US_ASCII));
        for (var row : rows) {
            byte[] encoded = port.codec().canonicalBytes(row);
            digest.update(ByteBuffer.allocate(4).putInt(encoded.length).array());
            digest.update(encoded);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override
    public VerifiedBatchExecutor.Codec<EtfBasic, EtfBasicKey> codec() {
        return port.codec();
    }

    @Override
    public VerifiedBatchExecutor.Port<EtfBasic, EtfBasicKey> port() {
        return port;
    }
}
