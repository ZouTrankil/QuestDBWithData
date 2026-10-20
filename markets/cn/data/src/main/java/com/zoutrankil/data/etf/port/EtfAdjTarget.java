package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.fund.EtfAdj;
import com.zoutrankil.data.domain.fund.EtfAdjKey;

/** Adjustment-specific target preserves its compatibility check in every fresh session. */
public interface EtfAdjTarget extends EtfTarget<EtfAdj, EtfAdjKey> {
    default int datasetVersion() {
        return 1;
    }

    /** 冻结版本必须显式传给会话；旧目标不能默默接受新版本。 */
    default EtfAdjWriteSession newWriter(String frozenTargetId, int datasetVersion) {
        if (datasetVersion != datasetVersion())
            throw new IllegalArgumentException("ETF 复权目标与请求的布局版本不一致");
        return newWriter(frozenTargetId);
    }

    @Override
    EtfAdjWriteSession newWriter(String frozenTargetId);
}
