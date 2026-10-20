package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.fund.EtfShare;
import com.zoutrankil.data.domain.fund.EtfShareKey;

/** ETF 份额原会话固定 v1，新布局须显式声明版本。 */
public interface EtfShareWriteSession extends EtfWriteSession<EtfShare, EtfShareKey> {
    interface Versioned extends EtfShareWriteSession {
        int datasetVersion();
    }
}
