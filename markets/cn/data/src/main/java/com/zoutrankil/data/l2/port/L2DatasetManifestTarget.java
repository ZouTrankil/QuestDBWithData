package com.zoutrankil.data.l2.port;

/** Physical identity and fresh bounded writers for the configured D085 target. */
public interface L2DatasetManifestTarget {
    String tableName();
    String targetId() throws Exception;
    L2DatasetManifestWriteSession newWriter();
    void createIsolatedTarget(String table);
}
