package com.zoutrankil.data.margin.domain;

/** Shared finite source and transport bounds for this dataset. */
public final class MarginDetailLimits {
    private MarginDetailLimits() {}
    public static final int API_ROW_CAP = 6_000;
    public static final int MAX_EVIDENCE_BYTES = 32 * 1024 * 1024;
    public static final int MAX_WINDOW_DAYS = 14;
    public static final int REVISION_DAYS = 7;
    public static final int MAX_SOURCE_SLICES = MAX_WINDOW_DAYS;
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
}
