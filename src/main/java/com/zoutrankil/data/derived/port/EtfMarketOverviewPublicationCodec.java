package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.EtfMarketOverviewPublicationValues;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

/** Execution codec bridge to pure original-owner canonical values. */
public final class EtfMarketOverviewPublicationCodec {
    private EtfMarketOverviewPublicationCodec() {}
    public static final VerifiedBatchExecutor.Codec<EtfMarketOverviewCachePublicationEnvelope,EtfMarketOverviewDailyCacheKey> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                public EtfMarketOverviewDailyCacheKey key(EtfMarketOverviewCachePublicationEnvelope row) { return row.key(); }
                public byte[] canonicalBytes(EtfMarketOverviewCachePublicationEnvelope row) {
                    return EtfMarketOverviewPublicationValues.canonicalBytes(row);
                }
            };
    public static String fingerprint(EtfMarketOverviewCachePublicationEnvelope row) {
        return EtfMarketOverviewPublicationValues.fingerprint(row);
    }
}
