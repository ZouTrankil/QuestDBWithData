package com.zoutrankil.data.margin.port;
import com.zoutrankil.data.domain.MarginZrz;
import com.zoutrankil.data.domain.MarginZrzKey;
import com.zoutrankil.data.margin.domain.MarginZrzState.TargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import java.time.LocalDate;
import java.util.List;
public interface MarginZrzWriteSession extends VerifiedWriteSession<MarginZrz,MarginZrzKey>,DateSliceReadPort<MarginZrz> {
    String formalTable();
    String stageTable();
    void useStage(String stage,String stageId);
    TargetRange readTargetRange();
    List<MarginZrz> readWindow(String table,LocalDate from,LocalDate to);
}
