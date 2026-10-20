package com.zoutrankil.data.domain.l2;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Frozen D086 field mapping and the DAY/WAL/DEDUP QuestDB contract. */
public final class L2DailyFeaturesDataset {
    private L2DailyFeaturesDataset() {}

    private static final TemporalContract TRADE_DATE =
            new TemporalContract(
                    TemporalKind.BUSINESS_DATE,
                    "Parquet timestamp[ns] at midnight",
                    "calendar",
                    "DAY",
                    "Python l2_daily_features.ts is the trade date carried as a midnight partition timestamp");

    public static final DatasetDefinition DEFINITION = definition("l2_daily_features");

    public static DatasetDefinition definition(String table) {
        return definition(table, CnTradeDateLayout.LEGACY_V1);
    }

    /** 来源 Parquet 继续用 ts；新版逻辑与物理日期显式改名，不把日期载体当事件时间。 */
    public static DatasetDefinition definition(String table, CnTradeDateLayout layout) {
        DatasetDefinition.identifier(table);
        String date = layout.physicalDateColumn("l2_daily_features");
        var columns =
                Arrays.stream(L2DailyFeatureField.values())
                        .map(
                                field ->
                                        new Column(
                                                field.fieldName(),
                                                field == L2DailyFeatureField.TS
                                                        ? date
                                                        : field.fieldName(),
                                                field == L2DailyFeatureField.TS
                                                        ? date
                                                        : field.fieldName(),
                                                field.storageType(),
                                                field.nullable(),
                                                field.meaning(),
                                                field.temporal() ? TRADE_DATE : null))
                        .toList();
        return new DatasetDefinition(
                "l2_daily_features",
                layout.schemaVersion(),
                "local.level2.parquet",
                "l2_daily_features_owner",
                table,
                ObjectKind.TABLE,
                columns,
                List.of(date, "symbol"),
                List.of(date, "symbol"),
                date,
                Partition.DAY,
                true,
                Set.of(Capability.READ, Capability.WRITE),
                List.of("l2_dataset_manifest"),
                layout == CnTradeDateLayout.TRADE_DATE_V2
                        ? "保留来源 ts，逻辑及物理列为 trade_date；UTC 午夜交易日载体，保留 DAY/WAL、完整去重键和原 null。"
                        : "Match the Python 110-column schema. ts is a business-date carrier at midnight; "
                                + "(ts,symbol) is both the business and QuestDB DEDUP UPSERT KEY. "
                                + "Preserve DAY/WAL storage and all source nulls.");
    }
}
