package com.zoutrankil.data.domain.derived;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** D098 native MV contract; refresh belongs to the canonical materialization job. */
public final class RetailSentimentDailyDataset {
    private RetailSentimentDailyDataset() {}

    private static final TemporalContract TRADE_DATE =
            new TemporalContract(
                    TemporalKind.BUSINESS_DATE,
                    "BASIC",
                    "calendar",
                    "DAY",
                    "SAMPLE BY 1d ALIGN TO CALENDAR of l2_daily_features.ts; exact UTC midnight date carrier");

    public static final DatasetDefinition DEFINITION =
            new DatasetDefinition(
                    "mv_retail_sentiment_daily",
                    1,
                    "python.derived.market_barometer_views",
                    "retail_sentiment_daily_owner",
                    "mv_retail_sentiment_daily",
                    ObjectKind.MATERIALIZED_VIEW,
                    List.of(
                            new Column(
                                    "l2_daily_features.ts",
                                    "trade_date",
                                    "trade_date",
                                    StorageType.TIMESTAMP,
                                    false,
                                    "Calendar-day bucket identity; no event-time conversion",
                                    TRADE_DATE),
                            new Column(
                                    "derived:avg(gmm_retail_ratio)",
                                    "avg_retail_ratio",
                                    "avg_retail_ratio",
                                    StorageType.DOUBLE,
                                    true,
                                    "Arithmetic mean of GMM retail volume ratio; dimensionless",
                                    null),
                            new Column(
                                    "derived:avg(mean_retail_entropy)",
                                    "avg_retail_entropy",
                                    "avg_retail_entropy",
                                    StorageType.DOUBLE,
                                    true,
                                    "Arithmetic mean of retail Shannon time entropy in bits",
                                    null),
                            new Column(
                                    "derived:sum(retail_total_amount)/100000000.0",
                                    "total_retail_amount_yi",
                                    "total_retail_amount_yi",
                                    StorageType.DOUBLE,
                                    true,
                                    "Retail turnover in yi yuan; source yuan divided by 100000000 exactly once",
                                    null),
                            new Column(
                                    "derived:sum(retail_funds_net_inflow)/100000000.0",
                                    "total_retail_net_inflow_yi",
                                    "total_retail_net_inflow_yi",
                                    StorageType.DOUBLE,
                                    true,
                                    "Signed retail net inflow in yi yuan; source yuan divided by 100000000",
                                    null),
                            new Column(
                                    "derived:avg(mean_rel_aggro)",
                                    "avg_rel_aggro",
                                    "avg_rel_aggro",
                                    StorageType.DOUBLE,
                                    true,
                                    "Arithmetic mean of relative buy/sell aggression; source metric scale retained",
                                    null),
                            new Column(
                                    "derived:sum(q1_count)",
                                    "total_q1",
                                    "total_q1",
                                    StorageType.LONG,
                                    true,
                                    "Sum of Q1 windows with net inflow and price rise; SQL SUM null stays null",
                                    null),
                            new Column(
                                    "derived:sum(q3_count)",
                                    "total_q3",
                                    "total_q3",
                                    StorageType.LONG,
                                    true,
                                    "Sum of Q3 windows with net outflow and price decline; SQL SUM null stays null",
                                    null),
                            new Column(
                                    "derived:avg(wash_trade_ratio)",
                                    "avg_wash_trade_ratio",
                                    "avg_wash_trade_ratio",
                                    StorageType.DOUBLE,
                                    true,
                                    "Arithmetic mean of wash trade ratio; dimensionless",
                                    null),
                            new Column(
                                    "derived:sum(spoof_count)",
                                    "total_spoof_count",
                                    "total_spoof_count",
                                    StorageType.LONG,
                                    true,
                                    "Sum of severe spoofing windows; SQL SUM null stays null",
                                    null),
                            new Column(
                                    "derived:sum(fake_support_count+fake_pressure_count)",
                                    "total_manipulation_count",
                                    "total_manipulation_count",
                                    StorageType.LONG,
                                    true,
                                    "Sum of rowwise fake support plus fake pressure counts; either null makes that row expression null",
                                    null),
                            new Column(
                                    "derived:avg(mfi_score)",
                                    "avg_mfi_score",
                                    "avg_mfi_score",
                                    StorageType.DOUBLE,
                                    true,
                                    "Arithmetic mean of main force intensity score; source score scale retained",
                                    null),
                            new Column(
                                    "derived:sum(main_net_inflow)/100000000.0",
                                    "total_main_net_yi",
                                    "total_main_net_yi",
                                    StorageType.DOUBLE,
                                    true,
                                    "Signed main net inflow in yi yuan; source yuan divided by 100000000",
                                    null)),
                    List.of("trade_date"),
                    List.of(),
                    "trade_date",
                    Partition.MONTH,
                    true,
                    Set.of(Capability.READ),
                    List.of("l2_daily_features"),
                    "One calendar-day aggregation is the natural business key. Native MV is MONTH/WAL without DEDUP or UPSERT keys. "
                            + "Direct row writes and replacements are forbidden; the canonical job provides bounded native MATERIALIZE. "
                            + "Read requires valid, caught-up refresh state and stable source and MV physical versions. "
                            + "Nullable SUM/AVG results are retained without zero filling or additional unit conversion.");

    /** 输出日期和聚合值不变；v2 明确绑定新物理来源，旧契约原样保留。 */
    public static DatasetDefinition definition(
            String output, String source, CnTradeDateLayout layout) {
        DatasetDefinition.identifier(output);
        DatasetDefinition.identifier(source);
        if (layout == CnTradeDateLayout.LEGACY_V1) {
            if (!DEFINITION.objectName().equals(output) || !"l2_daily_features".equals(source))
                throw new IllegalArgumentException("MV v1 使用原正式来源和输出");
            return DEFINITION;
        }
        Objects.requireNonNull(layout);
        var columns = new ArrayList<>(DEFINITION.columns());
        var date = columns.getFirst();
        var temporal =
                new TemporalContract(
                        TemporalKind.BUSINESS_DATE,
                        "UTC午夜日期载体",
                        "calendar",
                        "DAY",
                        "对显式 L2 trade_date 来源按日聚合，日期值不做事件时区转换");
        columns.set(
                0,
                new Column(
                        source + "." + layout.physicalDateColumn("l2_daily_features"),
                        date.logicalName(),
                        date.storageName(),
                        date.storageType(),
                        date.nullable(),
                        date.meaning(),
                        temporal,
                        date.legacyNullSentinels(),
                        date.numeric()));
        return new DatasetDefinition(
                DEFINITION.datasetId(),
                2,
                DEFINITION.provider(),
                DEFINITION.owner(),
                output,
                DEFINITION.objectKind(),
                columns,
                DEFINITION.businessKey(),
                DEFINITION.dedupKey(),
                DEFINITION.designatedTimestamp(),
                DEFINITION.partition(),
                DEFINITION.wal(),
                DEFINITION.capabilities(),
                DEFINITION.dependencies(),
                "显式 v2 L2 隔离来源与原生 MV；保留原13列、单位、null和聚合规则，读取须核对对应 SQL 及稳定版本。");
    }
}
