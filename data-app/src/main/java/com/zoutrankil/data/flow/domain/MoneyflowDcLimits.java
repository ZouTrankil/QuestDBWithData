package com.zoutrankil.data.flow.domain;

/** Shared finite source and transport bounds for this dataset. */
public final class MoneyflowDcLimits {
    private MoneyflowDcLimits() {}
    public static final int API_ROW_CAP = 6_000;
    public static final int PAGE_SIZE = 2000;
    public static final int MAX_DAILY_ROWS = 10000;
    public static final int MAX_PAGES = 6;
    public static final int MAX_EVIDENCE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_WINDOW_DAYS = 5;
    public static final int REVISION_DAYS = 2;
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
}
