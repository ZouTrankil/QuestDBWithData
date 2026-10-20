package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.stock.domain.StockSuspendState.Identity;
import com.zoutrankil.data.stock.domain.StockSuspendState.Prepared;
import com.zoutrankil.data.stock.domain.StockSuspendState.Range;
import com.zoutrankil.data.stock.domain.StockSuspendState.Snapshot;

import java.time.LocalDate;
import java.util.List;

/** The admitted logical suspension target and factories for isolated per-run state. */
public interface StockSuspendTarget {
    String tableName();

    String targetId();

    String physicalTargetId();

    Range range();

    StockSuspendWriteSession newWriter(String frozenPhysicalTargetId);

    StockSuspendTables.Table openTable(String table);

    String physicalTargetId(String table, Identity identity);

    Prepared prepare(
            Snapshot before,
            Snapshot current,
            List<StockSuspend> source,
            LocalDate from,
            LocalDate to)
            throws Exception;

    StockSuspendStagingPort newStaging();

    StockSuspendTables newPublicationTables();
}
