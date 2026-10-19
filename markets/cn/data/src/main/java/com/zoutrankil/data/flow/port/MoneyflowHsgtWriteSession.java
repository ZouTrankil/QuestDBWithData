package com.zoutrankil.data.flow.port;

import com.zoutrankil.data.domain.MoneyflowHsgt;
import com.zoutrankil.data.domain.MoneyflowHsgtKey;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.TargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.time.LocalDate;
import java.util.List;

/** A single run's mutable replacement-stage binding and stopped-writer proof. */
public interface MoneyflowHsgtWriteSession extends VerifiedWriteSession<MoneyflowHsgt, MoneyflowHsgtKey> {
    String formalTable();
    String stageTable();
    void useStage(String stage, String stageId);
    List<MoneyflowHsgt> readWindow(String table, LocalDate from, LocalDate to);
    TargetRange readTargetRange();
}
