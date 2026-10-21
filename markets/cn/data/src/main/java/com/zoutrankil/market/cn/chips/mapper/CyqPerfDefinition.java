package com.zoutrankil.market.cn.chips.mapper;

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

/** 沿用每日筹码原表的交易日、证券键和全部原值，不重新计算供应商指标。 */
public final class CyqPerfDefinition {
    private CyqPerfDefinition() {}

    public static final DatasetDefinition DEFINITION = definition();

    private static DatasetDefinition definition() {
        var source = SourceContract.load("cyq_perf");
        var date =
                new TemporalContract(
                        TemporalKind.BUSINESS_DATE,
                        "BASIC",
                        "calendar",
                        "DAY",
                        "UTC 午夜仅承载交易日期，不是行情发生时刻，不做时区平移");
        var columns =
                source.columns().stream()
                        .map(
                                field ->
                                        new Column(
                                                field.source(),
                                                field.target(),
                                                field.target(),
                                                StorageType.valueOf(field.type()),
                                                !field.key(),
                                                "Tushare cyq_perf 原值：" + field.source(),
                                                field.target().equals("trade_date") ? date : null))
                        .toList();
        return new DatasetDefinition(
                "cyq_perf",
                1,
                "tushare.cyq_perf",
                "cyq_perf_owner",
                "cyq_perf",
                ObjectKind.TABLE,
                columns,
                List.of("ts_code", "trade_date"),
                List.of("ts_code", "trade_date"),
                "trade_date",
                Partition.YEAR,
                true,
                Set.of(Capability.READ, Capability.WRITE),
                List.of(),
                "只写已存在的正式表；按明确证券集合及单个开市日核验来源，不推断全市场覆盖");
    }
}
