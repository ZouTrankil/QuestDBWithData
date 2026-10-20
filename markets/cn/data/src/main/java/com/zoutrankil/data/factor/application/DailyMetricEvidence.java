package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;
import com.zoutrankil.data.factor.storage.DailyMetricStorageMapping;
import com.zoutrankil.data.repository.FileEvidenceStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen, bounded publication input; a partial matrix remains partial after successful persistence. */
public final class DailyMetricEvidence {
    private DailyMetricEvidence() {}

    public record Snapshot(
            LocalDate signalDate,
            String inputFingerprint,
            boolean sourceComplete,
            List<FactorDailyMetricRow> rows,
            Set<DailyMetricPublication.Key> expected,
            DailyMetricPublication.Report report,
            String fileSha256) {
        public Snapshot {
            rows = List.copyOf(rows);
            expected = Set.copyOf(expected);
        }

        public boolean complete() {
            return sourceComplete && report.reusableCheckpoint();
        }
    }

    public static String writeNew(
            Path path,
            LocalDate date,
            String fingerprint,
            boolean sourceComplete,
            List<FactorDailyMetricRow> rows,
            Set<DailyMetricPublication.Key> expected)
            throws Exception {
        validate(date, fingerprint, rows, expected, sourceComplete);
        var matrix =
                expected.stream()
                        .sorted(
                                Comparator.comparing(DailyMetricPublication.Key::universeId)
                                        .thenComparing(DailyMetricPublication.Key::factorId))
                        .toList();
        byte[] bytes =
                JobDefinitionJson.mapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "format",
                                        "java.factor_daily_metric.v1",
                                        "signalDate",
                                        date,
                                        "inputFingerprint",
                                        fingerprint,
                                        "sourceComplete",
                                        sourceComplete,
                                        "rows",
                                        rows,
                                        "expected",
                                        matrix));
        if (bytes.length > 32 * 1024 * 1024)
            throw new IllegalArgumentException("Metric evidence exceeds byte budget");
        Files.createDirectories(path.toAbsolutePath().getParent());
        FileEvidenceStore.writeNewDurable(path, bytes);
        return FileEvidenceStore.sha256(bytes);
    }

    public static Snapshot read(Path path) throws Exception {
        byte[] bytes =
                FileEvidenceStore.readBounded(
                        path,
                        32 * 1024 * 1024,
                        () -> new IllegalArgumentException("Metric evidence exceeds byte budget"));
        var json = JobDefinitionJson.mapper();
        var root = json.readTree(bytes);
        if (!"java.factor_daily_metric.v1".equals(root.path("format").asText())
                || !root.path("sourceComplete").isBoolean()
                || !root.path("rows").isArray()
                || !root.path("expected").isArray()
                || root.path("rows").size() > 10000
                || root.path("expected").size() > 10000)
            throw new IllegalArgumentException("Unknown or oversized metric evidence");
        var rows = new ArrayList<FactorDailyMetricRow>();
        for (var node : root.path("rows"))
            rows.add(json.treeToValue(node, FactorDailyMetricRow.class));
        var expected = new HashSet<DailyMetricPublication.Key>();
        for (var node : root.path("expected"))
            if (!expected.add(json.treeToValue(node, DailyMetricPublication.Key.class)))
                throw new IllegalArgumentException("Duplicate expected metric key");
        var date = LocalDate.parse(root.path("signalDate").asText());
        String fingerprint = root.path("inputFingerprint").asText();
        var report =
                validate(
                        date,
                        fingerprint,
                        rows,
                        expected,
                        root.path("sourceComplete").booleanValue());
        return new Snapshot(
                date,
                fingerprint,
                root.path("sourceComplete").booleanValue(),
                rows,
                expected,
                report,
                FileEvidenceStore.sha256(bytes));
    }

    private static DailyMetricPublication.Report validate(
            LocalDate date,
            String fingerprint,
            List<FactorDailyMetricRow> rows,
            Set<DailyMetricPublication.Key> expected,
            boolean sourceComplete) {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Input fingerprint required");
        for (var row : rows) {
            DailyMetricDelta.key(row);
            if (row.maturityAt() == null
                    || row.availableAt() == null
                    || !row.maturityAt().isAfter(row.tradeDate())
                    || row.availableAt().isBefore(row.maturityAt())
                    || !"close_hfq".equals(row.labelPriceBasis()))
                throw new IllegalArgumentException("Mature canonical metric required");
            DailyMetricStorageMapping.values(row);
        }
        return DailyMetricPublication.inspect(date, rows, expected, true, !sourceComplete);
    }
}
