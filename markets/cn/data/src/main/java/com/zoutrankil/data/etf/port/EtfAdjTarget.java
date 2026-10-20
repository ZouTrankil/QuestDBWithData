package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.fund.EtfAdj;
import com.zoutrankil.data.domain.fund.EtfAdjKey;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

/** Adjustment-specific target preserves its compatibility check in every fresh session. */
public interface EtfAdjTarget extends EtfTarget<EtfAdj, EtfAdjKey> {
    /** 原目标接口固定 v1；支持按冻结版本恢复的目标显式声明此能力。 */
    interface Versioned extends EtfAdjTarget {
        @Override
        int datasetVersion();

        @Override
        EtfAdjWriteSession newWriter(String frozenTargetId, int datasetVersion);

        EtfTargetRange range(int datasetVersion);
    }

    static EtfWriteSession<EtfAdj, EtfAdjKey> writerForFrozenVersion(
            EtfTarget<EtfAdj, EtfAdjKey> target, String identity, int datasetVersion) {
        CnTradeDateLayout.fromVersion(datasetVersion);
        EtfWriteSession<EtfAdj, EtfAdjKey> writer;
        if (target instanceof Versioned versioned) {
            writer = versioned.newWriter(identity, datasetVersion);
        } else {
            if (datasetVersion != 1) throw new IllegalArgumentException("旧 ETF 复权目标只支持 v1 布局");
            writer = target.newWriter(identity);
        }
        int actual =
                writer instanceof EtfAdjWriteSession.Versioned session
                        ? session.datasetVersion()
                        : 1;
        if (actual != datasetVersion) throw new IllegalStateException("ETF 复权会话没有使用原冻结布局");
        return writer;
    }

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
