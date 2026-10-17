package com.zoutrankil.data.l2.port;

/** Physical identity and fresh bounded writers for the configured D086 target. */
public interface L2DailyFeaturesTarget {
    String tableName();
    String targetId() throws Exception;
    L2DailyFeaturesWriteSession newWriter();
    void createIsolatedTarget(String table);
}
