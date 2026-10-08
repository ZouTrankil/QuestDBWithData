package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.*;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Durable publication authority and explicit original-sender investigation. */
public interface EtfMarketOverviewPublisher {
    void validateSubmission(EtfMarketOverviewCachePublicationEnvelope envelope,
                            VerifiedBatchExecutor.Submission submission) throws Exception;
    EtfMarketOverviewOwnerResult publish(EtfMarketOverviewCachePublicationEnvelope envelope,
            VerifiedBatchExecutor.Submission submission, BooleanSupplier cancelled) throws Exception;
    boolean writerStopped(Path ledgerPath, String runId) throws Exception;
    List<Path> investigateStoppedTree(Path ledgerPath, String runId) throws Exception;
}
