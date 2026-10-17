package com.zoutrankil.batch.l2;

/** Existing CLI entry point for offline native computation. */
public final class L2DailyFeatureBatchCli {
    private L2DailyFeatureBatchCli() {
    }

    public static void main(String[] args) throws Exception {
        new L2DailyFeatureBatchService().execute(L2DailyFeatureBatchRequest.parse(args));
    }
}
