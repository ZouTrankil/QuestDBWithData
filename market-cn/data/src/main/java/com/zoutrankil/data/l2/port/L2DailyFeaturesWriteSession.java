package com.zoutrankil.data.l2.port;

import com.zoutrankil.data.domain.L2DailyFeatures;
import com.zoutrankil.data.domain.L2DailyFeaturesKey;
import com.zoutrankil.data.l2.domain.L2DailyFeaturesRows;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.time.LocalDate;
import java.util.List;

/** A fresh bounded D086 writer and its full-key encoding/readback contract. */
public interface L2DailyFeaturesWriteSession extends VerifiedWriteSession<L2DailyFeatures,L2DailyFeaturesKey> {
    VerifiedBatchExecutor.Codec<L2DailyFeatures,L2DailyFeaturesKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public L2DailyFeaturesKey key(L2DailyFeatures row) { return L2DailyFeaturesRows.key(row); }
        @Override public byte[] canonicalBytes(L2DailyFeatures row) { return L2DailyFeaturesRows.canonicalBytes(row); }
        @Override public int estimatedTransportBytes(L2DailyFeatures row,byte[] canonical) { return L2DailyFeaturesRows.estimatedTransportBytes(row,canonical); }
    };
    String tableName();
    int countRows(LocalDate date);
    List<LocalDate> readExistingDates();
}
