package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.fund.EtfShare;
import com.zoutrankil.data.domain.fund.EtfShareKey;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

/** 新布局按原冻结版本选择范围和会话，旧目标接口继续固定 v1。 */
public interface EtfShareTarget extends EtfTarget<EtfShare, EtfShareKey> {
    interface Versioned extends EtfShareTarget {
        int datasetVersion();

        EtfTargetRange range(int datasetVersion);

        EtfShareWriteSession newWriter(String identity, int datasetVersion);
    }

    static EtfWriteSession<EtfShare, EtfShareKey> writerForFrozenVersion(
            EtfTarget<EtfShare, EtfShareKey> target, String identity, int datasetVersion) {
        CnTradeDateLayout.fromVersion(datasetVersion);
        EtfWriteSession<EtfShare, EtfShareKey> writer;
        if (target instanceof Versioned selected) {
            writer = selected.newWriter(identity, datasetVersion);
        } else {
            if (datasetVersion != 1) throw new IllegalArgumentException("旧 ETF 份额目标只支持 v1 布局");
            writer = target.newWriter(identity);
        }
        int actual =
                writer instanceof EtfShareWriteSession.Versioned session
                        ? session.datasetVersion()
                        : 1;
        if (actual != datasetVersion) throw new IllegalStateException("ETF 份额会话没有使用原冻结布局");
        return writer;
    }
}
