package com.zoutrankil.data.domain;

import java.util.Objects;

/** Raw nullable metadata is preserved; absent physical/WAL frontiers require an independently counted empty table. */
public record MacroCoreMonthlyTargetSnapshot(String targetId, long tableId, String directory, String schemaHash,
                       Long physicalTxn, Long walTxn, long sequenceTxn, long writerTxn,
                       long pendingRows, long bufferedTxns, boolean suspended, long rowCount,
                       Long metadataRowCount) {
    public MacroCoreMonthlyTargetSnapshot {
        Objects.requireNonNull(targetId); Objects.requireNonNull(directory); Objects.requireNonNull(schemaHash);
        if (tableId < 0 || directory.isBlank() || sequenceTxn < 0 || writerTxn < 0 || pendingRows < 0
                || bufferedTxns < 0 || rowCount < 0 || physicalTxn != null && physicalTxn < 0
                || walTxn != null && walTxn < 0 || metadataRowCount != null && metadataRowCount < 0)
            throw new IllegalArgumentException("Complete nonnegative target metadata required");
    }
    public String identity() { return targetId; }
    public boolean settled() {
        if (suspended || pendingRows != 0 || bufferedTxns != 0 || sequenceTxn != writerTxn) return false;
        if (physicalTxn == null || walTxn == null || metadataRowCount == null) return rowCount == 0 && sequenceTxn == 0 && writerTxn == 0
                && (physicalTxn == null || physicalTxn == 0)
                && (metadataRowCount == null || metadataRowCount == 0) && (walTxn == null || walTxn == 0);
        return walTxn == writerTxn;
    }
}
