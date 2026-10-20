package com.zoutrankil.data.strategy.store;

/** 正式方案缺失、不一致，或无法安全使用。 */
public class PlanStoreException extends RuntimeException {
    public PlanStoreException(String message) {
        super(message);
    }

    public PlanStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
