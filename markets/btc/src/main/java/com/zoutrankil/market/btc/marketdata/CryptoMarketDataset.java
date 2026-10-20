package com.zoutrankil.market.btc.marketdata;

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

/** UTC K 线起点；现货和 USDT 永续分开，成交量统一为基础币数量。 */
public final class CryptoMarketDataset {
    private CryptoMarketDataset() {}

    public static final DatasetDefinition BARS =
            new DatasetDefinition(
                    "crypto_ohlcv",
                    1,
                    "market.btc.external",
                    "java.market.btc",
                    "crypto_ohlcv",
                    ObjectKind.TABLE,
                    List.of(
                            field("ts", StorageType.TIMESTAMP, "真实 UTC K 线起点"),
                            field("exchange", StorageType.SYMBOL, "交易所"),
                            field("symbol", StorageType.SYMBOL, "交易所币对代码"),
                            field("instrument", StorageType.SYMBOL, "SPOT 或 USDT_PERPETUAL"),
                            field("interval_minutes", StorageType.INT, "60 为小时，1440 为 UTC 日"),
                            field("open", StorageType.DOUBLE, "原始开盘价，USDT"),
                            field("high", StorageType.DOUBLE, "原始最高价，USDT"),
                            field("low", StorageType.DOUBLE, "原始最低价，USDT"),
                            field("close", StorageType.DOUBLE, "原始收盘价，USDT"),
                            field("volume", StorageType.DOUBLE, "基础币成交数量"),
                            field("quote_volume", StorageType.DOUBLE, "USDT 成交金额"),
                            field("source_sha256", StorageType.SYMBOL, "冻结响应 SHA-256"),
                            field("observed_at", StorageType.TIMESTAMP, "本次来源实际取得时间")),
                    List.of("ts", "exchange", "symbol", "instrument", "interval_minutes"),
                    List.of("ts", "exchange", "symbol", "instrument", "interval_minutes"),
                    "ts",
                    Partition.MONTH,
                    true,
                    Set.of(Capability.READ, Capability.WRITE),
                    List.of(),
                    "按交易所、币对、现货或永续和频率隔离；UTC 分区，完整键去重，逐字段核验后推进来源检查点。");

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

    public static final DatasetDefinition GAPS =
            new DatasetDefinition(
                    "crypto_data_gap_events",
                    1,
                    "exchange.reference",
                    "java.market.btc",
                    "crypto_data_gap_events",
                    ObjectKind.TABLE,
                    List.of(
                            field("ts", StorageType.TIMESTAMP, "没有来源 K 线的 UTC 小时起点"),
                            field("gap_until", StorageType.TIMESTAMP, "缺口结束时刻，不含本时刻"),
                            field("exchange", StorageType.SYMBOL, "交易所"),
                            field("symbol", StorageType.SYMBOL, "交易所币对"),
                            field("instrument", StorageType.SYMBOL, "产品类别"),
                            field("interval_minutes", StorageType.INT, "缺口 K 线周期"),
                            field("reason", StorageType.SYMBOL, "有官方证据的场所停市"),
                            field("closure_from", StorageType.TIMESTAMP, "官方说明的停市起点"),
                            field("closure_until", StorageType.TIMESTAMP, "官方说明的恢复时刻"),
                            field("official_url", StorageType.VARCHAR, "官方停市证据地址"),
                            field("official_evidence_sha256", StorageType.SYMBOL, "官方事实摘录文件摘要"),
                            field("hour_archive_sha256", StorageType.SYMBOL, "有缺口的官方小时归档摘要"),
                            field("native_daily_sha256", StorageType.SYMBOL, "经核对的官方原生日线归档摘要"),
                            field("rest_empty_sha256", StorageType.SYMBOL, "同小时 REST 空数组响应摘要"),
                            field("source_sha256", StorageType.SYMBOL, "完整核对证据文件摘要"),
                            field("observed_at", StorageType.TIMESTAMP, "本次缺口核对时间")),
                    List.of("ts", "exchange", "symbol", "instrument", "interval_minutes"),
                    List.of("ts", "exchange", "symbol", "instrument", "interval_minutes"),
                    "ts",
                    Partition.MONTH,
                    true,
                    Set.of(Capability.READ, Capability.WRITE),
                    List.of(),
                    "缺口只在官方停市窗口、REST 无记录及原生日线与实际小时成交核对一致时登记；不生成空价格 K 线。");
}
