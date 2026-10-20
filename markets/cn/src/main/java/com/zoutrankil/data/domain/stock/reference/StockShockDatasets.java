package com.zoutrankil.data.domain.stock.reference;

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

/** 异动来源的 trade_date 输出表示公告日；读取契约不改变外部表或原日期载体。 */
public final class StockShockDatasets {
    private StockShockDatasets() {}

    public static final DatasetDefinition SHOCK = definition("stk_shock");
    public static final DatasetDefinition HIGH_SHOCK = definition("stk_high_shock");

    private static DatasetDefinition definition(String table) {
        return new DatasetDefinition(
                table,
                2,
                "tushare." + table,
                "external_cn_reference",
                table,
                ObjectKind.TABLE,
                List.of(
                        new Column(
                                "ts_code",
                                "instrument_code",
                                "ts_code",
                                StorageType.SYMBOL,
                                false,
                                "来源完整证券代码，保留前导零和交易所后缀",
                                null),
                        new Column(
                                "trade_date",
                                "announcement_date",
                                "trade_date",
                                StorageType.TIMESTAMP,
                                false,
                                "来源公告日期，不是证券行情交易日或精确可用时刻",
                                new TemporalContract(
                                        TemporalKind.BUSINESS_DATE,
                                        "BASIC",
                                        "calendar",
                                        "DAY",
                                        "公告日期；物理 UTC 午夜仅为日期载体，不加减时差")),
                        new Column(
                                "name", "name", "name", StorageType.STRING, true, "来源证券名称", null),
                        new Column(
                                "trade_market",
                                "trade_market",
                                "trade_market",
                                StorageType.SYMBOL,
                                true,
                                "来源交易市场或板块原文，不直接当作标准交易所代码",
                                null),
                        new Column(
                                "reason",
                                "reason",
                                "reason",
                                StorageType.STRING,
                                false,
                                "来源异常说明；保留既有去重身份",
                                null),
                        new Column(
                                "period",
                                "period",
                                "period",
                                StorageType.STRING,
                                false,
                                "来源异常期间原文；不猜测区间格式或公告可用时刻",
                                null)),
                List.of("instrument_code", "announcement_date", "reason", "period"),
                List.of("ts_code", "trade_date", "reason", "period"),
                "trade_date",
                Partition.YEAR,
                true,
                Set.of(Capability.READ),
                List.of(),
                "只读 v2 逻辑映射；保留外部 YEAR/WAL/去重布局、日期值与旧来源协议，不执行 DDL 或签发来源完整证据。");
    }
}
