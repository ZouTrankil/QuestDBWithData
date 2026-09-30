package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete source identity; batch_id distinguishes reprocessed manifests for one stock and date. */
public record L2DatasetManifestKey(LocalDate tradeDate, String symbol, long batchId) {
    public L2DatasetManifestKey {
        Objects.requireNonNull(tradeDate, "trade date required");
        if (symbol == null || !symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
            throw new IllegalArgumentException("Canonical mainland stock symbol required");
        if (batchId < 0) throw new IllegalArgumentException("Non-negative manifest batch required");
    }
}
