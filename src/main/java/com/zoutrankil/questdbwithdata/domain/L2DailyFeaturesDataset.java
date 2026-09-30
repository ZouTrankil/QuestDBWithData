package com.zoutrankil.questdbwithdata.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** Frozen D086 field mapping and the DAY/WAL/DEDUP QuestDB contract. */
public final class L2DailyFeaturesDataset {
    private L2DailyFeaturesDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "Parquet timestamp[ns] at midnight", "calendar", "DAY",
            "Python l2_daily_features.ts is the trade date carried as a midnight partition timestamp");

    public static final DatasetDefinition DEFINITION = definition("l2_daily_features");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        var columns = Arrays.stream(L2DailyFeatureField.values()).map(field ->
                new Column(field.fieldName(), field.fieldName(), field.fieldName(), field.storageType(),
                        field.nullable(), field.meaning(), field.temporal() ? TRADE_DATE : null)).toList();
        return new DatasetDefinition("l2_daily_features", 1, "local.level2.parquet",
                "l2_daily_features_owner", table, ObjectKind.TABLE, columns,
                List.of("ts", "symbol"), List.of("ts", "symbol"), "ts", Partition.DAY, true,
                Set.of(Capability.READ, Capability.WRITE), List.of("l2_dataset_manifest"),
                "Match the Python 110-column schema. ts is a business-date carrier at midnight; "
                        + "(ts,symbol) is both the business and QuestDB DEDUP UPSERT KEY. "
                        + "Preserve DAY/WAL storage and all source nulls.");
    }
}
