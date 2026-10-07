package com.zoutrankil.data.margin.port;

/** Physical identity and fresh writer factory for one configured dataset target. */
public interface MarginDetailTarget {
    String tableName();
    String targetId();
    MarginDetailWriteSession newWriter(String frozenTargetId);
}
