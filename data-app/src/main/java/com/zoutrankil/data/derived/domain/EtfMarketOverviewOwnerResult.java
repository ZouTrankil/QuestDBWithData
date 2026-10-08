package com.zoutrankil.data.derived.domain;

import java.nio.file.Path;

/** Exact original-owner publication outcome, after protocol validation. */
public record EtfMarketOverviewOwnerResult(Path requestPath, Path responsePath, Path intentPath,
        String responseSha256, boolean processStopped, long hits, long misses, String status) {}
