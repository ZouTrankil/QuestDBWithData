package com.zoutrankil.data.flow.port;

/** Physical identity and fresh writer factory for one configured dataset target. */
public interface MoneyflowThsTarget {
    String tableName();
    String targetId();
    MoneyflowThsWriteSession newWriter(String frozenTargetId);
}
