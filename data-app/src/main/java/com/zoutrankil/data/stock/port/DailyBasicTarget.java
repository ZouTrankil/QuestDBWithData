package com.zoutrankil.data.stock.port;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
public interface DailyBasicTarget {
    String tableName();
    String targetId() throws Exception;
    VerifiedWriteSession<DailyBasic, DailyBasicKey> newWriter();
    com.zoutrankil.data.stock.domain.DailyBasicTargetRange range();
}
