package com.zoutrankil.data.l2.port;

import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.l2.domain.L2DailyFeaturesRows;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** A fresh bounded D086 writer and its full-key encoding/readback contract. */
public interface L2DailyFeaturesWriteSession
        extends VerifiedWriteSession<L2DailyFeatures, L2DailyFeaturesKey> {
    VerifiedBatchExecutor.Codec<L2DailyFeatures, L2DailyFeaturesKey> CODEC =
            codec(CnTradeDateLayout.LEGACY_V1);

    interface Versioned extends L2DailyFeaturesWriteSession {
        int datasetVersion();
    }

    static VerifiedBatchExecutor.Codec<L2DailyFeatures, L2DailyFeaturesKey> codec(
            CnTradeDateLayout layout) {
        Objects.requireNonNull(layout);
        return new VerifiedBatchExecutor.Codec<>() {
            @Override
            public L2DailyFeaturesKey key(L2DailyFeatures row) {
                return L2DailyFeaturesRows.key(row);
            }

            @Override
            public byte[] canonicalBytes(L2DailyFeatures row) {
                return L2DailyFeaturesRows.canonicalBytes(row, layout);
            }

            @Override
            public int estimatedTransportBytes(L2DailyFeatures row, byte[] canonical) {
                return L2DailyFeaturesRows.estimatedTransportBytes(row, canonical);
            }
        };
    }

    String tableName();

    int countRows(LocalDate date);

    /** 全量键值回读后，再核对同一证券范围的数量，不能单独用计数证明完成。 */
    default int countRows(LocalDate date, List<String> symbols) {
        if (!symbols.isEmpty()) throw new IllegalArgumentException("旧 L2 会话未提供证券范围计数");
        return countRows(date);
    }

    /** 完整回读前后固定同一已应用 WAL 版本；未知版本不能冒充稳定。 */
    default String stableVersion() {
        throw new IllegalStateException("旧 L2 会话未提供稳定 WAL 版本");
    }

    List<LocalDate> readExistingDates();
}
