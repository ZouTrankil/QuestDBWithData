package com.zoutrankil.data.strategy.store;

/** An authoritative plan is missing, inconsistent, or cannot be used safely. */
public class PlanStoreException extends RuntimeException {
    public PlanStoreException(String message) { super(message); }
    public PlanStoreException(String message, Throwable cause) { super(message, cause); }
}
