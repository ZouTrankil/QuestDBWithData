package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact-key incremental metric comparison. Extra target rows never imply deletion.
 * Equality does not establish source freshness: callers must validate the input fingerprint separately.
 */
public final class DailyMetricDelta {
    private DailyMetricDelta() {}

    public record Key(
            Instant date,
            String universeId,
            String universeVersion,
            String factorId,
            String definitionVersion,
            String labelDefinition,
            int horizon,
            String evaluationProfile) {}

    public record Result(
            List<FactorDailyMetricRow> writes,
            int matchingRows,
            int insertedRows,
            int changedRows,
            List<Key> newerTargetKeys,
            int unrelatedTargetRows) {
        public Result {
            writes = List.copyOf(writes);
            newerTargetKeys = List.copyOf(newerTargetKeys);
        }

        public boolean exactMatch() {
            return writes.isEmpty() && newerTargetKeys.isEmpty();
        }
    }

    public static Key key(FactorDailyMetricRow row) {
        Objects.requireNonNull(row);
        Objects.requireNonNull(row.tradeDate());
        for (var text :
                List.of(
                        row.universeId(),
                        row.universeVersion(),
                        row.factorId(),
                        row.definitionVersion(),
                        row.labelDefinition(),
                        row.evaluationProfile()))
            if (text.isBlank())
                throw new IllegalArgumentException("Complete metric business key required");
        if (row.horizon() == null
                || row.horizon() != 1
                || !row.evaluationProfile().equals("base_ic"))
            throw new IllegalArgumentException("Canonical one-day metric key required");
        return new Key(
                row.tradeDate(),
                row.universeId(),
                row.universeVersion(),
                row.factorId(),
                row.definitionVersion(),
                row.labelDefinition(),
                row.horizon(),
                row.evaluationProfile());
    }

    public static Result compare(
            List<FactorDailyMetricRow> expected, List<FactorDailyMetricRow> actual) {
        if (expected.isEmpty() || expected.size() > 10000 || actual.size() > 10000)
            throw new IllegalArgumentException("Bounded nonempty metric scope required");
        var wanted = index(expected);
        var found = index(actual);
        var writes = new ArrayList<FactorDailyMetricRow>();
        var newer = new ArrayList<Key>();
        int same = 0, inserted = 0, changed = 0, unrelated = 0;
        for (var entry : wanted.entrySet()) {
            var row = entry.getValue();
            var existing = found.get(entry.getKey());
            Objects.requireNonNull(row.availableAt(), "Metric availability required");
            if (existing == null) {
                writes.add(row);
                inserted++;
            } else if (row.equals(existing)) same++;
            else if (Objects.requireNonNull(
                            existing.availableAt(), "Target metric availability missing")
                    .isAfter(row.availableAt())) newer.add(entry.getKey());
            else {
                writes.add(row);
                changed++;
            }
        }
        for (var key : found.keySet()) if (!wanted.containsKey(key)) unrelated++;
        return new Result(writes, same, inserted, changed, newer, unrelated);
    }

    private static Map<Key, FactorDailyMetricRow> index(List<FactorDailyMetricRow> rows) {
        var result = new LinkedHashMap<Key, FactorDailyMetricRow>();
        for (var row : rows)
            if (result.putIfAbsent(key(row), row) != null)
                throw new IllegalArgumentException("Duplicate daily metric business key");
        return result;
    }
}
