package com.zoutrankil.data.domain;

import java.util.Objects;

/** Captures both source and output versions: RANGE may change the output without changing its base checkpoint. */
public record MarketBreadthDailyV1Snapshot(long sourceId, String sourceDirectory, long sourceTableTxn,
                       long sourceSeqTxn, long sourceWriterTxn, boolean sourceSettled,
                       long mvId, String mvDirectory, long mvTxn, long mvSeqTxn,
                       long mvWriterTxn, boolean mvSettled, boolean valid, boolean caughtUp,
                       String definitionSha, String refreshStarted, String refreshFinished,
                       long refreshBaseTxn, long reportedBaseTxn, String sourcePartition, String viewStatus) {
    public String sourceVersion() { return sourceId + ":" + sourceSeqTxn; }
    public boolean sourceUnchanged(MarketBreadthDailyV1Snapshot other) {
        return other != null && sourceId == other.sourceId
                && Objects.equals(sourceDirectory, other.sourceDirectory)
                && Objects.equals(sourcePartition, other.sourcePartition)
                && sourceTableTxn == other.sourceTableTxn && sourceSeqTxn == other.sourceSeqTxn
                && sourceWriterTxn == other.sourceWriterTxn && sourceSettled && other.sourceSettled;
    }
    public String stableVersion() {
        return sourceVersion() + ":" + sourceTableTxn + ":" + mvId + ":" + mvTxn
                + ":" + mvSeqTxn + ":" + refreshBaseTxn + ":" + refreshStarted + ":" + refreshFinished;
    }
}
