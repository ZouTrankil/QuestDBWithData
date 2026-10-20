package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.fund.EtfDaily;
import com.zoutrankil.data.domain.fund.EtfDailyKey;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

/** 新布局按原冻结版本选择范围和会话，旧目标接口继续固定 v1。 */
public interface EtfDailyTarget extends EtfTarget<EtfDaily, EtfDailyKey> {
    interface Versioned extends EtfDailyTarget {
        int datasetVersion();

        EtfTargetRange range(int datasetVersion);

        EtfDailyWriteSession newWriter(String identity, int datasetVersion);
    }

    static EtfWriteSession<EtfDaily, EtfDailyKey> writerForFrozenVersion(
            EtfTarget<EtfDaily, EtfDailyKey> target, String identity, int datasetVersion) {
        CnTradeDateLayout.fromVersion(datasetVersion);
        EtfWriteSession<EtfDaily, EtfDailyKey> writer;
        if (target instanceof Versioned selected) {
            writer = selected.newWriter(identity, datasetVersion);
        } else {
            if (datasetVersion != 1) throw new IllegalArgumentException("旧 ETF 日线目标只支持 v1 布局");
            writer = target.newWriter(identity);
        }
        int actual =
                writer instanceof EtfDailyWriteSession.Versioned session
                        ? session.datasetVersion()
                        : 1;
        if (actual != datasetVersion) throw new IllegalStateException("ETF 日线会话没有使用原冻结布局");
        return writer;
    }
}
