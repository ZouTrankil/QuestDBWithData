package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.*;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Creates a fresh application session without external IO. */
@FunctionalInterface
public interface EtfMarketOverviewSessions {
    EtfMarketOverviewPublicationSession open(EtfMarketOverviewCachePublicationEnvelope initial);
}
