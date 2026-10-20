# 数据集归属和时间清单

这里列出目前登记的 Batch 来源。字段、版本和上限以各市场的 `contracts/source/*.json` 为准；注册来源不代表完整历史或正式生产已经验收。

## A 股和中国宏观输入

业务时钟使用上海时间。交易日期、公告日期和报告期分别处理，不把所有来源都按 A 股开市日过滤。

| 数据集 | 来源日期字段 | 目标时间列 | 日期依据 | Batch 任务 |
| --- | --- | --- | --- | --- |
| `cn_bond_yield_curve` | `trade_date` | `trade_date` | 来源发布日，不套股票开市日 | `source_cn_bond_yield_curve` |
| `cn_cpi` | `month` | `month` | 观测月份；月初标记 | `source_cn_cpi` |
| `cn_gdp` | `report_date` | `report_date` | 报告季度；季末标记 | `source_cn_gdp` |
| `cn_m` | `month` | `month` | 观测月份；月初标记 | `source_cn_m` |
| `cn_pmi` | `month` | `month` | 观测月份；月初标记 | `source_cn_pmi` |
| `cn_ppi` | `month` | `month` | 观测月份；月初标记 | `source_cn_ppi` |
| `cyq_perf` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_cyq_perf` |
| `daily` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_daily` |
| `daily_basic` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_daily_basic` |
| `disclosure_date` | `end_date` | `ann_date` | 公告日或报告期；按来源参数 | `source_disclosure_date` |
| `dividend` | `ann_date` | `ann_date` | 公告日或报告期；按来源参数 | `source_dividend` |
| `etf_adj` | `trade_date` | `timestamp` | 来源交易日，按覆盖规则判断 | `source_etf_adj` |
| `etf_basic` | `timestamp` | `timestamp` | 目录快照；保留原技术时间 | `source_etf_basic` |
| `etf_daily` | `trade_date` | `timestamp` | 来源交易日，按覆盖规则判断 | `source_etf_daily` |
| `etf_factor` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_etf_factor` |
| `etf_portfolio` | `ann_date` | `end_date` | 公告日或报告期；按来源参数 | `source_etf_portfolio` |
| `etf_share` | `trade_date` | `timestamp` | 来源交易日，按覆盖规则判断 | `source_etf_share` |
| `exchange_calendar` | `cal_date` | `cal_date` | 交易所日历，包含闭市日 | `source_exchange_calendar` |
| `fina_audit` | `ann_date` | `ann_date` | 公告日或报告期；按来源参数 | `source_fina_audit` |
| `fina_mainbz` | `end_date` | `end_date` | 公告日或报告期；按来源参数 | `source_fina_mainbz` |
| `ft_limit` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_ft_limit` |
| `fut_basic` | `timestamp` | `timestamp` | 目录快照；保留原技术时间 | `source_fut_basic` |
| `fut_daily` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_fut_daily` |
| `fut_holding` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_fut_holding` |
| `fut_mapping` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_fut_mapping` |
| `fut_settle` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_fut_settle` |
| `hibor` | `date` | `timestamp` | 来源发布日，不套股票开市日 | `source_hibor` |
| `index_daily_basic` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_index_daily_basic` |
| `index_daily_market` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_index_daily_market` |
| `margin_detail` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_margin_detail` |
| `moneyflow` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_moneyflow` |
| `moneyflow_hsgt` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_moneyflow_hsgt` |
| `share_float` | `ann_date` | `float_date` | 公告日或报告期；按来源参数 | `source_share_float` |
| `shibor` | `date` | `timestamp` | 来源发布日，不套股票开市日 | `source_shibor` |
| `shibor_lpr` | `date` | `date` | 来源发布日，不套股票开市日 | `source_shibor_lpr` |
| `stk_factor` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_stk_factor` |
| `stk_limit` | `trade_date` | `trade_date` | 来源交易日，按覆盖规则判断 | `source_stk_limit` |
| `stk_st_daily` | `trade_date` | `timestamp` | 来源交易日，按覆盖规则判断 | `source_stk_st_daily` |
| `stk_suspend` | `trade_date` | `timestamp` | 来源交易日，按覆盖规则判断 | `source_stk_suspend` |
| `ths_index` | `update_time` | `update_time` | 目录快照；保留原技术时间 | `source_ths_index` |

日频和报告期时间列保留原来的 UTC 日期编码；技术快照的 epoch 占位也不改。真实事件和接收时刻才按 UTC 时间点处理。

## 美股与美国宏观输入

美国来源放在 `market-us`。目前注册 `source_us_tbr`，来源和目标日期字段都是 `date`，表示利率的来源日期，不是美股开盘时刻，也不套 A 股交易日历。

已有这些美国表字段投影，但只有 `us_tbr` 注册了上述采集任务：

- `UsMarketDailyRow`
- `UsTbrRow`
- `UsTltrRow`
- `UsTrltrRow`
- `UsTrycrRow`
- `UsTycrRow`

美股基础行情另用 `markets/us/data-app` 的有限入口，保存 `us_equity_daily`、`us_equity_intraday`、`us_security_universe`、`us_etf_equity_holdings` 和 `us_nasdaq_itch_messages`。日线业务日期、真实 UTC 分钟和 ITCH 纳秒时间分别定义；这些有限任务不由 `source_us_tbr` 或 A 股 Batch 调度。字段及覆盖验收见[基础行情](us-market-data.md)。

## BTC

`crypto_ohlcv` 保存 Binance、Bybit 的 BTC/ETH 现货、USDT 永续小时线及 UTC 日线；入口在 `markets/btc/data-app`。业务键包含真实 UTC 起点、交易所、币对、产品和分钟间隔。日线由连续 24 根小时线合成。来源完整、逐字段回读和 WAL 核验后才推进队列；当前实际范围见[基础行情](us-market-data.md)。

## A 股衍生和策略数据

股票、ETF、指数、资金流、市场情绪、市场状态、因子和主策略归 A 股。使用跨市场宏观输入时，保留来源自己的发布日期与可用时间。

离线与实时 L2 也归 A 股。事件、接收、可用时刻使用 UTC；交易日和会话按上海时间。实时 Parquet 和 SQLite 接收日志一起备份。

新增数据集时同步更新所属市场的注册、时间含义、覆盖检查和恢复测试；这份清单也一起补充。

美国利率回补预览使用 `calendarVersion=source-date-v1`，逐个查询来源日期；不会用 SSE 开市日筛掉美国日期。它只是有限日期计划，不自动提交执行，也不代表已接通完整美股交易日历。
