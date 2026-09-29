package com.zoutrankil.questdbwithdata.domain;

import java.util.Set;

/** Delivery and verification remain separate phases; unresolved writes are never retry-ready. */
public enum SyncRunState {
    PENDING, RUNNING, FETCHED, VALIDATED, SUBMITTED, ACKNOWLEDGED, IN_DOUBT,
    VERIFIED, VERIFIED_EMPTY, PARTIAL, FAILED, CANCELLED;

    public boolean terminal() { return Set.of(VERIFIED, VERIFIED_EMPTY, PARTIAL, FAILED, CANCELLED).contains(this); }
    public void requireTransition(SyncRunState next) {
        if (next == null || terminal() || next == this) throw new IllegalArgumentException("Invalid ledger transition");
        boolean valid = switch (this) {
            case PENDING -> Set.of(RUNNING, FAILED, CANCELLED).contains(next);
            case RUNNING -> Set.of(FETCHED, VERIFIED, VERIFIED_EMPTY, PARTIAL, FAILED, CANCELLED, IN_DOUBT).contains(next);
            case FETCHED -> Set.of(VALIDATED, VERIFIED_EMPTY, PARTIAL, FAILED, CANCELLED).contains(next);
            case VALIDATED -> Set.of(SUBMITTED, VERIFIED, PARTIAL, FAILED, CANCELLED).contains(next);
            case SUBMITTED -> Set.of(ACKNOWLEDGED, IN_DOUBT, PARTIAL).contains(next);
            case ACKNOWLEDGED -> Set.of(VERIFIED, IN_DOUBT, PARTIAL).contains(next);
            case IN_DOUBT -> next == VERIFIED;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Invalid ledger transition: " + this + " -> " + next);
    }
}
