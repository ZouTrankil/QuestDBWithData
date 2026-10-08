package com.zoutrankil.data.margin.domain;
/** Shared source and physical snapshot budgets. */
public final class MarginSecsLimits {
 private MarginSecsLimits() {}
 public static final int API_ROW_CAP=6000, MAX_EVIDENCE_BYTES=16*1024*1024;
 public static final int MAX_ROWS=1_000_000, MAX_BYTES=256*1024*1024;
}
