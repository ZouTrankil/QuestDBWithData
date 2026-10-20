package com.zoutrankil.data.flow.port;

import com.zoutrankil.data.domain.stock.moneyflow.MoneyflowThs;
import com.zoutrankil.data.domain.stock.moneyflow.MoneyflowThsKey;
import com.zoutrankil.data.flow.domain.MoneyflowThsTargetRange;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

/** One bounded writer and its readback view, created for each planning or run attempt. */
public interface MoneyflowThsWriteSession
        extends VerifiedWriteSession<MoneyflowThs, MoneyflowThsKey>,
                DateSliceReadPort<MoneyflowThs> {
    MoneyflowThsTargetRange readTargetRange();
}
