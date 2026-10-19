package com.zoutrankil.batch.l2;

/** Existing CLI entry point for offline native computation. */
public final class L2DailyFeatureCli {
    private L2DailyFeatureCli() {
    }

    public static void main(String[] args) throws Exception {
        new L2DailyFeatureSingleService().execute(L2DailyFeatureSingleRequest.parse(args));
    }
}
