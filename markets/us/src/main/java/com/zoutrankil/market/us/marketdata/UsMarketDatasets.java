package com.zoutrankil.market.us.marketdata;

import com.zoutrankil.data.domain.DatasetDefinition;
import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** 日期键仍是交易日；分钟键是实际 UTC 时刻，来源和市场不可混合覆盖。 */
public final class UsMarketDatasets {
    private UsMarketDatasets() {}
    public static final DatasetDefinition DAILY=definition("us_equity_daily","trade_date",false,List.of("trade_date","symbol","provider"),List.of(
            field("trade_date",StorageType.TIMESTAMP,false,"纽约交易日，编码为 UTC 零点",true),
            field("symbol",StorageType.SYMBOL,false,"来源证券代码；类别股不丢失后缀",false),
            field("provider",StorageType.SYMBOL,false,"实际价格来源",false),
            field("venue",StorageType.SYMBOL,false,"价格覆盖场所或复合市场",false),
            field("open",StorageType.DOUBLE,false,"未复权开盘价，原币种",false),
            field("high",StorageType.DOUBLE,false,"未复权最高价，原币种",false),
            field("low",StorageType.DOUBLE,false,"未复权最低价，原币种",false),
            field("close",StorageType.DOUBLE,false,"未复权收盘价，原币种",false),
            field("volume",StorageType.DOUBLE,false,"未复权成交股数；重建来源允许小数",false),
            field("adj_open",StorageType.DOUBLE,false,"按拆股和分红调整的开盘价",false),
            field("adj_high",StorageType.DOUBLE,false,"按拆股和分红调整的最高价",false),
            field("adj_low",StorageType.DOUBLE,false,"按拆股和分红调整的最低价",false),
            field("adj_close",StorageType.DOUBLE,false,"来源当前调整基准的收盘价，不是历史当时可用价格",false),
            field("adj_volume",StorageType.DOUBLE,false,"来源或拆股规则调整的成交量",false),
            field("dividend",StorageType.DOUBLE,false,"本除息日每股现金分红，原币种",false),
            field("split_factor",StorageType.DOUBLE,false,"当日新股数/旧股数，无事件为 1",false),
            field("currency",StorageType.SYMBOL,false,"价格及现金分红币种",false),
            field("raw_price_basis",StorageType.SYMBOL,false,"来源未复权或根据完整拆股事件重建",false),
            field("source_sha256",StorageType.SYMBOL,false,"冻结原始响应 SHA-256",false),
            field("observed_at",StorageType.TIMESTAMP,false,"本次来源响应实际取得时刻",false)));

    public static final DatasetDefinition MINUTE=definition("us_equity_intraday","ts",true,List.of("ts","symbol","provider","interval_minutes"),List.of(
            field("ts",StorageType.TIMESTAMP,false,"分钟起点，实际 UTC 时间",false),
            field("trade_date",StorageType.TIMESTAMP,false,"按纽约时区确定的业务日",true),
            field("symbol",StorageType.SYMBOL,false,"来源证券代码",false),
            field("provider",StorageType.SYMBOL,false,"实际分钟来源",false),
            field("venue",StorageType.SYMBOL,false,"IEX 或其他实际成交场所，不能冒充全市场成交量",false),
            field("interval_minutes",StorageType.INT,false,"K 线间隔，1 或 5 分钟",false),
            field("open",StorageType.DOUBLE,false,"区间第一笔价格",false),
            field("high",StorageType.DOUBLE,false,"区间最高价格",false),
            field("low",StorageType.DOUBLE,false,"区间最低价格",false),
            field("close",StorageType.DOUBLE,false,"区间最后一笔价格",false),
            field("volume",StorageType.DOUBLE,true,"该来源场所实际成交股数，未知保持空值",false),
            field("source_sha256",StorageType.SYMBOL,false,"冻结原始响应 SHA-256",false),
            field("observed_at",StorageType.TIMESTAMP,false,"响应实际取得时刻",false)));

    private static DatasetDefinition definition(String name,String timestamp,boolean intraday,List<String> keys,List<Column> columns) {
        return new DatasetDefinition(name,1,"market.us.external","java.market.us",name,ObjectKind.TABLE,columns,keys,keys,timestamp,
                intraday?Partition.MONTH:Partition.YEAR,true,Set.of(Capability.READ,Capability.WRITE),List.of(),
                "来源隔离，完整业务键 DEDUP；先冻结来源，再核验全部字段和 WAL，最后推进 SQLite 检查点。");
    }
    private static Column field(String name,StorageType type,boolean nullable,String meaning,boolean businessDate) {
        var temporal=type==StorageType.TIMESTAMP?new TemporalContract(businessDate?TemporalKind.BUSINESS_DATE:TemporalKind.INSTANT,
                "ISO-8601",businessDate?"calendar":"UTC","MICROS",meaning):null;
        return new Column(name,name,name,type,nullable,meaning,temporal);
    }
}
