package com.zoutrankil.data.etf.application;

import java.nio.file.Path;

/** Test-only bridge for the existing cross-family ledger characterization. */
public final class EtfCoverageTestAccess {
    private EtfCoverageTestAccess() {}

    public static boolean factorHasHistorySchema(Path path) throws Exception {
        return EtfFactorCoverage.hasHistorySchema(path);
    }
}
