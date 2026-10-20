package com.zoutrankil.data.factor.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Python l2_ingest_receipt compatibility. Scope permission is not a completeness certificate. */
public final class L2SourceScope {
    private L2SourceScope() {}

    public record Receipt(
            LocalDate date, String status, String sha256, List<String> excludedSymbols) {}

    public record Pool(String id, boolean available, Set<String> members) {
        public Pool {
            Objects.requireNonNull(id);
            members = Set.copyOf(members);
        }
    }

    public record Decision(
            Receipt receipt,
            String status,
            boolean publishable,
            List<String> affectedPools,
            List<String> affectedSymbols) {}

    public static Receipt inspect(Path reportDirectory, LocalDate date) {
        Path path =
                reportDirectory.resolve(
                        "dfcf_l2_process_"
                                + date.format(DateTimeFormatter.BASIC_ISO_DATE)
                                + ".json");
        if (Files.notExists(path)) return new Receipt(date, "unrecorded", "", List.of());
        String sha = "";
        try {
            byte[] bytes =
                    FileEvidenceStore.readBounded(
                            path,
                            16 * 1024 * 1024,
                            () -> new IllegalArgumentException("L2 receipt too large"));
            sha = FileEvidenceStore.sha256(bytes);
            var report = JobDefinitionJson.mapper().readTree(bytes);
            if (!report.isObject()
                    || !report.path("date")
                            .asText()
                            .equals(date.format(DateTimeFormatter.BASIC_ISO_DATE))
                    || !textOr(report.get("target_table"), "l2_daily_features")
                            .equals("l2_daily_features"))
                return new Receipt(date, "invalid_receipt", sha, List.of());
            var excluded = new TreeSet<String>();
            if (report.path("excluded_corrupt_symbols").isArray())
                for (var item : report.path("excluded_corrupt_symbols")) {
                    String value = item.asText().trim().toUpperCase(Locale.ROOT);
                    if (!value.isBlank()) excluded.add(value);
                }
            var symbols = List.copyOf(excluded);
            if (!textOr(report.get("run_state"), "completed").trim().equalsIgnoreCase("completed"))
                return new Receipt(date, "incomplete_run_receipt", sha, symbols);
            long total = count(report.get("total_symbols")),
                    requested = count(report.get("requested_symbols"));
            long failed = count(report.get("failed_batches"));
            if (total <= 0 || requested <= 0 || requested > total)
                return new Receipt(date, "invalid_receipt", sha, List.of());
            // Match Python ordering: partial scope is evaluated before failed-batch classification.
            if (truthy(report.get("partial_archive")) || !symbols.isEmpty() || requested < total)
                return new Receipt(date, "partial_archive", sha, symbols);
            if (failed != 0
                    || truthy(report.get("failed_batch_details"))
                    || truthy(report.get("aborted_after_wal_failure")))
                return new Receipt(date, "failed_receipt", sha, List.of());
            return new Receipt(date, "recorded_complete", sha, List.of());
        } catch (Exception invalid) {
            return new Receipt(date, "invalid_receipt", sha, List.of());
        }
    }

    public static Decision assess(Receipt receipt, List<Pool> pools) {
        if (new HashSet<>(pools.stream().map(Pool::id).toList()).size() != pools.size())
            throw new IllegalArgumentException("Unique L2 pool scope required");
        if (Set.of("unrecorded", "recorded_complete").contains(receipt.status()))
            return new Decision(receipt, receipt.status(), true, List.of(), List.of());
        if (!receipt.status().equals("partial_archive"))
            return new Decision(
                    receipt, "blocked_invalid_source_receipt", false, List.of(), List.of());
        var unavailable =
                pools.stream()
                        .filter(p -> !p.available() || p.members().isEmpty())
                        .map(Pool::id)
                        .sorted()
                        .toList();
        if (receipt.excludedSymbols().isEmpty() || pools.isEmpty() || !unavailable.isEmpty())
            return new Decision(
                    receipt, "blocked_partial_scope_unresolved", false, unavailable, List.of());
        var affected = new TreeSet<String>();
        var symbols = new TreeSet<String>();
        for (var pool : pools)
            for (String symbol : pool.members()) {
                String normalized = symbol.trim().toUpperCase(Locale.ROOT);
                if (receipt.excludedSymbols().contains(normalized)) {
                    affected.add(pool.id());
                    symbols.add(normalized);
                }
            }
        return new Decision(
                receipt,
                affected.isEmpty() ? "partial_scope_safe" : "blocked_partial_scope_affected",
                affected.isEmpty(),
                List.copyOf(affected),
                List.copyOf(symbols));
    }

    private static String textOr(JsonNode node, String fallback) {
        return truthy(node) ? node.asText() : fallback;
    }

    private static long count(JsonNode node) {
        if (!truthy(node)) return 0;
        if (node.isBoolean()) return node.asBoolean() ? 1 : 0;
        if (node.isNumber()) return node.longValue();
        return Long.parseLong(node.asText().trim());
    }

    private static boolean truthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return false;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.asDouble() != 0;
        if (node.isContainerNode()) return !node.isEmpty();
        return !node.asText().isEmpty();
    }
}
