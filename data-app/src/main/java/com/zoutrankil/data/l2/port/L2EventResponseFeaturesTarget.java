package com.zoutrankil.data.l2.port;

/** Configured D088 target and a factory for independent physical write sessions. */
public interface L2EventResponseFeaturesTarget {
    String tableName();
    String targetId() throws Exception;
    L2EventResponseFeaturesWriteSession newWriter();
    void createIsolatedTarget(String table);
}
