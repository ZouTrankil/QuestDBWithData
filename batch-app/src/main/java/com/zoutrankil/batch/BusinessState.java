package com.zoutrankil.batch;

/** Product state, deliberately independent of Spring Batch's technical status. */
public enum BusinessState {
    WAITING_SOURCE, WAITING_UPSTREAM, RUNNING, VERIFYING, VERIFIED, VERIFIED_EMPTY,
    PARTIAL, BLOCKED, FAILED, IN_DOUBT;

    public boolean ready() { return this == VERIFIED || this == VERIFIED_EMPTY; }
}
