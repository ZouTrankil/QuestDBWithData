package com.zoutrankil.data.flow.port;

/** Physical identity and fresh writer factory for one configured dataset target. */
public interface MoneyflowDcTarget {
    String tableName();
    String targetId();
    MoneyflowDcWriteSession newWriter(String frozenTargetId);
}
