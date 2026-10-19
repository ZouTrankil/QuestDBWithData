package com.zoutrankil.data.index.port;

import com.zoutrankil.data.domain.IndexWeight;
import com.zoutrankil.data.domain.IndexWeightKey;
import com.zoutrankil.data.index.domain.IndexWeightTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.time.LocalDate;
import java.util.List;

/** One frozen physical generation and one execution attempt's writer state. */
public interface IndexWeightWriteSession extends VerifiedWriteSession<IndexWeight,IndexWeightKey> {
    IndexWeightTargetRange readExistingRange();
    List<IndexWeight> readSnapshot(String indexCode, LocalDate tradeDate);
    List<IndexWeight> readRange(String code, LocalDate from, LocalDate to);
}
