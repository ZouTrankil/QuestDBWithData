package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.fund.EtfAdj;
import com.zoutrankil.data.domain.fund.EtfAdjKey;

import java.time.LocalDate;
import java.util.List;

/** Adjustment factors require a formal-date compatibility check before delivery. */
public interface EtfAdjWriteSession extends EtfWriteSession<EtfAdj, EtfAdjKey> {
    /** 原接口代表固定 v1；可分版的会话须显式实现此能力，不能探测字段猜版本。 */
    interface Versioned extends EtfAdjWriteSession {
        int datasetVersion();
    }

    void requireCompatibleFormalDate(LocalDate date, List<EtfAdj> expected);
}
