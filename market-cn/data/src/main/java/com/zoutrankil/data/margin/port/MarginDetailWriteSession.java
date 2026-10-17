package com.zoutrankil.data.margin.port;

import com.zoutrankil.data.domain.MarginDetail;
import com.zoutrankil.data.domain.MarginDetailKey;
import com.zoutrankil.data.margin.domain.MarginDetailTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import java.time.LocalDate;
import java.util.List;

/** One bounded writer and its readback view, created for each planning or run attempt. */
public interface MarginDetailWriteSession extends VerifiedWriteSession<MarginDetail,MarginDetailKey>, DateSliceReadPort<MarginDetail> {
    MarginDetailTargetRange readTargetRange();
    List<MarginDetail> readRange(LocalDate from, LocalDate to);
}
