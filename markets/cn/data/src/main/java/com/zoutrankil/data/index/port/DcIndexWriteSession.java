package com.zoutrankil.data.index.port;

import com.zoutrankil.data.domain.stock.concept.DcIndex;
import com.zoutrankil.data.domain.stock.concept.DcIndexKey;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

public interface DcIndexWriteSession
        extends VerifiedWriteSession<DcIndex, DcIndexKey>, DateSliceReadPort<DcIndex> {
    DcIndexWriteSession forTarget(String stage, String physicalId);
}
