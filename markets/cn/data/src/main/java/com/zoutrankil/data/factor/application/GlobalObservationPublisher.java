package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Bounded incremental section publication. Acknowledgement alone is not completion. */
public final class GlobalObservationPublisher {
    public record Section(LocalDate date, String entityType, String factorId, String version) {}
    public interface Store {
        /** Check schema, complete dedup key, target identity and WAL health before sending. */
        void preflight();
        List<GlobalFactorObservation> read(Section section);
        /** Return only after a bounded acknowledgement; throw on uncertainty. */
        void send(List<GlobalFactorObservation> rows) throws Exception;
    }
    public record Result(int expectedRows, int reusedRows, int writtenRows, String status) {}
    private final Store store;
    private final int batchRows;
    public GlobalObservationPublisher(Store store, int batchRows) {
        this.store = Objects.requireNonNull(store);
        if (batchRows < 1 || batchRows > 250) throw new IllegalArgumentException("Batch rows must be 1..250");
        this.batchRows = batchRows;
    }

    /** The owner holds the section lease and pins the input snapshot for this call.
     * On any failure retain the attempt as uncertain and reconcile before resuming.
     * Partial sources may publish supplied rows but cannot advance a complete watermark.
     */
    public Result publish(List<GlobalFactorObservation> expected, boolean sourceComplete) throws Exception {
        expected = List.copyOf(expected);
        if (expected.isEmpty() || expected.size() > 100_000)
            throw new IllegalArgumentException("A nonempty section of at most 100000 rows is required");
        Section section = section(expected.getFirst());
        for (var row : expected)
            if (!section.equals(section(row))) throw new IllegalArgumentException("One date/factor/version/type per publication");
        // Reject duplicate expected keys before touching the store.
        GlobalObservationCoverage.compare(expected, List.of());
        store.preflight();
        var delta = GlobalObservationCoverage.compare(expected, store.read(section));
        for (int start = 0; start < delta.writes().size(); start += batchRows) {
            var batch = delta.writes().subList(start, Math.min(start + batchRows, delta.writes().size()));
            store.send(batch);
        }
        var verified = GlobalObservationCoverage.compare(expected, store.read(section));
        if (!verified.matchesExpected())
            throw new IllegalStateException("Observation readback differs after send; reconcile before replay");
        boolean complete = sourceComplete && expected.stream().allMatch(row -> row.qualityStatus().equals("complete"));
        return new Result(expected.size(), delta.matching(), delta.writes().size(), complete ? "VERIFIED" : "PARTIAL");
    }
    private static Section section(GlobalFactorObservation row) {
        return new Section(row.signalDate(), row.entityType(), row.factorId(), row.definitionVersion());
    }
}
