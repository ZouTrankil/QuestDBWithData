package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.Objects;

/** 固定来源、日期布局与输出归属，不能从现场列名或相同行值猜版本。 */
public record RetailSentimentDailyLayout(
        CnTradeDateLayout layout, String sourceTable, String outputTable) {
    public static final String V2_OUTPUT_PREFIX = "java_d098_mv_retail_sentiment_daily_";
    public static final RetailSentimentDailyLayout LEGACY =
            new RetailSentimentDailyLayout(
                    CnTradeDateLayout.LEGACY_V1,
                    RetailSentimentDailyPolicy.SOURCE,
                    RetailSentimentDailyPolicy.OUTPUT);

    public RetailSentimentDailyLayout {
        Objects.requireNonNull(layout);
        DatasetDefinition.identifier(sourceTable);
        DatasetDefinition.identifier(outputTable);
        if (layout == CnTradeDateLayout.LEGACY_V1) {
            if (!RetailSentimentDailyPolicy.SOURCE.equals(sourceTable)
                    || !RetailSentimentDailyPolicy.OUTPUT.equals(outputTable))
                throw new IllegalArgumentException("MV v1 保留原正式来源和输出");
        } else {
            if (!sourceTable.startsWith("java_d086_l2_daily_features_")
                    || sourceTable.length() == "java_d086_l2_daily_features_".length()
                    || !outputTable.startsWith(V2_OUTPUT_PREFIX)
                    || outputTable.length() == V2_OUTPUT_PREFIX.length())
                throw new IllegalArgumentException("MV v2 仅允许明确的 L2 与 MV 隔离目标");
        }
    }

    public int version() {
        return layout.schemaVersion();
    }

    public String dateColumn() {
        return layout.physicalDateColumn("l2_daily_features");
    }
}
