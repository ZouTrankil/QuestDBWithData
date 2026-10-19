package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.fund.EtfAdj;
import com.zoutrankil.data.domain.fund.EtfAdjKey;
import java.time.LocalDate;
import java.util.List;

/** Adjustment factors require a formal-date compatibility check before delivery. */
public interface EtfAdjWriteSession extends EtfWriteSession<EtfAdj, EtfAdjKey> {
    void requireCompatibleFormalDate(LocalDate date, List<EtfAdj> expected);
}
