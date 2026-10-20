package com.zoutrankil.data.l2.domain;

/** Identified and settled physical version of the fixed formal daily L2 table. */
public record FormalL2Frontier(String identity, long writerTxn, long sequencerTxn) {
    public FormalL2Frontier {
        if (identity == null || identity.isBlank() || writerTxn != sequencerTxn)
            throw new IllegalStateException(
                    "Formal L2 frontier must be identified and WAL settled");
    }

    public String version() {
        return identity + ":" + writerTxn + ":" + sequencerTxn;
    }
}
