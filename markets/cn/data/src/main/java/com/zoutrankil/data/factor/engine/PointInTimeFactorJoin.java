package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Per-security backward as-of join; availability dates must be assigned by the source owner. */
public final class PointInTimeFactorJoin {
    private PointInTimeFactorJoin() {}

    public record Observation(LocalDate observedDate, LocalDate availableDate, double value) {
        public Observation {
            Objects.requireNonNull(availableDate);
        }
    }

    /** Borrowed immutable source vector; fields follow the caller's declared column order. */
    public record VectorObservation(LocalDate availableDate, double[] values) {
        public VectorObservation {
            Objects.requireNonNull(availableDate);
            Objects.requireNonNull(values);
        }
    }

    /** One stable sort and as-of cursor for all columns of the same security/source. */
    public static double[][] joinColumns(
            LocalDate[] signalDates, List<VectorObservation> source, int columnCount) {
        return joinColumns(signalDates, source, columnCount, false);
    }

    /** Preserve source values for pipelines that apply missing-value rules after arithmetic. */
    public static double[][] joinRawColumns(
            LocalDate[] signalDates, List<VectorObservation> source, int columnCount) {
        return joinColumns(signalDates, source, columnCount, true);
    }

    private static double[][] joinColumns(
            LocalDate[] signalDates,
            List<VectorObservation> source,
            int columnCount,
            boolean preserveNonFinite) {
        if (columnCount < 1 || columnCount > 512)
            throw new IllegalArgumentException("Expected 1..512 columns");
        for (int i = 0; i < signalDates.length; i++) {
            Objects.requireNonNull(signalDates[i]);
            if (i > 0 && !signalDates[i].isAfter(signalDates[i - 1]))
                throw new IllegalArgumentException("Strictly ordered unique signal dates required");
        }
        for (var row : source)
            if (row.values().length != columnCount)
                throw new IllegalArgumentException("PIT source column count differs");
        var sorted =
                source.stream()
                        .sorted(Comparator.comparing(VectorObservation::availableDate))
                        .toList();
        double[][] result = new double[columnCount][signalDates.length];
        for (var column : result) Arrays.fill(column, Double.NaN);
        int cursor = 0;
        double[] current = null;
        for (int i = 0; i < signalDates.length; i++) {
            while (cursor < sorted.size()
                    && !sorted.get(cursor).availableDate().isAfter(signalDates[i]))
                current = sorted.get(cursor++).values();
            if (current != null)
                for (int c = 0; c < columnCount; c++)
                    result[c][i] =
                            preserveNonFinite || Double.isFinite(current[c])
                                    ? current[c]
                                    : Double.NaN;
        }
        return result;
    }

    public static double[] join(LocalDate[] signalDates, List<Observation> source, boolean margin) {
        for (int i = 0; i < signalDates.length; i++) {
            Objects.requireNonNull(signalDates[i]);
            if (i > 0 && !signalDates[i].isAfter(signalDates[i - 1]))
                throw new IllegalArgumentException("Strictly ordered unique signal dates required");
        }
        if (margin)
            for (var row : source)
                if (row.observedDate() == null || !row.availableDate().isAfter(row.observedDate()))
                    throw new IllegalArgumentException(
                            "Margin availability must follow its observation date");
        // Stable sort preserves Python's last-record-wins policy for the same available date.
        var rows =
                source.stream().sorted(Comparator.comparing(Observation::availableDate)).toList();
        double[] result = new double[signalDates.length];
        Arrays.fill(result, Double.NaN);
        int cursor = 0;
        double current = Double.NaN;
        for (int i = 0; i < signalDates.length; i++) {
            while (cursor < rows.size()
                    && !rows.get(cursor).availableDate().isAfter(signalDates[i])) {
                current = rows.get(cursor++).value();
                if (!Double.isFinite(current)) current = Double.NaN;
            }
            result[i] = current;
        }
        return result;
    }
}
