package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.sync.port.VerifiedWriteSession;

/** Physical ETF target identity and fresh writers, including whole-directory snapshots. */
public interface EtfWriteTarget<T, K> {
    String tableName();
    String targetId();
    VerifiedWriteSession<T, K> newWriter(String frozenTargetId);
}
