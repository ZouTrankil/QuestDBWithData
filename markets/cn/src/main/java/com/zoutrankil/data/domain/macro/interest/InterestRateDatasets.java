package com.zoutrankil.data.domain.macro.interest;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 利率日期采用来源自己的观测日，不按 A 股交易日补行或猜发布日期。 */
public final class InterestRateDatasets {
    private InterestRateDatasets() {}

    public static final DatasetDefinition SHIBOR =
            definition(
                    "shibor", "timestamp", List.of("on", "1w", "2w", "1m", "3m", "6m", "9m", "1y"));
    public static final DatasetDefinition HIBOR =
            definition(
                    "hibor", "timestamp", List.of("on", "1w", "2w", "1m", "2m", "3m", "6m", "12m"));
    public static final DatasetDefinition LPR =
            definition("shibor_lpr", "date", List.of("1y", "5y"));

    private static DatasetDefinition definition(
            String table, String physicalDate, List<String> tenors) {
        var columns = new ArrayList<Column>();
        columns.add(
                new Column(
                        "date",
                        "observation_date",
                        physicalDate,
                        StorageType.TIMESTAMP,
                        false,
                        "来源利率观测日；不等于股票交易日或真实可用时刻",
                        new TemporalContract(
                                TemporalKind.BUSINESS_DATE,
                                "BASIC",
                                "calendar",
                                "DAY",
                                "保留来源日期；UTC 午夜为日期载体，不套 SSE 日历，不加 8 小时")));
        for (String tenor : tenors)
            columns.add(
                    new Column(
                            tenor,
                            tenor.equals("on") ? "overnight_rate" : "rate_" + tenor,
                            tenor,
                            StorageType.DOUBLE,
                            true,
                            "来源期限 " + tenor + " 的利率原值；读取不缩放，单位按来源证据另验收",
                            null));
        return new DatasetDefinition(
                table,
                2,
                "tushare." + table,
                "external_cn_reference",
                table,
                ObjectKind.TABLE,
                columns,
                List.of("observation_date"),
                List.of(physicalDate),
                physicalDate,
                Partition.YEAR,
                true,
                Set.of(Capability.READ),
                List.of(),
                "只读 v2 映射，保留外部 YEAR/WAL 及日期去重布局；数字开头的物理期限列始终引用，不执行迁移或变更旧来源请求。");
    }
}
