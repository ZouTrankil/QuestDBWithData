package com.zoutrankil.questdbwithdata.domain;

import java.util.Objects;

/** Transport acknowledgement is not a completion claim. Unknown delivery must be reconciled. */
public record DatasetWriteReceipt(String batchId, Status status, Boolean acknowledged, int submittedRows,
                                  int matchedRows, int mismatchedRows, String verificationEvidence) {
    public enum Status { EMPTY, REJECTED, ACKNOWLEDGED, VERIFIED, IN_DOUBT, FAILED }
    public DatasetWriteReceipt {
        if (batchId == null || batchId.isBlank()) throw new IllegalArgumentException("Batch identity required");
        Objects.requireNonNull(status);
        if (submittedRows < 0 || matchedRows < 0 || mismatchedRows < 0 || matchedRows > submittedRows) {
            throw new IllegalArgumentException("Invalid write/readback counts");
        }
        if (status == Status.EMPTY && submittedRows != 0) throw new IllegalArgumentException("Empty cannot have submitted rows");
        if (status == Status.ACKNOWLEDGED && !Boolean.TRUE.equals(acknowledged)) {
            throw new IllegalArgumentException("Acknowledged status requires transport acknowledgement");
        }
        if (status == Status.VERIFIED && (submittedRows == 0 || matchedRows != submittedRows || mismatchedRows != 0
                || verificationEvidence == null || verificationEvidence.isBlank())) {
            throw new IllegalArgumentException("Verified requires nonempty complete value comparison evidence");
        }
    }
    public boolean verified() { return status == Status.VERIFIED; }
}
