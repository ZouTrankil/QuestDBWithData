package com.zoutrankil.data.flow.domain;
/** Shared bounded source and snapshot contract; no execution state. */
public final class MoneyflowHsgtLimits {
 private MoneyflowHsgtLimits() {}
 public static final int API_ROW_CAP=300, MAX_RANGE_DAYS=31, MAX_EVIDENCE_BYTES=2*1024*1024;
 public static final int MAX_ROWS=100_000, MAX_BYTES=64*1024*1024;
}
