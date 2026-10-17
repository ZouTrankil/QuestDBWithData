package com.zoutrankil.data.l2.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.domain.L2IntradayBarFeaturesRows;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.time.LocalDate;

public interface L2IntradayBarFeaturesWriteSession extends VerifiedWriteSession<L2IntradayBarFeatures,L2IntradayBarFeaturesKey> {
    public static final VerifiedBatchExecutor.Codec<L2IntradayBarFeatures, L2IntradayBarFeaturesKey> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                @Override public L2IntradayBarFeaturesKey key(L2IntradayBarFeatures row) { return L2IntradayBarFeaturesRows.key(row); }
                @Override public byte[] canonicalBytes(L2IntradayBarFeatures row) {
                    return L2IntradayBarFeaturesRows.canonicalBytes(row);
                }
                @Override public int estimatedTransportBytes(L2IntradayBarFeatures row, byte[] canonical) {
                    return L2IntradayBarFeaturesRows.estimatedTransportBytes(row, canonical);
                }
            };
    @Override default VerifiedBatchExecutor.Codec<L2IntradayBarFeatures,L2IntradayBarFeaturesKey> codec(){return CODEC;}
    String tableName();
    int countRows(LocalDate date);
    int rowCount();
    LocalDate readLatestTradeDate();
}
