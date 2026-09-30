package com.zoutrankil.questdbwithdata.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** Frozen Python T0 label materialization with DAY/WAL/DEDUP and the symbol-minute key. */
public final class L2T0TrainingLabelsDataset {
    private L2T0TrainingLabelsDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "A-share exchange date; validated against minute interpreted in Asia/Shanghai");
    private static final TemporalContract MINUTE = new TemporalContract(
            TemporalKind.INSTANT, "Parquet timestamp[ns] local exchange wall time", "Asia/Shanghai",
            "minute", "Label anchor is stored as a UTC instant at whole-minute precision");

    public static final DatasetDefinition DEFINITION = definition("l2_t0_training_labels");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        var columns = Arrays.stream(L2T0TrainingLabelField.values()).map(field -> {
            TemporalContract temporal = switch (field) {
                case TRADE_DATE -> TRADE_DATE;
                case MINUTE -> MINUTE;
                default -> null;
            };
            return new Column(field.fieldName(), field.fieldName(), field.fieldName(), field.storageType(),
                    field.nullable(), field.meaning(), temporal);
        }).toList();
        return new DatasetDefinition("l2_t0_training_labels", 1, "local.level2.t0_training_labels.parquet",
                "l2_t0_training_labels_owner", table, ObjectKind.TABLE, columns,
                List.of("symbol", "minute"), List.of("symbol", "minute"),
                "minute", Partition.DAY, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("l2_dataset_manifest", "l2_intraday_bar_features"),
                "Each nonempty D087 minute bar yields one D089 label row keyed by (symbol, minute). "
                + "Python applies a forward as-of lookup at minute+h with tolerance h for horizons 1, 3, 5, 10, 15, and 30 minutes. "
                + "Future returns and gross/net alpha fields are look-ahead outcomes, not point-in-time features. "
                + "Python maps a missing gross alpha to opportunity label 0; consumers must use non-null alpha/outcome "
                + "coverage when interpreting binary labels. The physical schema has no maturity flag. "
                + "Same-key source revisions replace all 62 values via DEDUP UPSERT.");
    }
}
