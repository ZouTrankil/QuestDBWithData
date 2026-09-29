package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Receipt-backed last-success lookup for Python's seven-calendar-day snapshot refresh gate. */
public final class IndexWeightCoverage {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_HISTORY = 10_000;
    private static final int MAX_RECEIPT_BYTES = IndexWeightSource.MAX_RECEIPT_BYTES;
    public record Snapshot(String runId, Instant verifiedAt, LocalDate verifiedLocalDate,
            String stockDetailTargetId, int sourceRows) {}
    private IndexWeightCoverage() {}

    public static Optional<Snapshot> latestVerifiedSnapshot(Path ledgerPath, String targetId,
            String stockDetailTargetId) throws Exception {
        Objects.requireNonNull(ledgerPath); Objects.requireNonNull(targetId); Objects.requireNonNull(stockDetailTargetId);
        if (!Files.isRegularFile(ledgerPath)) return Optional.empty();
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var candidates = new ArrayList<Snapshot>(); String after = null; int seen = 0;
        while (true) {
            var page = ledger.history(IndexWeightSyncJobOwner.DEFINITION.jobId(), after, PAGE_SIZE);
            for (var summary : page) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("D021 refresh history exceeds bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion() != IndexWeightSyncJobOwner.DEFINITION.version())
                    continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !IndexWeightSyncJobOwner.DEFINITION.jobId().equals(run.jobId())
                        || run.jobVersion() != IndexWeightSyncJobOwner.DEFINITION.version()) continue;
                JsonNode frozen = JobDefinitionJson.mapper().readTree(run.frozenJson());
                if (!IndexWeightSyncJobOwner.DEFINITION.equals(JobDefinitionJson.mapper()
                        .treeToValue(frozen.path("definition"), SyncJobDefinition.class))
                        || !"SNAPSHOT".equals(frozen.path("mode").asText())) continue;
                JsonNode parameters = frozen.path("parameters");
                if (!targetId.equals(parameters.path("targetId").asText())
                        || !stockDetailTargetId.equals(parameters.path("stockDetailTargetId").asText())) continue;
                Instant observedAt = Instant.parse(parameters.path("observedAt").asText());
                int sourceRows = validateSnapshotReceipts(ledgerPath, ledger, summary.id(), targetId, stockDetailTargetId,
                        summary.state(), observedAt);
                Instant verifiedAt = Instant.parse(summary.updatedAt());
                candidates.add(new Snapshot(summary.id(), verifiedAt,
                        observedAt.atZone(IndexWeightSyncJobOwner.DEFINITION.zone()).toLocalDate(),
                        stockDetailTargetId, sourceRows));
            }
            if (page.size() < PAGE_SIZE) break;
            after = page.getLast().id();
        }
        return candidates.stream().max(Comparator.comparing(Snapshot::verifiedAt).thenComparing(Snapshot::runId));
    }

    private static int validateSnapshotReceipts(Path ledgerPath, SyncRunLedger ledger, String runId,
            String targetId, String stockDetailTargetId, SyncRunState runState, Instant observedAt) throws Exception {
        Path root = ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").toRealPath();
        var codes = new HashSet<String>(); int sourceRows = 0, slices = 0; String after = null;
        while (true) {
            var entries = ledger.entries(runId, after, 100);
            for (var entry : entries) {
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                slices++;
                if (entry.state() != SyncRunState.VERIFIED)
                    throw new IllegalStateException("D021 completed snapshot has a non-verified source slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
                    if (fetched != null) throw new IllegalStateException("Duplicate D021 FETCHED source event");
                    fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                }
                if (fetched == null) throw new IllegalStateException("D021 verified source slice lacks FETCHED receipt");
                String evidence = fetched.path("responseEvidence").asText("");
                String fingerprint = fetched.path("sourceFingerprint").asText("");
                Path receiptPath = requireWithin(evidence, root);
                if (!fingerprint.matches("[0-9a-f]{64}") || Files.size(receiptPath) > MAX_RECEIPT_BYTES)
                    throw new IllegalStateException("D021 snapshot receipt exceeds cap or has invalid SHA-256");
                byte[] receiptBytes = Files.readAllBytes(receiptPath);
                if (!fingerprint.equals(sha256(receiptBytes))) throw new IllegalStateException("D021 source receipt SHA-256 differs from ledger");
                JsonNode receipt = JobDefinitionJson.mapper().readTree(receiptBytes);
                String code = receipt.path("indexCode").asText("");
                var index = IndexWeightUniverse.resolve(code);
                if (index == null || !codes.add(code) || !receipt.path("sourceComplete").asBoolean(false)
                        || !observedAt.toString().equals(receipt.path("observedAt").asText())
                        || receipt.path("returnedRows").asInt(-1) != index.expectedMembers()
                        || receipt.path("returnedRows").asInt(-1) != fetched.path("returnedRows").asInt(-2)
                        || !receipt.path("normalizedRows").isArray() || receipt.path("normalizedRows").isEmpty())
                    throw new IllegalStateException("D021 snapshot receipt scope/count/code is invalid");
                if (index.route() == IndexWeightUniverse.Route.CSINDEX_OSS_XLS) {
                    if (!"csindex_oss_xls".equals(receipt.path("sourceKind").asText()))
                        throw new IllegalStateException("D021 CSIndex receipt route changed");
                    Path raw = requireWithin(receiptPath.getParent().resolve(receipt.path("rawFile").asText(""))
                            .toString(), root);
                    if (Files.size(raw) > IndexWeightSource.MAX_XLS_BYTES)
                        throw new IllegalStateException("D021 raw workbook exceeds bounded evidence size");
                    byte[] rawBytes = Files.readAllBytes(raw);
                    if (rawBytes.length > IndexWeightSource.MAX_XLS_BYTES
                            || rawBytes.length != receipt.path("rawBytes").asInt(-1)
                            || !receipt.path("rawSha256").asText().equals(sha256(rawBytes)))
                        throw new IllegalStateException("D021 raw CSIndex workbook evidence failed its byte/hash check");
                } else {
                    JsonNode enrichment = receipt.path("nameEnrichment");
                    if (!"tushare".equals(receipt.path("sourceKind").asText())
                            || !stockDetailTargetId.equals(enrichment.path("targetId").asText())
                            || !enrichment.path("referenceFingerprint").asText().matches("[0-9a-f]{64}")
                            || !enrichment.path("referenceRows").isArray())
                        throw new IllegalStateException("D021 Tushare/D002 name enrichment evidence is invalid");
                    if (!enrichment.path("referenceFingerprint").asText().equals(sha256(
                            JobDefinitionJson.mapper().writeValueAsBytes(enrichment.path("referenceRows")))))
                        throw new IllegalStateException("D021 name reference fingerprint differs from retained rows");
                }
                if (receipt.path("normalizedRows").size() != receipt.path("returnedRows").asInt())
                    throw new IllegalStateException("D021 normalized receipt count differs from FETCHED count");
                sourceRows = Math.addExact(sourceRows, receipt.path("returnedRows").asInt());
            }
            if (entries.size() < 100) break;
            after = entries.getLast().id();
        }
        var expectedCodes = IndexWeightUniverse.INDEXES.stream().map(IndexWeightUniverse.Index::code).collect(java.util.stream.Collectors.toSet());
        if (slices != IndexWeightUniverse.INDEXES.size() || !codes.equals(expectedCodes) || sourceRows == 0
                || runState != SyncRunState.VERIFIED)
            throw new IllegalStateException("D021 refresh gate requires all eight source receipts of a nonempty verified snapshot");
        return sourceRows;
    }

    private static Path requireWithin(String text, Path root) throws Exception {
        if (text == null || text.isBlank()) throw new IllegalStateException("D021 evidence path missing");
        Path candidate = Path.of(text).toAbsolutePath().normalize().toRealPath();
        if (!candidate.startsWith(root)) throw new IllegalStateException("D021 evidence path escapes this run's source root");
        return candidate;
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
