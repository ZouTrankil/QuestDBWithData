package com.zoutrankil.market.btc.archive;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 原始归档产品独立于旧 crypto_ohlcv；不改变旧聚合日线或未决请求。 */
public final class BinanceArchiveDefinitions {
    private BinanceArchiveDefinitions() {}

    public static DatasetDefinition definition(BinanceArchiveDataset dataset) {
        boolean funding = dataset == BinanceArchiveDataset.FUNDING;
        String time = funding ? "funding_time" : "open_time";
        var columns = new ArrayList<Column>();
        columns.add(
                field(time, StorageType.TIMESTAMP, funding ? "实际结算事件 UTC 时刻" : "原生 K 线 UTC 起点"));
        columns.add(field("exchange", StorageType.SYMBOL, "交易所 BINANCE"));
        columns.add(field("symbol", StorageType.SYMBOL, "币对 BTCUSDT，计价币 USDT"));
        columns.add(field("instrument", StorageType.SYMBOL, "SPOT 或 USDT_PERPETUAL"));
        if (funding) {
            columns.add(field("funding_rate", StorageType.DOUBLE, "该次结算费率，比例值，不乘百分之百"));
            columns.add(
                    new Column(
                            "funding_interval_hours",
                            "funding_interval_hours",
                            "funding_interval_hours",
                            StorageType.INT,
                            true,
                            "来源记录的该次结算间隔，小时；API 未提供时为空，不按时间差推算",
                            null));
        } else {
            columns.add(field("interval_minutes", StorageType.INT, "1h 为 60，原生 1d 为 1440"));
            columns.add(field("close_time", StorageType.TIMESTAMP, "来源 K 线结束时刻，保留原微秒精度"));
            for (String price : List.of("open", "high", "low", "close"))
                columns.add(field(price, StorageType.DOUBLE, "USDT 价格；原始小数另存 ZIP"));
            if (dataset.traded()) {
                columns.add(field("volume", StorageType.DOUBLE, "实际成交数量，BTC"));
                columns.add(field("quote_volume", StorageType.DOUBLE, "实际成交金额，USDT"));
                columns.add(field("trade_count", StorageType.LONG, "实际成交笔数"));
                columns.add(field("taker_buy_volume", StorageType.DOUBLE, "主动买入成交数量，BTC"));
                columns.add(field("taker_buy_quote_volume", StorageType.DOUBLE, "主动买入成交金额，USDT"));
            }
        }
        columns.add(field("source_sha256", StorageType.SYMBOL, "官方 CHECKSUM 核验过的完整 ZIP 摘要"));
        columns.add(field("observed_at", StorageType.TIMESTAMP, "本次实际下载时刻；不是历史已知时间"));
        String name = "btc_" + dataset.id();
        var key =
                funding
                        ? List.of(time, "exchange", "symbol", "instrument")
                        : List.of(time, "exchange", "symbol", "instrument", "interval_minutes");
        return new DatasetDefinition(
                name,
                funding ? 2 : 1,
                "market.btc.binance.archive",
                "java.market.btc.archive",
                name,
                DatasetDefinition.ObjectKind.TABLE,
                columns,
                key,
                key,
                time,
                DatasetDefinition.Partition.MONTH,
                true,
                Set.of(DatasetDefinition.Capability.READ, DatasetDefinition.Capability.WRITE),
                List.of(),
                "Binance 官方原生归档；按数据类型隔离，完整字段回读后推进；原始小数和 ignore 保留在 ZIP。");
    }

    private static Column field(String name, StorageType type, String meaning) {
        return new Column(
                name,
                name,
                name,
                type,
                false,
                meaning,
                type == StorageType.TIMESTAMP
                        ? new TemporalContract(
                                TemporalKind.INSTANT, "ISO-8601", "UTC", "MICROS", meaning)
                        : null);
    }
}
