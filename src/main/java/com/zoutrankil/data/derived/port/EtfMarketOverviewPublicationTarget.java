package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.*;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Exact private endpoint and bounded cache/coverage readback. */
public interface EtfMarketOverviewPublicationTarget {
    void preflight(EtfMarketOverviewCachePublicationEnvelope expected) throws Exception;
    EtfMarketOverviewObservedPublication readback(EtfMarketOverviewCachePublicationEnvelope expected,
            EtfMarketOverviewDailyCacheKey key) throws Exception;
    boolean walSettled(EtfMarketOverviewCachePublicationEnvelope expected) throws Exception;
}
