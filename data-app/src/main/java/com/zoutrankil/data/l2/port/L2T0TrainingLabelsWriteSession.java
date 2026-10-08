package com.zoutrankil.data.l2.port;

import com.zoutrankil.data.domain.L2T0TrainingLabels;
import com.zoutrankil.data.domain.L2T0TrainingLabelsKey;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.time.LocalDate;

/** One D089 execution session; mutable sender state must not be shared across runs. */
public interface L2T0TrainingLabelsWriteSession extends VerifiedWriteSession<L2T0TrainingLabels, L2T0TrainingLabelsKey> {
    String tableName();
    int countRows(LocalDate date);
    int rowCount();
    LocalDate readLatestTradeDate();
}
