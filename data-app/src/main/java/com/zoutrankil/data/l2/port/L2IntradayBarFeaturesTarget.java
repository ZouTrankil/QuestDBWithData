package com.zoutrankil.data.l2.port;

public interface L2IntradayBarFeaturesTarget {
    String tableName();
    String targetId()throws Exception;
    L2IntradayBarFeaturesWriteSession newWriter();
    void createIsolatedTarget(String table);
}
