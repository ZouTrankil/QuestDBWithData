package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.stock.domain.StockStDailyState.Content;
import com.zoutrankil.data.stock.domain.StockStDailyState.Identity;
import com.zoutrankil.data.stock.domain.StockStDailyState.Snapshot;

import java.time.LocalDate;

/** Physical table operations; publication state transitions remain in the application. */
public interface StockStDailyTables {
    interface Versioned extends StockStDailyTables {
        int datasetVersion();
    }

    interface Table {
        Snapshot snapshot() throws Exception;

        Content window(LocalDate from, LocalDate to) throws Exception;

        Content outside(LocalDate from, LocalDate to) throws Exception;
    }

    Snapshot snapshot(String table) throws Exception;

    Snapshot snapshotIfPresent(String table) throws Exception;

    String targetId(String table, Identity identity);

    void rename(String from, String to);
}
