package com.zoutrankil.data.flow.port;

/** Physical identity and fresh writer factory for one configured dataset target. */
public interface MoneyflowTarget {
    String tableName();
    String targetId();
    MoneyflowWriteSession newWriter(String frozenTargetId);
}
