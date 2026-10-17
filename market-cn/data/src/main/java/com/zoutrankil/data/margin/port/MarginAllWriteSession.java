package com.zoutrankil.data.margin.port;
import com.zoutrankil.data.domain.MarginAll;
import com.zoutrankil.data.domain.MarginAllKey;
import com.zoutrankil.data.margin.domain.MarginAllState.TargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import java.time.LocalDate;
import java.util.List;
public interface MarginAllWriteSession extends VerifiedWriteSession<MarginAll,MarginAllKey>,DateSliceReadPort<MarginAll> {
    String formalTable();
    String stageTable();
    void useStage(String stage,String stageId);
    TargetRange readTargetRange();
    List<MarginAll> readWindow(String table,LocalDate from,LocalDate to);
}
