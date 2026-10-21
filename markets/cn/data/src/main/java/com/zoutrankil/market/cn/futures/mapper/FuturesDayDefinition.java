package com.zoutrankil.market.cn.futures.mapper;

import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.market.cn.observation.mapper.BusinessDateObservationMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 沿用期货原表与来源业务键；日期载体和技术观测时刻分别说明。 */
public final class FuturesDayDefinition {
    public static final Set<String> DATASETS = Set.of("fut_daily");

    private FuturesDayDefinition() {}

    public static DatasetDefinition from(String dataset) {
        if (!DATASETS.contains(dataset)) throw new IllegalArgumentException("未接入的期货日频来源");
        var c = SourceContract.load(dataset);
        var date =
                new TemporalContract(
                        TemporalKind.BUSINESS_DATE,
                        "BASIC",
                        "calendar",
                        "DAY",
                        "来源所属交易日；夜盘不按接收时刻重分交易日，UTC 午夜仅承载原日期");
        var observed =
                new TemporalContract(
                        TemporalKind.INSTANT,
                        "ISO_INSTANT",
                        "UTC",
                        "MICROS",
                        "本次冻结请求的观测开始时间；恢复不重新生成，不冒充市场事件时刻");
        var columns =
                c.columns().stream()
                        .map(
                                f ->
                                        new Column(
                                                f.target().equals("update_time")
                                                        ? "derived:observation_started_at"
                                                        : f.source(),
                                                f.target(),
                                                f.target(),
                                                StorageType.valueOf(f.type()),
                                                !f.key(),
                                                f.target().equals("update_time")
                                                        ? "技术观测时刻，不参与业务键"
                                                        : "Tushare "
                                                                + dataset
                                                                + " 原值："
                                                                + f.source(),
                                                f.target().equals("trade_date")
                                                        ? date
                                                        : f.target().equals("update_time")
                                                                ? observed
                                                                : null))
                        .toList();
        return new DatasetDefinition(
                dataset,
                1,
                "tushare." + dataset,
                "futures_day_owner",
                dataset,
                ObjectKind.TABLE,
                columns,
                List.of("ts_code", "trade_date"),
                List.of("ts_code", "trade_date"),
                "trade_date",
                Partition.YEAR,
                true,
                Set.of(Capability.READ, Capability.WRITE),
                List.of(),
                "只写已存在的正式表；明确单合约和有限日期，不宣称全交易所覆盖，不自动改表");
    }

    public static Map<String, Object> physical(
            String dataset, Map<String, Object> business, Instant observedAt) {
        var c = SourceContract.load(dataset);
        return BusinessDateObservationMapper.physical(
                from(dataset), c.physicalRow(business, observedAt));
    }
}
