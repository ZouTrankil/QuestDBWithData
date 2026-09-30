package com.zoutrankil.questdbwithdata.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** Frozen Python event materialization with DAY/WAL/DEDUP and the complete triple key. */
public final class L2EventResponseFeaturesDataset {
    private L2EventResponseFeaturesDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "A-share exchange date; validated against minute interpreted in Asia/Shanghai");
    private static final TemporalContract MINUTE = new TemporalContract(
            TemporalKind.INSTANT, "Parquet timestamp[ns] local exchange wall time", "Asia/Shanghai",
            "minute", "Event minute is stored as a UTC instant at whole-minute precision");

    public static final DatasetDefinition DEFINITION = definition("l2_event_response_features");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        var columns = Arrays.stream(L2EventResponseFeatureField.values()).map(field -> {
            TemporalContract temporal = switch (field) {
                case TRADE_DATE -> TRADE_DATE;
                case MINUTE -> MINUTE;
                default -> null;
            };
            return new Column(field.fieldName(), field.fieldName(), field.fieldName(), field.storageType(),
                    field.nullable(), field.meaning(), temporal);
        }).toList();
        return new DatasetDefinition("l2_event_response_features", 1, "local.level2.event_response.parquet",
                "l2_event_response_features_owner", table, ObjectKind.TABLE, columns,
                List.of("symbol", "minute", "event_type"), List.of("symbol", "minute", "event_type"),
                "minute", Partition.DAY, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("l2_dataset_manifest", "l2_intraday_bar_features"),
                "The full business and audited physical UPSERT key is (symbol, minute, event_type). "
                + "trade_date is the Asia/Shanghai date of the source wall-time minute. Python materializes "
                + "events from D087 minute bars using its amount-quantile/active-flow, OFI-depth-depletion, "
                + "and cancel-ratio rules. Future return fields are look-ahead outcomes, not point-in-time features. "
                + "Source Parquet wall time is explicitly localized to Asia/Shanghai and stored as a UTC instant; "
                + "a later certified batch replaces all 73 values for the full event key.");
    }
}
