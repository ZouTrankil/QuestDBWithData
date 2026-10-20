package com.zoutrankil.data.factor.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Plans missing/revised output sections, never advances solely from a maximum date.
 * Coverage must be obtained from current physical observations, not an old receipt.
 */
public final class FactorIncrementalPlanner {
    private FactorIncrementalPlanner() {}

    public record Section(LocalDate date, String factorId, String version, String poolId) {
        public Section {
            Objects.requireNonNull(date);
            for (String value : new String[] {factorId, version, poolId})
                if (value == null || value.isBlank())
                    throw new IllegalArgumentException("Blank section key");
        }
    }

    /** Fingerprint covers warmup dependencies, PIT membership and exposures as well as this date. */
    public record Requested(Section section, String inputFingerprint, int lookbackPeriods) {
        public Requested {
            Objects.requireNonNull(section);
            if (inputFingerprint == null || inputFingerprint.isBlank() || lookbackPeriods < 0)
                throw new IllegalArgumentException("Fingerprint and nonnegative lookback required");
        }
    }

    /** physicalComplete includes key coverage and value verification, not merely a positive row count. */
    public record Coverage(
            Section section,
            String inputFingerprint,
            boolean physicalComplete,
            boolean sourceComplete) {}

    public record Pending(Requested request, String reason) {}

    public record Plan(
            List<Pending> sections,
            List<LocalDate> outputDates,
            LocalDate readFrom,
            LocalDate readThrough) {
        public Plan {
            sections = List.copyOf(sections);
            outputDates = List.copyOf(outputDates);
        }
    }

    public static Plan plan(List<Requested> requested, List<Coverage> observed, int maxDates) {
        if (maxDates < 1) throw new IllegalArgumentException("Positive date batch limit required");
        Map<Section, Coverage> coverage = new HashMap<>();
        for (Coverage row : observed) {
            Objects.requireNonNull(row.section());
            if (coverage.putIfAbsent(row.section(), row) != null)
                throw new IllegalArgumentException(
                        "Ambiguous physical coverage for " + row.section());
        }
        var keys = new HashSet<Section>();
        var pending = new ArrayList<Pending>();
        for (Requested request : requested) {
            if (!keys.add(request.section()))
                throw new IllegalArgumentException("Duplicate requested section");
            Coverage row = coverage.get(request.section());
            String reason =
                    row == null
                            ? "missing"
                            : !row.physicalComplete()
                                    ? "physical_gap"
                                    : !row.sourceComplete()
                                            ? "partial_source"
                                            : !request.inputFingerprint()
                                                            .equals(row.inputFingerprint())
                                                    ? "input_revised"
                                                    : null;
            if (reason != null) pending.add(new Pending(request, reason));
        }
        pending.sort(
                Comparator.comparing((Pending row) -> row.request().section().date())
                        .thenComparing(row -> row.request().section().poolId())
                        .thenComparing(row -> row.request().section().factorId())
                        .thenComparing(row -> row.request().section().version()));
        List<LocalDate> dates =
                pending.stream()
                        .map(row -> row.request().section().date())
                        .distinct()
                        .limit(maxDates)
                        .toList();
        if (dates.isEmpty()) return new Plan(List.of(), dates, null, null);
        var selectedDates = new HashSet<>(dates);
        List<Pending> selected =
                pending.stream()
                        .filter(row -> selectedDates.contains(row.request().section().date()))
                        .toList();
        long lookback =
                selected.stream().mapToLong(row -> row.request().lookbackPeriods()).max().orElse(1);
        // Same baseline policy as Python equity_warmup_calendar_days.
        long warmup = Math.max(460L, (long) Math.ceil(Math.max(1L, lookback) * 1.6) + 7);
        return new Plan(selected, dates, dates.getFirst().minusDays(warmup), dates.getLast());
    }
}
