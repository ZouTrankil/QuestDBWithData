package com.zoutrankil.data.index.port;

/** Target identity and fresh writer assembly without database client types. */
public interface IndexDailyMarketTarget {
    String tableName();
    String targetId();
    IndexDailyMarketWriteSession newWriter(String frozenTargetId);
}
