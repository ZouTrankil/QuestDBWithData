package com.zoutrankil.data.flow.port;

import com.zoutrankil.data.domain.Moneyflow;
import com.zoutrankil.data.domain.MoneyflowKey;
import com.zoutrankil.data.flow.domain.MoneyflowTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import java.time.LocalDate;
import java.util.List;

/** One bounded writer and its readback view, created for each planning or run attempt. */
public interface MoneyflowWriteSession extends VerifiedWriteSession<Moneyflow,MoneyflowKey>, DateSliceReadPort<Moneyflow> {
    MoneyflowTargetRange readTargetRange();
    List<Moneyflow> readRange(LocalDate from, LocalDate to);
}
