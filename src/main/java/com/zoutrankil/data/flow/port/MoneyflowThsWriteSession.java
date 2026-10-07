package com.zoutrankil.data.flow.port;

import com.zoutrankil.data.domain.MoneyflowThs;
import com.zoutrankil.data.domain.MoneyflowThsKey;
import com.zoutrankil.data.flow.domain.MoneyflowThsTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import java.time.LocalDate;
import java.util.List;

/** One bounded writer and its readback view, created for each planning or run attempt. */
public interface MoneyflowThsWriteSession extends VerifiedWriteSession<MoneyflowThs,MoneyflowThsKey>, DateSliceReadPort<MoneyflowThs> {
    MoneyflowThsTargetRange readTargetRange();
}
