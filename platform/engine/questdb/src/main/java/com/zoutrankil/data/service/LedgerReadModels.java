package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;

/** Application responses for ledger reads; storage records stay behind the application boundary. */
public final class LedgerReadModels {
    private LedgerReadModels() {}

    public enum Kind { RUN, ATTEMPT, SLICE }
    public record Entry(String id, Kind kind, String runId, String parentId, SyncRunState state,
                        long revision, String payloadJson, String updatedAt) {}
    public record Run(String id, String parentRunId, String jobId, int jobVersion, String logicalDate,
                      String targetId, String frozenJson) {}
    public record RunSummary(String id, String parentRunId, String jobId, int jobVersion,
                             String logicalDate, String targetId, SyncRunState state,
                             long revision, String updatedAt) {}

    public static Entry entry(SyncRunLedger.Entry row) {
        return new Entry(row.id(), Kind.valueOf(row.kind().name()), row.runId(), row.parentId(),
                row.state(), row.revision(), row.payloadJson(), row.updatedAt());
    }
}
