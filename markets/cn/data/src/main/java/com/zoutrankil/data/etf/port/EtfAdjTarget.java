package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.fund.EtfAdj;
import com.zoutrankil.data.domain.fund.EtfAdjKey;

/** Adjustment-specific target preserves its compatibility check in every fresh session. */
public interface EtfAdjTarget extends EtfTarget<EtfAdj, EtfAdjKey> {
    @Override
    EtfAdjWriteSession newWriter(String frozenTargetId);
}
