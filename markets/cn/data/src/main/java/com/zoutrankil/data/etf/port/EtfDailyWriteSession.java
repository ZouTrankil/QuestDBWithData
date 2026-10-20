package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.fund.EtfDaily;
import com.zoutrankil.data.domain.fund.EtfDailyKey;

/** ETF 日线原会话固定 v1，新布局须显式声明版本。 */
public interface EtfDailyWriteSession extends EtfWriteSession<EtfDaily, EtfDailyKey> {
    interface Versioned extends EtfDailyWriteSession {
        int datasetVersion();
    }
}
