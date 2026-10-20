package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.factor.domain.GlobalFactorObservation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Bounded physical reconciliation. Publication must still recheck under its write-session lock. */
public final class GlobalObservationIncrementalRead {
    private GlobalObservationIncrementalRead() {}

    public record Section(LocalDate date, String entityType, String factorId, String version) {}

    @FunctionalInterface
    public interface Reader {
        List<GlobalFactorObservation> read(Section section, int maxRows);
    }

    public record Result(
            List<GlobalFactorObservation> writes, int matching, int unexpected, int sections) {
        public Result {
            writes = List.copyOf(writes);
        }
    }

    public static Result reconcile(List<GlobalFactorObservation> expected, Reader reader) {
        Objects.requireNonNull(reader);
        if (expected.size() > 100_000)
            throw new IllegalArgumentException("Expected observations exceed row budget");
        // Reject duplicate expected keys before any database operation.
        GlobalObservationCoverage.compare(expected, List.of());
        var groups = new LinkedHashMap<Section, List<GlobalFactorObservation>>();
        for (var row : expected)
            groups.computeIfAbsent(section(row), ignored -> new ArrayList<>()).add(row);
        var writes = new ArrayList<GlobalFactorObservation>();
        int matching = 0, unexpected = 0;
        for (var entry : groups.entrySet()) {
            if (Thread.currentThread().isInterrupted())
                throw new CancellationException("Coverage read cancelled");
            var physical = List.copyOf(reader.read(entry.getKey(), 100_000));
            if (physical.size() > 100_000)
                throw new IllegalStateException("Physical section exceeds row budget");
            for (var row : physical)
                if (!section(row).equals(entry.getKey()))
                    throw new IllegalStateException("Physical reader returned another section");
            var delta = GlobalObservationCoverage.compare(entry.getValue(), physical);
            writes.addAll(delta.writes());
            matching += delta.matching();
            unexpected += delta.unexpected();
        }
        return new Result(writes, matching, unexpected, groups.size());
    }

    private static Section section(GlobalFactorObservation row) {
        return new Section(
                row.signalDate(), row.entityType(), row.factorId(), row.definitionVersion());
    }
}
