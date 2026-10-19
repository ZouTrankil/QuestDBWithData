package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.etf.domain.EtfTargetRange;

/** Application-owned target operations without database connection or client types. */
public interface EtfTarget<T, K> extends EtfWriteTarget<T, K> {
    EtfTargetRange range();
    @Override EtfWriteSession<T, K> newWriter(String frozenTargetId);
}
