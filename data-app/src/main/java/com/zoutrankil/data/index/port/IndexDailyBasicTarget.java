package com.zoutrankil.data.index.port;

/** Target identity and fresh writer assembly without database client types. */
public interface IndexDailyBasicTarget {
    String tableName();
    String targetId();
    IndexDailyBasicWriteSession newWriter(String frozenTargetId);
}
