package com.zoutrankil.data.stock.port;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
public interface DailyTarget {
    String tableName();
    String targetId() throws Exception;
    DailyWriteSession newWriter();
}
