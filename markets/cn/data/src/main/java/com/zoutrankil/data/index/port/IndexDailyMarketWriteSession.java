package com.zoutrankil.data.index.port;

import com.zoutrankil.data.domain.IndexDailyMarket;
import com.zoutrankil.data.domain.IndexDailyMarketKey;
import com.zoutrankil.data.index.domain.IndexDailyMarketTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.time.LocalDate;
import java.util.List;

/** One frozen physical generation and one execution attempt's writer state. */
public interface IndexDailyMarketWriteSession extends VerifiedWriteSession<IndexDailyMarket,IndexDailyMarketKey> {
    IndexDailyMarketTargetRange readExistingRange(String code);
    List<IndexDailyMarket> readExistingRows(String code);
    List<IndexDailyMarket> readRange(String code, LocalDate from, LocalDate to);
}
