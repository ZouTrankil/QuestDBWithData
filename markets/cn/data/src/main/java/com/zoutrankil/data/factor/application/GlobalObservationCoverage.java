package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.factor.domain.GlobalFactorObservation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Compares physical rows with computed GLOBAL observations, including metadata. */
public final class GlobalObservationCoverage {
    private GlobalObservationCoverage() {}

    public record Key(
            LocalDate date, String entityType, String entityId, String factorId, String version) {}

    public record Delta(List<GlobalFactorObservation> writes, int matching, int unexpected) {
        public Delta {
            writes = List.copyOf(writes);
        }

        public boolean matchesExpected() {
            return writes.isEmpty();
        }
    }

    public static Key key(GlobalFactorObservation row) {
        return new Key(
                row.signalDate(),
                row.entityType(),
                row.entityId(),
                row.factorId(),
                row.definitionVersion());
    }

    public static Delta compare(
            List<GlobalFactorObservation> expected, List<GlobalFactorObservation> physical) {
        var actual = new HashMap<Key, GlobalFactorObservation>();
        for (var row : physical)
            if (actual.putIfAbsent(key(row), row) != null)
                throw new IllegalStateException("Duplicate physical observation key: " + key(row));
        var seen = new HashSet<Key>();
        var writes = new ArrayList<GlobalFactorObservation>();
        int matching = 0;
        for (var row : expected) {
            Key key = key(row);
            if (!seen.add(key))
                throw new IllegalArgumentException("Duplicate expected observation key: " + key);
            if (row.equals(actual.remove(key))) matching++;
            else writes.add(row);
        }
        // Unrequested rows are reported, never deleted by an incremental replay.
        return new Delta(writes, matching, actual.size());
    }
}
