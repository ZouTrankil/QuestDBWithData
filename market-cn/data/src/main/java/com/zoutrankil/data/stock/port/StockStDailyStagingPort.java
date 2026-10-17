package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.StockStDaily;
import com.zoutrankil.data.stock.domain.StockStDailyState.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public interface StockStDailyStagingPort {
    Prepared prepare(String target, String expectedPhysicalTarget, LocalDate from, LocalDate to) throws Exception;
    Verified write(Prepared prepared, List<StockStDaily> rows, String sourceFingerprint,
                   List<Map<String,Object>> sourceReceipts, Path evidence, BooleanSupplier cancelled) throws Exception;
}
