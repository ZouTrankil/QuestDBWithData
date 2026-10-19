package com.zoutrankil.data.l2.port;

/** Configured D089 target and a factory for independent physical write sessions. */
public interface L2T0TrainingLabelsTarget {
    String tableName();
    String targetId() throws Exception;
    L2T0TrainingLabelsWriteSession newWriter();
    void createIsolatedTarget(String table);
}
