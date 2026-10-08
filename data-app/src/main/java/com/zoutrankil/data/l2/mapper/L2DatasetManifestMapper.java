package com.zoutrankil.data.l2.mapper;

import com.zoutrankil.data.client.dto.L2DatasetManifestParquetDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.L2DatasetManifest;
import com.zoutrankil.data.domain.L2DatasetManifestDataset;
import com.zoutrankil.data.domain.table.L2DatasetManifestRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;

/** Explicit Parquet DTO -> business model -> QuestDB projection mapping for D085. */
public final class L2DatasetManifestMapper {
    public L2DatasetManifest fromParquet(JsonNode json) {
        return fromSource(L2DatasetManifestParquetDto.decode(json));
    }

    public L2DatasetManifest fromSource(L2DatasetManifestParquetDto row) {
        LocalDate day = TemporalValues.businessDate(row.tradeDate(), TemporalValues.DateFormat.BASIC);
        return new L2DatasetManifest(day, row.symbol(), row.market(), row.board(), row.sourceRoot(),
                row.outputRoot(), row.featureVersion(), row.dailyFeatureOk(), row.t0Ok(),
                row.rawRowCounts(), row.outputPaths(), row.costConfig(), row.horizonsMin(),
                row.errors(), row.batchId(), day);
    }

    public L2DatasetManifest fromStorage(L2DatasetManifestRow row) {
        LocalDate tradeDate = TemporalValues.businessDate(row.tradeDate(), TemporalValues.DateFormat.BASIC);
        LocalDate timestampDate = TemporalValues.CalendarTimestamp.fromStorage(row.tradeDateTs()).date();
        return new L2DatasetManifest(tradeDate, row.symbol(), row.market(), row.board(), row.sourceRoot(),
                row.outputRoot(), row.featureVersion(), row.dailyFeatureOk(), row.t0Ok(),
                row.rawRowCounts(), row.outputPaths(), row.costConfig(), row.horizonsMin(),
                row.errors(), row.batchId(), timestampDate);
    }

    public L2DatasetManifestRow toStorage(L2DatasetManifest row) {
        return new L2DatasetManifestRow(TemporalValues.formatDate(row.tradeDate(), TemporalValues.DateFormat.BASIC),
                row.symbol(), row.market(), row.board(), row.sourceRoot(), row.outputRoot(),
                row.featureVersion(), row.dailyFeatureOk(), row.t0Ok(), row.rawRowCounts(),
                row.outputPaths(), row.costConfig(), row.horizonsMin(), row.errors(), row.batchId(),
                new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());
    }

    public DatasetValues values(L2DatasetManifest row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate());
        values.put("symbol", row.symbol());
        values.put("market", row.market());
        values.put("board", row.board());
        values.put("source_root", row.sourceRoot());
        values.put("output_root", row.outputRoot());
        values.put("feature_version", row.featureVersion());
        values.put("daily_feature_ok", row.dailyFeatureOk());
        values.put("t0_ok", row.t0Ok());
        values.put("raw_row_counts", row.rawRowCounts());
        values.put("output_paths", row.outputPaths());
        values.put("cost_config", row.costConfig());
        values.put("horizons_min", row.horizonsMin());
        values.put("errors", row.errors());
        values.put("batch_id", row.batchId());
        values.put("trade_date_ts", row.tradeDateTs());
        return new DatasetValues(values);
    }

    public L2DatasetManifest fromValues(DatasetValues values) {
        return new L2DatasetManifest(
                values.get("trade_date", LocalDate.class),
                values.get("symbol", String.class),
                values.get("market", String.class),
                values.get("board", String.class),
                values.get("source_root", String.class),
                values.get("output_root", String.class),
                values.get("feature_version", String.class),
                values.get("daily_feature_ok", Boolean.class),
                values.get("t0_ok", Boolean.class),
                values.get("raw_row_counts", String.class),
                values.get("output_paths", String.class),
                values.get("cost_config", String.class),
                values.get("horizons_min", String.class),
                values.get("errors", String.class),
                values.get("batch_id", Long.class),
                values.get("trade_date_ts", LocalDate.class));
    }

    public Instant partitionCarrier(L2DatasetManifest row) {
        return new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier();
    }

    public static java.util.List<String> columns() {
        return L2DatasetManifestDataset.DEFINITION.columns().stream()
                .map(com.zoutrankil.data.domain.DatasetDefinition.Column::logicalName).toList();
    }
}
