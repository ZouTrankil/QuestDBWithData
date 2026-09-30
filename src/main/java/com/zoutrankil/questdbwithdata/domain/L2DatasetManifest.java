package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** One L2 batch manifest row. trade_date_ts is a UTC-midnight storage carrier for trade_date. */
public record L2DatasetManifest(
        LocalDate tradeDate,
        String symbol,
        String market,
        String board,
        String sourceRoot,
        String outputRoot,
        String featureVersion,
        Boolean dailyFeatureOk,
        Boolean t0Ok,
        String rawRowCounts,
        String outputPaths,
        String costConfig,
        String horizonsMin,
        String errors,
        long batchId,
        LocalDate tradeDateTs) {

    public L2DatasetManifest {
        Objects.requireNonNull(tradeDate, "trade_date required");
        Objects.requireNonNull(tradeDateTs, "trade_date_ts required");
        if (!tradeDate.equals(tradeDateTs))
            throw new IllegalArgumentException("trade_date_ts must be derived from trade_date");
        new L2DatasetManifestKey(tradeDate, symbol, batchId);
        bounded(sourceRoot, 4096, "source_root");
        bounded(outputRoot, 4096, "output_root");
        bounded(featureVersion, 128, "feature_version");
        bounded(rawRowCounts, 65536, "raw_row_counts");
        bounded(outputPaths, 65536, "output_paths");
        bounded(costConfig, 65536, "cost_config");
        bounded(horizonsMin, 65536, "horizons_min");
        bounded(errors, 65536, "errors");
    }

    private static void bounded(String value, int max, String name) {
        if (value != null && value.length() > max)
            throw new IllegalArgumentException(name + " exceeds the frozen field bound");
    }

    public L2DatasetManifestKey key() {
        return new L2DatasetManifestKey(tradeDate, symbol, batchId);
    }
}
