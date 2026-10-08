package com.zoutrankil.data.margin.domain;
import com.zoutrankil.data.domain.MarginZrz;
import java.time.LocalDate;
import java.nio.file.Path;
import java.util.*;
/** Immutable full-table replacement proofs and bounded dataset policy. */
public final class MarginZrzState {
    private MarginZrzState() {}
    public static final int MAX_ROWS=100_000,MAX_BYTES=64*1024*1024,MAX_WINDOW_DAYS=366,API_ROW_CAP=5000;
public record Identity(long id,String directory,long writerTxn){public Identity{Objects.requireNonNull(directory);}}
public record Snapshot(Identity identity,List<MarginZrz> rows,String fingerprint,int bytes){public Snapshot{Objects.requireNonNull(identity);rows=List.copyOf(rows);if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||bytes<0)throw new IllegalArgumentException("Bounded D031 snapshot proof required");}}
public record Prepared(String target,String stage,String logicalTargetId,String physicalTargetBefore,
            String stagePhysicalTarget,String runId,String requestFingerprint,LocalDate from,LocalDate to,
            Snapshot before,Snapshot outside,Path runEvidence) {}
public record Verified(Prepared prepared,Snapshot snapshot,
            List<MarginZrz> authoritativeRows,List<Map<String,Object>> sourceReceipts,String receipt) {
        public Verified { authoritativeRows=List.copyOf(authoritativeRows);sourceReceipts=List.copyOf(sourceReceipts); }
    }
public record TargetRange(LocalDate min,LocalDate max,long rows){public TargetRange{if((min==null)!=(max==null)||min!=null&&min.isAfter(max)||rows<0||(min==null)!=(rows==0))throw new IllegalArgumentException("Invalid D031 range");}public boolean empty(){return rows==0;}}
}
