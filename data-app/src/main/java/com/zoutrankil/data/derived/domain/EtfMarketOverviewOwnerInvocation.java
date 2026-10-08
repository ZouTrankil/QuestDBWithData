package com.zoutrankil.data.derived.domain;

import java.nio.file.Path;

/** Immutable artifact paths for one original-owner process invocation. */
public record EtfMarketOverviewOwnerInvocation(Path request, Path response, Path intent,
        Path started, Path stopped, Path log, String id) {}
