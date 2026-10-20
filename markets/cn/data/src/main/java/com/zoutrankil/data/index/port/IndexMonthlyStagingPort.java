package com.zoutrankil.data.index.port;

import java.util.function.BooleanSupplier;

public interface IndexMonthlyStagingPort extends IndexMonthlyTables {
    int tableCount(String table);

    void createOutsideStage(String stage, String target, String code, String lower, String end);

    void awaitWal(String table, BooleanSupplier cancelled) throws Exception;
}
