package com.zoutrankil.data.stock.port;
import com.zoutrankil.data.domain.StockDetailInfo;
import com.zoutrankil.data.stock.domain.StockDetailState.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
/** Table operations used by the application-owned two-rename publication protocol. */
public interface StockDetailTarget {
    String tableName();
    Table open(String table);
    String identify(String table,Identity identity);
    boolean exists(String table);
    void rename(String from,String to);
    StockDetailTarget publicationTables();
    Prepared prepare(Snapshot before,List<StockDetailInfo> source) throws Exception;
    StageWriter newStaging();
    interface Table { Identity preflight(); Snapshot snapshot() throws Exception; }
    interface StageWriter {
        Verified write(Prepared prepared,Path evidence,BooleanSupplier cancelled) throws Exception;
        default Verified write(Prepared prepared,Path evidence) throws Exception { return write(prepared,evidence,()->false); }
    }
}
