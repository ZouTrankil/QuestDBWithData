package com.zoutrankil.data.stock.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Immutable physical evidence used by D012 staging, publication and recovery. */
public final class StockStDailyState {
    private StockStDailyState() {}

    public record Identity(long id, String directory, long writerTxn) {}

    public record Content(long rows, String fingerprint) {
        public Content {
            if (rows < 0 || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Complete D012 content fingerprint required");
        }
    }

    public record Snapshot(Identity identity, Content content) {
        public Snapshot { Objects.requireNonNull(identity); Objects.requireNonNull(content); }
    }

    public record Prepared(String target, String beforePhysicalTarget, LocalDate from, LocalDate to,
                           Snapshot before, Content outside) {
        public Prepared {
            Objects.requireNonNull(target); Objects.requireNonNull(beforePhysicalTarget);
            Objects.requireNonNull(from); Objects.requireNonNull(to);
            Objects.requireNonNull(before); Objects.requireNonNull(outside);
            if (from.isAfter(to)) throw new IllegalArgumentException("Invalid D012 stage window");
        }
    }

    public record Verified(String table, String physicalTarget, Snapshot snapshot,
                           Content outside, Content window,
                           int sourceRows, int batches, String sourceFingerprint, String receipt) {}

    public static boolean sameContent(Content left, Content right) {
        return left != null && right != null && left.rows() == right.rows()
                && left.fingerprint().equals(right.fingerprint());
    }
}
