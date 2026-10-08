package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.StockStDaily;
import com.zoutrankil.data.domain.StockStDailyKey;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.data.stock.domain.StockStDailyState.Content;
import java.time.LocalDate;
import java.util.List;

public interface StockStDailyTarget {
    String tableName();
    String targetId();
    String physicalTargetId();
    StockTargetRange range();
    StockDateWriteSession<StockStDaily, StockStDailyKey> newWriter(String physicalTargetId);
    StockDateWriteSession<StockStDaily, StockStDailyKey> stageWriter(String stageTable, String physicalTargetId);
    StockStDailyTables newPublicationTables();
    StockStDailyStagingPort newStaging();
    StockStDailyTables.Table openTable();
    Content fingerprintWindow(List<StockStDaily> rows, LocalDate from, LocalDate to) throws Exception;
}
