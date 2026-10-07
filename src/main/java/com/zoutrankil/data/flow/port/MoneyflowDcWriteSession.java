package com.zoutrankil.data.flow.port;

import com.zoutrankil.data.domain.MoneyflowDc;
import com.zoutrankil.data.domain.MoneyflowDcKey;
import com.zoutrankil.data.flow.domain.MoneyflowDcTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import java.time.LocalDate;
import java.util.List;

/** One bounded writer and its readback view, created for each planning or run attempt. */
public interface MoneyflowDcWriteSession extends VerifiedWriteSession<MoneyflowDc,MoneyflowDcKey>, DateSliceReadPort<MoneyflowDc> {
    MoneyflowDcTargetRange readTargetRange();
    List<MoneyflowDc> readRange(LocalDate from, LocalDate to);
}
