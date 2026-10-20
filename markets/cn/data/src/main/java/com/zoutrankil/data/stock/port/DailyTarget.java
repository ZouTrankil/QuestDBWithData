package com.zoutrankil.data.stock.port;

public interface DailyTarget {
    String tableName();

    String targetId() throws Exception;

    DailyWriteSession newWriter();
}
