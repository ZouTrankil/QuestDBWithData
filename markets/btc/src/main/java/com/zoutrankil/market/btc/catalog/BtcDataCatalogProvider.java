package com.zoutrankil.market.btc.catalog;

import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.port.DataCatalogProvider;
import java.util.*;
import static com.zoutrankil.data.catalog.domain.CatalogTag.*;

/** 只登记本市场说明，是否已完成五年历史必须读取实际来源回执。 */
public final class BtcDataCatalogProvider implements DataCatalogProvider {
    @Override public List<DataObjectDescription> descriptions() {
        return List.of(new DataObjectDescription("crypto_ohlcv","BTC/ETH 现货及永续行情","按交易所、币对、产品及频率隔离的 OHLCV",Set.of(DataObjectDescription.Market.BTC),
                "加密基础行情",DataObjectDescription.SourceType.COLLECTED,"Binance 官方归档及 Bybit V5 Kline",List.of(),"java.market.btc",
                "原始小时成交量为基础币；日线由齐备的 UTC 24 小时合成，不混合交易所。","CryptoMarketDataset.BARS；官方摘要、冻结响应、SQLite 队列及完整键值回读",Set.of(BTC,COLLECTED,DAILY)));
    }
}
