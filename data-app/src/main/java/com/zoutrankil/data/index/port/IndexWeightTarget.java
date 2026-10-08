package com.zoutrankil.data.index.port;

/** Target identity and fresh writer assembly without database client types. */
public interface IndexWeightTarget {
    String tableName();
    String targetId();
    IndexWeightWriteSession newWriter(String frozenTargetId);
}
