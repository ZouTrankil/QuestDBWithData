package com.zoutrankil.data.margin.port;
import com.zoutrankil.data.margin.domain.MarginZrzState.*;
import java.time.LocalDate;
/** Physical operations used by the application-owned publication protocol. */
public interface MarginZrzTables {
    Table open(String table);
    String logicalTargetId(String table);
    String physicalTargetId(String table,Identity identity);
    int tableCount(String table);
    void rename(String source,String target);
    DiscardSession newDiscardSession();
    interface Table {
        Identity preflight();
        Snapshot snapshot() throws Exception;
        Snapshot outside(LocalDate from,LocalDate to) throws Exception;
        Snapshot window(LocalDate from,LocalDate to) throws Exception;
    }
    interface DiscardSession {
        int namedStageCount(String stage);
        void dropStage(String stage);
        boolean stageExists(String stage);
    }
}
