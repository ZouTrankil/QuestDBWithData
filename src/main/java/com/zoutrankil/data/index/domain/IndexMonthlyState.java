package com.zoutrankil.data.index.domain;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
public final class IndexMonthlyState {
 private IndexMonthlyState() {}
public record Identity(long id, String directory, long writerTxn) {
        public Identity { Objects.requireNonNull(directory); }
    }
public record Snapshot(Identity identity, List<IndexMonthly> rows, String fingerprint, int bytes) {
        public Snapshot {
            Objects.requireNonNull(identity); rows = List.copyOf(rows);
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || bytes < 0)
                throw new IllegalArgumentException("Bounded D022 snapshot fingerprint required");
        }
    }
public record Prepared(String target, String stage, String logicalTargetId, String runId,
                           String requestFingerprint, String physicalTargetBefore, String stagePhysicalTarget,
                           String code, LocalDate from, LocalDate to, Instant observedAt,
                           String sourceReceipt, String sourceFingerprint, int sourceRows,
                           IndexMonthlyState.Snapshot before, IndexMonthlyState.Snapshot outside) {}
public record Verified(String table, String physicalTarget, IndexMonthlyState.Snapshot snapshot,
                           IndexMonthlyState.Snapshot outside, IndexMonthlyState.Snapshot window,
                           int sourceRows, String sourceFingerprint, String sourceReceipt, String receipt) {}
public record Recovered(Prepared prepared, Verified verified) {}
public record TargetRange(LocalDate min,LocalDate max){public TargetRange{if((min==null)!=(max==null)||min!=null&&min.isAfter(max))throw new IllegalArgumentException("Invalid D022 target range");}}
}
