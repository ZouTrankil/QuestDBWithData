package com.zoutrankil.data.financial.port;

import java.util.Map;
import java.util.Set;

/** 编排队列的持久化边界；正式写入验收仍来自原 owner。 */
public interface FinancialQueueRepository {
    record Snapshot(String active, String allowance, Map<String, String> entries) {}

    @FunctionalInterface
    interface PrefixResolver {
        String resolve(String active) throws Exception;
    }

    interface Lease extends AutoCloseable {
        void close() throws Exception;
    }

    Lease acquire() throws Exception;

    void initialize() throws Exception;

    boolean exists(String key) throws Exception;

    <T> T read(String key, Class<T> type) throws Exception;

    void write(String key, Object value) throws Exception;

    Set<String> receipts(String prefix) throws Exception;

    Snapshot snapshot(PrefixResolver resolver) throws Exception;
}
