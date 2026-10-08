package com.zoutrankil.data.stock.port;
import com.zoutrankil.data.domain.StockSuspend;
import com.zoutrankil.data.stock.domain.StockSuspendState.*;
import java.time.LocalDate;
import java.util.List;
/** Physical operations used by the application-owned D011 publication state machine. */
public interface StockSuspendTables {
    interface Table {
        Identity preflight();
        Snapshot snapshot() throws Exception;
    }
    Table openTable(String table);
    String logicalTargetId(String table);
    String physicalTargetId(String table,Identity identity);
    Prepared prepare(Snapshot before,Snapshot current,List<StockSuspend> source,LocalDate from,LocalDate to) throws Exception;
    Snapshot snapshotIfPresent(String table) throws Exception;
    void rename(String from,String to);
}
