package com.zoutrankil.data.l2.port;

import com.zoutrankil.data.domain.L2EventResponseFeatures;
import com.zoutrankil.data.domain.L2EventResponseFeaturesKey;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.time.LocalDate;

/** One D088 execution session; mutable sender state must not be shared across runs. */
public interface L2EventResponseFeaturesWriteSession extends VerifiedWriteSession<L2EventResponseFeatures, L2EventResponseFeaturesKey> {
    String tableName();
    int countRows(LocalDate date);
    int rowCount();
    LocalDate readLatestTradeDate();
}
