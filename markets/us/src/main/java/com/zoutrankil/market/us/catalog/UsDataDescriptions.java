package com.zoutrankil.market.us.catalog;

import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.domain.DataObjectDescription.SourceType;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.catalog.domain.CatalogTag.*;

/** 维护业务说明与多个标签；对象是否存在、行数和日期始终读取实库。 */
public final class UsDataDescriptions {
    private UsDataDescriptions() {}
    public static List<DataObjectDescription> all() {
        return List.of(
            new DataObjectDescription("us_security_history_catalog", "美国历史证券来源目录", "Stock/ETF 及 OTC 代码、来源窗口和映射原因", Set.of(DataObjectDescription.Market.US),
                    "美股证券范围", SourceType.COLLECTED, "Tiingo supported_tickers 完整目录的美国场所范围", List.of(),
                    "java.market.us", "来源窗口不等于正式上市退市日；目录包含预留代码，实际价格需单独验收。", "UsHistoricalSecurityCatalog.SECURITIES；完整 ZIP 摘要及逐字段回读", Set.of(COLLECTED, EQUITY, ETF, US)),
            new DataObjectDescription("us_etf_equity_holdings", "SPY/QQQ 发行人股票持仓", "当前股票及 ADR 持仓，用于覆盖核对", Set.of(DataObjectDescription.Market.US),
                    "美股证券范围", SourceType.COLLECTED, "State Street 与 Invesco 官方完整持仓", List.of(),
                    "java.market.us", "ETF_HOLDINGS_PROXY，不是官方历史指数成分；现金、CVR、衍生品保留在来源原文中。", "UsFundHoldings.EQUITIES；发行人原文和完整键值回读", Set.of(COLLECTED, ETF, EQUITY, US)),
            new DataObjectDescription("us_security_universe", "美国当前官方挂牌范围", "完整原始目录及候选或排除原因", Set.of(DataObjectDescription.Market.US),
                    "美股证券范围", SourceType.COLLECTED, "Nasdaq Trader nasdaqlisted/otherlisted", List.of(),
                    "java.market.us", "当前挂牌快照，不能替代历史退市和 OTC 范围。", "UsUniverseSnapshot.SECURITIES；官方原文及完整键值回读", Set.of(COLLECTED, EQUITY, US)),
            new DataObjectDescription("us_nasdaq_itch_messages", "Nasdaq ITCH 部分原始订单流", "ITCH 5 完整消息的明确局部范围，保留原始 payload", Set.of(DataObjectDescription.Market.US),
                    "美股高频订单流", SourceType.COLLECTED, "Nasdaq 官方历史 ITCH gzip 文件的有界前缀", List.of(),
                    "java.market.us", "保留真实纳秒与原始消息序号；source_complete=false，不能解释为完整交易日或完整订单簿。", "NasdaqItchDataset.MESSAGES；压缩前缀、取得元数据、原始 payload 及逐字段回读", Set.of(COLLECTED, EQUITY, US)),
            new DataObjectDescription("us_equity_daily", "美股及核心 ETF 日线", "原始及复权 OHLCV、分红与拆股", Set.of(DataObjectDescription.Market.US),
                    "美股行情", SourceType.COLLECTED, "Tiingo EOD；来源列保留实际提供者", List.of(),
                    "java.market.us", "纽约业务日期；当前调整价不代表历史当时可见的价格。", "UsMarketDatasets.DAILY；完整来源响应、SQLite 账本、逐字段回读和 WAL", Set.of(COLLECTED, DAILY, EQUITY, ETF, US)),
            new DataObjectDescription("us_equity_intraday", "美股及核心 ETF 分钟线", "1 分钟来源行情及 5 分钟合并", Set.of(DataObjectDescription.Market.US),
                    "美股日内行情", SourceType.COLLECTED, "Tiingo IEX 历史分钟线；5 分钟由同源分钟合并", List.of(),
                    "java.market.us", "保留来源原样，包括供应商休市日零量补齐记录；研究使用 us_equity_intraday_regular。IEX 场所量不能解释为全美股成交量。", "UsMarketDatasets.MINUTE；冻结来源及完整键值回读", Set.of(COLLECTED, MINUTE, EQUITY, ETF, US)),
            new DataObjectDescription("us_equity_intraday_regular", "美股正常交易时段分钟线", "按已核对日历过滤原始 1/5 分钟及来源补齐记录", Set.of(DataObjectDescription.Market.US),
                    "美股日内行情", SourceType.DERIVED, "us_equity_intraday 与 NYSE/Nasdaq 官方日历", List.of("us_equity_intraday"),
                    "java.market.us", "只读，当前日历版本覆盖 2026 年；保留原始价格及来源摘要，不补造缺分钟。", "UsEquitySessionCalendar.REGULAR；日历原文、版本、视图 SQL 和独立时段覆盖核对", Set.of(DERIVED, MINUTE, CALENDAR, EQUITY, ETF, US)),
            new DataObjectDescription("us_tbr", "美国短期国债利率", "美国短期国债利率", Set.of(DataObjectDescription.Market.US),
                    "美国国债/利率", SourceType.COLLECTED, "Tushare 同名美国利率接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, DAILY, MACRO, RATES, US)),
            new DataObjectDescription("us_tltr", "美国长期国债利率", "美国长期国债利率", Set.of(DataObjectDescription.Market.US),
                    "美国国债/利率", SourceType.COLLECTED, "Tushare 同名美国利率接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, DAILY, MACRO, RATES, US)),
            new DataObjectDescription("us_trltr", "美国实际长期利率平均值", "美国实际长期利率平均值", Set.of(DataObjectDescription.Market.US),
                    "美国国债/利率", SourceType.COLLECTED, "Tushare 同名美国利率接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, DAILY, MACRO, RATES, US)),
            new DataObjectDescription("us_trycr", "美国实际国债收益率曲线", "美国实际国债收益率曲线", Set.of(DataObjectDescription.Market.US),
                    "美国国债/利率", SourceType.COLLECTED, "Tushare 同名美国利率接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, DAILY, MACRO, RATES, US)),
            new DataObjectDescription("us_tycr", "美国国债收益率曲线", "美国国债收益率曲线", Set.of(DataObjectDescription.Market.US),
                    "美国国债/利率", SourceType.COLLECTED, "Tushare 同名美国利率接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, DAILY, MACRO, RATES, US)),
            new DataObjectDescription("us_market_daily", "纳斯达克综合、标普500及VIX日收盘序列", "纳斯达克综合、标普500及VIX日收盘序列", Set.of(DataObjectDescription.Market.US),
                    "美股指数/波动率", SourceType.COLLECTED, "FRED：NASDAQCOM、SP500、VIXCLS", List.of(),
                    "java.market.us", "指数与波动率收盘点位；只补缺失键，保留旧行，FRED 缺值不补零。", "FredMarketDaily.DEFINITION；原始响应、旧行快照、完整键值回读和 WAL", Set.of(COLLECTED, DAILY, INDEX, US))
        );
    }
}
