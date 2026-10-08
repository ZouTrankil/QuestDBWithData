package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.EtfAdj;
import com.zoutrankil.data.domain.EtfAdjKey;

/** Adjustment-specific target preserves its compatibility check in every fresh session. */
public interface EtfAdjTarget extends EtfTarget<EtfAdj, EtfAdjKey> {
    @Override EtfAdjWriteSession newWriter(String frozenTargetId);
}
