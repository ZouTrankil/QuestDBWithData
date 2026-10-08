package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.*;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/** One execution's publication binding and no-resend state. */
public interface EtfMarketOverviewPublicationSession extends VerifiedBatchExecutor.Port<
        EtfMarketOverviewCachePublicationEnvelope, EtfMarketOverviewDailyCacheKey> {
    void bind(EtfMarketOverviewCachePublicationEnvelope envelope);
    void cancellationProbe(BooleanSupplier probe);
    void reconciliationContext(Path ledgerPath, String runId);
    boolean unresolved();
    Duration visibilityTimeout();
    EtfMarketOverviewCachePublicationEnvelope preview(LocalDate date) throws Exception;
    EtfMarketOverviewCachePublicationEnvelope requireExactEnvelope(EtfMarketOverviewDailyCache row) throws Exception;
}
