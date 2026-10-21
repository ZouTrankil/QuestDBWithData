package com.zoutrankil.market.cn.macro.mapper;

import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;

import java.util.List;
import java.util.Set;

/** 将已有统计期契约投影为正式表定义；不从实库反推来源字段。 */
public final class MacroObservationDefinition {
    public static final Set<String> DATASETS =
            Set.of("cn_cpi", "cn_ppi", "cn_pmi", "cn_m", "cn_gdp", "sf_month");

    private MacroObservationDefinition() {}

    public static DatasetDefinition from(SourceContract source) {
        if (!DATASETS.contains(source.dataset())) throw new IllegalArgumentException("未知宏观统计期来源");
        var date =
                new TemporalContract(
                        TemporalKind.BUSINESS_DATE,
                        source.dataset().equals("cn_gdp") ? "季度末日期" : "统计月首日",
                        "calendar",
                        "DAY",
                        "统计期标记；UTC 午夜只是日期载体，不是发布日期或可用时刻");
        var columns =
                source.columns().stream()
                        .map(
                                field ->
                                        new Column(
                                                field.target().equals("report_date")
                                                        ? "derived:quarter_end"
                                                        : field.source(),
                                                field.target(),
                                                field.target(),
                                                StorageType.valueOf(field.type()),
                                                !field.key(),
                                                field.key()
                                                        ? "来源统计期；保留旧表日期布局"
                                                        : "Tushare "
                                                                + source.dataset()
                                                                + " 原值："
                                                                + field.source(),
                                                field.type().startsWith("TIMESTAMP") ? date : null))
                        .toList();
        return new DatasetDefinition(
                source.dataset(),
                1,
                "tushare." + source.dataset(),
                "macro_observation_owner",
                source.dataset(),
                ObjectKind.TABLE,
                columns,
                List.of(source.timestampColumn()),
                List.of(source.timestampColumn()),
                source.timestampColumn(),
                Partition.YEAR,
                true,
                Set.of(Capability.READ, Capability.WRITE),
                List.of(),
                "沿用已有正式 YEAR/WAL 表和统计期去重键；历史修订重查原统计期，不填造未发布期次");
    }
}
