package com.zoutrankil.market.btc.marketdata;

import com.zoutrankil.data.domain.DatasetDefinition;
import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** UTC K 线起点；现货和 USDT 永续分开，成交量统一为基础币数量。 */
public final class CryptoMarketDataset {
    private CryptoMarketDataset() {}
    public static final DatasetDefinition BARS=new DatasetDefinition("crypto_ohlcv",1,"market.btc.external","java.market.btc","crypto_ohlcv",ObjectKind.TABLE,List.of(
            field("ts",StorageType.TIMESTAMP,"真实 UTC K 线起点"),field("exchange",StorageType.SYMBOL,"交易所"),
            field("symbol",StorageType.SYMBOL,"交易所币对代码"),field("instrument",StorageType.SYMBOL,"SPOT 或 USDT_PERPETUAL"),
            field("interval_minutes",StorageType.INT,"60 为小时，1440 为 UTC 日"),
            field("open",StorageType.DOUBLE,"原始开盘价，USDT"),field("high",StorageType.DOUBLE,"原始最高价，USDT"),
            field("low",StorageType.DOUBLE,"原始最低价，USDT"),field("close",StorageType.DOUBLE,"原始收盘价，USDT"),
            field("volume",StorageType.DOUBLE,"基础币成交数量"),field("quote_volume",StorageType.DOUBLE,"USDT 成交金额"),
            field("source_sha256",StorageType.SYMBOL,"冻结响应 SHA-256"),field("observed_at",StorageType.TIMESTAMP,"本次来源实际取得时间")),
            List.of("ts","exchange","symbol","instrument","interval_minutes"),List.of("ts","exchange","symbol","instrument","interval_minutes"),
            "ts",Partition.MONTH,true,Set.of(Capability.READ,Capability.WRITE),List.of(),"按交易所、币对、现货或永续和频率隔离；UTC 分区，完整键去重，逐字段核验后推进来源检查点。");
    private static Column field(String name,StorageType type,String meaning) {
        return new Column(name,name,name,type,false,meaning,type==StorageType.TIMESTAMP?new TemporalContract(TemporalKind.INSTANT,"ISO-8601","UTC","MICROS",meaning):null);
    }
}
