package com.zoutrankil.data.flow.port;

import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.Identity;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.Snapshot;
import java.time.LocalDate;

/** Physical generations and snapshots; publication decisions belong to the application. */
public interface MoneyflowHsgtTables {
    interface Table {
        Identity preflight();
        Snapshot snapshot() throws Exception;
        Snapshot outside(LocalDate from, LocalDate to) throws Exception;
        Snapshot window(LocalDate from, LocalDate to) throws Exception;
    }
    Table open(String table);
    default Table open(String table, String owningTarget) {
        if (!table.equals(owningTarget))
            throw new UnsupportedOperationException("This table adapter cannot validate an owner's publication layout");
        return open(table);
    }
    String logicalTargetId(String table);
    String physicalTargetId(String table, Identity identity);
    int tableCount(String table);
    void rename(String source, String target);
}
