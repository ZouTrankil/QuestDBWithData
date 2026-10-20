package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.stock.market.DailyBasic;
import com.zoutrankil.data.domain.stock.market.DailyBasicKey;
import com.zoutrankil.data.stock.domain.DailyBasicTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

public interface DailyBasicTarget {
    String tableName();

    String targetId() throws Exception;

    VerifiedWriteSession<DailyBasic, DailyBasicKey> newWriter();

    DailyBasicTargetRange range();
}
