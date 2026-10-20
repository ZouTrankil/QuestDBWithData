package com.zoutrankil.market.btc.catalog;

import static com.zoutrankil.data.catalog.domain.CatalogTag.BTC;
import static com.zoutrankil.data.catalog.domain.CatalogTag.COLLECTED;
import static com.zoutrankil.data.catalog.domain.CatalogTag.DAILY;

import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.port.DataCatalogProvider;

import java.util.List;
import java.util.Set;

/** 只登记本市场说明，是否已完成五年历史必须读取实际来源回执。 */
public final class BtcDataCatalogProvider implements DataCatalogProvider {
    @Override
    public List<DataObjectDescription> descriptions() {
        return List.of(
                new DataObjectDescription(
                        "crypto_data_gap_events",
                        "加密交易所停市缺口",
                        "官方停市窗口、原归档缺口及 REST 空记录证据",
                        Set.of(DataObjectDescription.Market.BTC),
                        "加密来源完整性",
                        DataObjectDescription.SourceType.COLLECTED,
                        "交易所官方说明、归档及 REST",
                        List.of(),
                        "java.market.btc",
                        "缺失小时不生成价格；原生日线须与实际小时成交核对。",
                        "CryptoMarketDataset.GAPS；官方事实、原生日线和空记录回执",
                        Set.of(BTC, COLLECTED)),
                new DataObjectDescription(
                        "crypto_ohlcv",
                        "BTC/ETH 现货及永续行情",
                        "按交易所、币对、产品及频率隔离的 OHLCV",
                        Set.of(DataObjectDescription.Market.BTC),
                        "加密基础行情",
                        DataObjectDescription.SourceType.COLLECTED,
                        "Binance 官方归档及 Bybit V5 Kline",
                        List.of(),
                        "java.market.btc",
                        "原始小时成交量为基础币；完整 UTC 日按小时合成，有官方停市缺口的月份使用经核对的原生日线。",
                        "CryptoMarketDataset.BARS；官方摘要、冻结响应、SQLite 队列及完整键值回读",
                        Set.of(BTC, COLLECTED, DAILY)));
    }
}
