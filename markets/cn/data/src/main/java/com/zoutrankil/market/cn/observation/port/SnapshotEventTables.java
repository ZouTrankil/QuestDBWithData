package com.zoutrankil.market.cn.observation.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** 所有快照包含完整多重记录和稳定 WAL 版本；发布器负责耐久意图及重命名。 */
public interface SnapshotEventTables {
    record Identity(long id, String directory, String targetId, int schemaVersion) {}

    record Snapshot(
            Identity identity, List<Map<String, Object>> rows, String fingerprint, long writerTxn) {
        public Snapshot {
            rows = List.copyOf(rows);
        }
    }

    void requireNoNativeDependencies() throws Exception;

    Snapshot snapshot(String table) throws Exception;

    Snapshot snapshotIfPresent(String table) throws Exception;

    Snapshot stage(
            String table, List<Map<String, Object>> rows, Path evidence, BooleanSupplier cancelled)
            throws Exception;

    void rename(String from, String to) throws Exception;
}
