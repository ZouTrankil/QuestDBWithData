package com.zoutrankil.data.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Frozen D087 Python materialization, business key and DAY/WAL/DEDUP contract. */
public final class L2IntradayBarFeaturesDataset {
    private L2IntradayBarFeaturesDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "A-share exchange date; validated against minute interpreted in Asia/Shanghai");
    private static final TemporalContract MINUTE = new TemporalContract(
            TemporalKind.INSTANT, "Parquet timestamp[ns] local exchange wall time", "Asia/Shanghai",
            "minute", "Source minute is floored in exchange local time and stored as a UTC instant at whole-minute precision");

    public static final DatasetDefinition DEFINITION = definition("l2_intraday_bar_features");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        var columns = Arrays.stream(L2IntradayBarFeatureField.values()).map(field -> {
            TemporalContract temporal = switch (field) {
                case TRADE_DATE -> TRADE_DATE;
                case MINUTE -> MINUTE;
                default -> null;
            };
            return new Column(field.fieldName(), field.fieldName(), field.fieldName(), field.storageType(),
                    field.nullable(), field.meaning(), temporal);
        }).toList();
        return new DatasetDefinition("l2_intraday_bar_features", 1, "local.level2.parquet",
                "l2_intraday_bar_features_owner", table, ObjectKind.TABLE, columns,
                List.of("symbol", "minute"), List.of("symbol", "minute"), "minute", Partition.DAY, true,
                Set.of(Capability.READ, Capability.WRITE), List.of("l2_dataset_manifest"),
                "Keep the audited DAY/WAL/DEDUP layout and (symbol,minute) UPSERT KEY. The canonical business key is "
                        + "(symbol,minute UTC instant); trade_date is the Asia/Shanghai calendar date of that event. "
                        + "Python materializes one minute bar from manifest-certified trade/order/snapshot-derived Parquet. "
                        + "Source local wall time is localized explicitly to Asia/Shanghai and converted to UTC without "
                        + "sub-minute truncation; later certified batches replace the complete minute row.");
    }
}
