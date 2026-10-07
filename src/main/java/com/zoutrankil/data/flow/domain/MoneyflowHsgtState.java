package com.zoutrankil.data.flow.domain;

import com.zoutrankil.data.domain.MoneyflowHsgt;
import java.util.*;
import java.time.LocalDate;
import java.nio.file.Path;

/** Immutable values crossing the MoneyflowHsgt application/storage boundary. */
public final class MoneyflowHsgtState {
    private MoneyflowHsgtState() {}
    public record Identity(long id, String directory, long writerTxn) {
        public Identity { Objects.requireNonNull(directory); }
    }
    public record Snapshot(Identity identity, List<MoneyflowHsgt> rows, String fingerprint, int bytes) {
        public Snapshot {
            Objects.requireNonNull(identity); rows = List.copyOf(rows);
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || bytes < 0)
                throw new IllegalArgumentException("Bounded D027 snapshot proof required");
        }
    }
    public record TargetRange(LocalDate min, LocalDate max, long rows) {
        public TargetRange { if ((min==null)!=(max==null)||min!=null&&min.isAfter(max)||rows<0||(min==null)!=(rows==0))throw new IllegalArgumentException("Invalid D027 target range"); }
        public boolean empty() { return rows == 0; }
    }
    public record Prepared(String target,String stage,String logicalTargetId,String physicalTargetBefore,
            String stagePhysicalTarget,String runId,String requestFingerprint,LocalDate from,LocalDate to,
            Snapshot before,Snapshot outside,Path runEvidence) {}
    public record Verified(Prepared prepared,Snapshot snapshot,
            List<MoneyflowHsgt> authoritativeRows,List<Map<String,Object>> sourceReceipts,String receipt) {
        public Verified { authoritativeRows=List.copyOf(authoritativeRows);sourceReceipts=List.copyOf(sourceReceipts); }
    }
}
