package com.zoutrankil.questdbwithdata.client.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact Parquet source row. trade_date_ts is deliberately absent because Java derives it from trade_date. */
public record L2DatasetManifestParquetDto(
        String tradeDate,
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
        Long batchId) {

    private static final List<String> FIELDS = List.of("trade_date", "symbol", "market", "board",
            "source_root", "output_root", "feature_version", "daily_feature_ok", "t0_ok",
            "raw_row_counts", "output_paths", "cost_config", "horizons_min", "errors", "batch_id");

    public L2DatasetManifestParquetDto {
        Objects.requireNonNull(tradeDate, "trade_date required");
        Objects.requireNonNull(symbol, "symbol required");
        Objects.requireNonNull(batchId, "batch_id required");
    }

    public static L2DatasetManifestParquetDto decode(JsonNode row) {
        if (row == null || !row.isObject()) throw new IllegalArgumentException("Parquet source row must be a JSON object");
        var actual = new HashSet<String>();
        row.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(new HashSet<>(FIELDS)))
            throw new IllegalArgumentException("Parquet source columns differ from the frozen D085 schema");
        return new L2DatasetManifestParquetDto(
                text(row, "trade_date", true), text(row, "symbol", true), text(row, "market", false),
                text(row, "board", false), text(row, "source_root", false), text(row, "output_root", false),
                text(row, "feature_version", false), bool(row, "daily_feature_ok"), bool(row, "t0_ok"),
                text(row, "raw_row_counts", false), text(row, "output_paths", false),
                text(row, "cost_config", false), text(row, "horizons_min", false),
                text(row, "errors", false), integer(row, "batch_id"));
    }

    private static String text(JsonNode row, String field, boolean required) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) {
            if (required) throw new IllegalArgumentException("Required Parquet field is null: " + field);
            return null;
        }
        if (!value.isTextual()) throw new IllegalArgumentException("Parquet field must be text: " + field);
        return value.textValue();
    }

    private static Boolean bool(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isBoolean()) throw new IllegalArgumentException("Parquet field must be boolean: " + field);
        return value.booleanValue();
    }

    private static Long integer(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalArgumentException("Parquet field must be an integer: " + field);
        return value.longValue();
    }
}
