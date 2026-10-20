# A 股登记对象时间字段逐表核查

105 个登记对象均已逐个核对。明确可把时间别名统一为 `trade_date` 的有 6 个；不能把全部 `ts`、`timestamp` 或其他时间列一起改名。此次仅输出命名建议，没有修改数据库、Java 契约、写入流程或恢复协议。

核查时间：2026-10-20T08:48:08.663242+00:00。范围是 CN 目录登记的 105 个对象，实库共 2321 列，其中 101 个表、4 个原生 MV；不是整个数据库的全部表。每个对象读取系统结构和最多 2 行时间字段样本，普通表核对 `SHOW CREATE TABLE`，4 个 MV 使用 `materialized_views().view_sql`。样本证明字段实际编码，不证明全部历史值都满足契约。

## 汇总

| 判断 | 对象数 |
| --- | ---: |
| 可统一 | 6 |
| 保留其他语义 | 51 |
| 已用 trade_date | 43 |
| 待核 | 3 |
| 需纠偏 | 2 |

“已用 trade_date”只表示当前名称可保留；个别历史结果缺少现行生产契约，限制已写在逐表说明中。“待核”不纳入确定的改名清单。

## 可统一为交易日的六个字段

| 对象 | 当前字段 | 建议逻辑字段 | 直接依据 |
| --- | --- | --- | --- |
| etf_adj | timestamp | trade_date | EtfAdjDataset 的来源、逻辑字段均为 trade_date |
| etf_daily | timestamp | trade_date | EtfDailyDataset；实库为 TIMESTAMP_NS，不能改名时丢失精度 |
| etf_share | timestamp | trade_date | EtfShareDataset；ETF MV 已用 timestamp AS trade_date |
| stk_st_daily | timestamp | trade_date | StockStDailyDataset 明确只展开到 SSE 开市日 |
| stk_suspend | timestamp | trade_date | StockSuspendDataset 明确映射 suspend_d.trade_date |
| l2_daily_features | ts | trade_date | L2DailyFeaturesDataset；情绪 MV 已用 ts AS trade_date |

## 需要单独处理的问题

- `stk_shock.trade_date` 和 `stk_high_shock.trade_date`：来源输出说明为公告日期，建议逻辑名 `announcement_date`。输入参数虽然称交易日期，也不能覆盖输出字段的公告语义。见 [异常波动官方说明](https://tushare.pro/document/2?doc_id=451) 和 [严重异常波动官方说明](https://tushare.pro/document/2?doc_id=452)。
- `index_daily_market`：当前实库的指定列是 `trade_date`，Java 的 `IndexDailyMarketDataset` 仍把逻辑 `trade_date` 映射到物理 `timestamp`。这是已观察到的结构与契约差异，不能用“以后统一命名”掩盖。
- `etf_basic.timestamp`、`fut_basic.timestamp`：现行写入规则固定为 `1970-01-01`，属于技术占位，不能叫交易日，也不能叫实际快照时间。
- `shibor.timestamp`、`hibor.timestamp`、`shibor_lpr.date`：建议逻辑名 `observation_date`，不把不同利率日历强行套到 A 股交易日历。
- `l2_dataset_manifest`：已有字符串 `trade_date` 和分区载体 `trade_date_ts`；后者进入现有去重键。字典可表达两者一对一，物理列不能直接改成重名或删除。
- `regime_stock_return_distribution_daily.as_of`：样本像窗口截止日，但当前仓库缺生产契约。先核实是否严格为窗口结束交易日，再决定是否用 `trade_date`；`computed_at` 仍单独保留。
- `qmt_1m_bars` 与旧 L2 实时表的样本不能单独证明历史时区已正确转换。此轮不做偏移修复，也不把它们降成日期字段。

## 逐个对象的结论

下表列出所有时间字段；字段级的类型、样本、建议名和证据路径见同目录的 columns CSV。`update_time` 等观测字段不自动改成 `ingested_at` 或 `available_at`。

| 序号 | 对象 | 指定时间列 | 时间字段 | 判断及原因 |
| ---: | --- | --- | --- | --- |
| 1 | `etf_adj` | `timestamp` | `timestamp` | **可统一**：timestamp → trade_date；来源 fund_adj 的交易日。 |
| 2 | `etf_basic` | `timestamp` | `found_date`, `due_date`, `list_date`, `issue_date`, `delist_date`, `purc_startdate`, `redm_startdate`, `timestamp`, `update_time` | **保留其他语义**：timestamp 是固定 1970-01-01 技术占位；成立、上市、退市、申赎日期各自保留；update_time 不当作交易日。 |
| 3 | `etf_daily` | `timestamp` | `timestamp` | **可统一**：timestamp → trade_date；保留现有 TIMESTAMP_NS 精度。 |
| 4 | `etf_factor` | `trade_date` | `trade_date` | **已用 trade_date**：来源交易日；不改名。 |
| 5 | `etf_portfolio` | `end_date` | `ann_date`, `end_date`, `update_time` | **保留其他语义**：ann_date 是公告日，end_date 是持仓报告截止日；update_time 是本地观测字段。 |
| 6 | `etf_share` | `timestamp` | `timestamp`, `update_time` | **可统一**：timestamp → trade_date；份额变动所属交易日，update_time 分开保留。 |
| 7 | `qmt_1m_bars` | `bar_time` | `bar_time`, `period`, `updated_at` | **保留其他语义**：bar_time 是分钟K线时间，不能降成交易日；起止边界和历史时区须回查原生产方，updated_at 分开保留。 |
| 8 | `stock_minute_bars` | `bar_time` | `bar_time` | **保留其他语义**：bar_time 是分钟K线时间；目录说明部分时间来自交易时段和源行号映射，不能仅凭时间戳认定为原始事件时刻。 |
| 9 | `cn_bond_yield_curve` | `trade_date` | `trade_date` | **已用 trade_date**：现行契约为 ChinaBond 业务日期，并在已批准 SSE 会话范围核验；保留现有 trade_date，不等同于盘中报价时刻。 |
| 10 | `cn_cpi` | `month` | `month` | **保留其他语义**：month 是统计月份，以月初 UTC 零点承载，不是交易日或发布日期。 |
| 11 | `cn_gdp` | `report_date` | `quarter`, `report_date` | **保留其他语义**：quarter 是季度，report_date 是季度期末标记；建议逻辑名 report_period_end_date。 |
| 12 | `cn_m` | `month` | `month` | **保留其他语义**：month 是统计月份，不是交易日。 |
| 13 | `cn_pmi` | `month` | `month` | **保留其他语义**：month 是统计月份，不是交易日。 |
| 14 | `cn_ppi` | `month` | `month` | **保留其他语义**：month 是统计月份，不是交易日。 |
| 15 | `hibor` | `timestamp` | `timestamp` | **保留其他语义**：timestamp → observation_date；香港同业利率观测日期，不套用 A 股交易日历。 |
| 16 | `rmb_index_daily` | `trade_date` | `trade_date` | **待核**：保留 trade_date；维护字典明确标注官方来源未核实，暂不认证为标准交易日。 |
| 17 | `sf_month` | `month` | `month` | **保留其他语义**：month 是社融统计月份，不是交易日。 |
| 18 | `shibor` | `timestamp` | `timestamp` | **保留其他语义**：timestamp → observation_date；利率观测日期，不因是工作日就改为交易日。 |
| 19 | `shibor_lpr` | `date` | `date` | **保留其他语义**：date → observation_date；来源利率观测日期，不能当作股票交易日或自动当作数据可用时刻。 |
| 20 | `l2_live_normalized_events` | `recv_ts` | `recv_ts`, `trade_date`, `exchange_ts`, `updated_at` | **已用 trade_date**：保留 trade_date；recv_ts 是接收时间，exchange_ts 是交易所事件时间角色，updated_at 分开保留；历史时钟真实性须原接收证据核验。 |
| 21 | `dc_index` | `trade_date` | `trade_date` | **待核**：保留 trade_date；维护字典尚未核实来源字段，不能仅凭名称和午夜值认证语义。 |
| 22 | `index` | `import_time` | `base_date`, `publish_date`, `import_time` | **保留其他语义**：base_date 是基日，publish_date 是发布日期，import_time 是导入时刻；均不是交易日。 |
| 23 | `index_daily_basic` | `trade_date` | `trade_date` | **已用 trade_date**：指数每日指标的交易日；不改名。 |
| 24 | `index_daily_market` | `trade_date` | `trade_date`, `update_time` | **已用 trade_date**：实库已经是 trade_date；Java 契约仍映射到 timestamp，存在结构漂移，迁移前先核对正式写入路径。 |
| 25 | `index_member` | `update_time` | `update_time`, `in_date`, `out_date` | **保留其他语义**：in_date、out_date 是成分纳入和剔除日期；update_time 是本地观测字段；字符串 None 须按原兼容规则处理。 |
| 26 | `index_monthly` | `trade_date` | `trade_date`, `update_time` | **已用 trade_date**：契约明确为月末交易观测日，样本为 2010-01-29；虽为月线也保留 trade_date，不能替换成月初月份标签。 |
| 27 | `index_weight` | `trade_date` | `trade_date`, `update_time` | **已用 trade_date**：成分权重所属交易日；update_time 不是权重生效交易日的替代字段。 |
| 28 | `ths_index` | `update_time` | `list_date`, `update_time` | **保留其他语义**：list_date 是板块上市日期，update_time 是本地观测字段。 |
| 29 | `ths_member` | `update_time` | `in_date`, `out_date`, `update_time` | **保留其他语义**：in_date、out_date 是成员关系日期；来源可能为空，update_time 不能冒充成员生效日。 |
| 30 | `block_trade` | `trade_date` | `trade_date` | **已用 trade_date**：大宗交易所属交易日；不改名。 |
| 31 | `broker_recommend` | `month` | `month` | **保留其他语义**：month 是荐股所属月份，不是交易日。 |
| 32 | `cyq_chips` | `trade_date` | `trade_date` | **已用 trade_date**：筹码分布所属交易日；不改名。 |
| 33 | `cyq_perf` | `trade_date` | `trade_date` | **已用 trade_date**：筹码指标所属交易日；不改名。 |
| 34 | `pledge_stat` | `end_date` | `end_date` | **保留其他语义**：end_date 是质押统计截止日；建议统计语义名 statistics_end_date，不是交易日。 |
| 35 | `repurchase` | `ann_date` | `ann_date`, `end_date`, `exp_date` | **保留其他语义**：ann_date 是公告日，end_date 是统计截止日，exp_date 是到期日；不能统一为交易日。 |
| 36 | `share_float` | `float_date` | `ann_date`, `float_date` | **保留其他语义**：ann_date 是公告日，float_date 是解禁日期；解禁日和交易日不是可互换的数据元。 |
| 37 | `stk_alert` | `start_date` | `start_date`, `end_date` | **保留其他语义**：start_date、end_date 是重点提示的起止日期；保留有效期语义。 |
| 38 | `stk_high_shock` | `trade_date` | `trade_date`, `period` | **需纠偏**：来源输出 trade_date 实为公告日期；标准逻辑名应为 announcement_date，来源名与物理名暂保留。 |
| 39 | `stk_holdernumber` | `ann_date` | `ann_date`, `end_date` | **保留其他语义**：ann_date 是公告日，end_date 是股东人数统计截止日；不能合并。 |
| 40 | `stk_holdertrade` | `ann_date` | `ann_date`, `begin_date`, `close_date` | **保留其他语义**：ann_date 是公告日，begin_date、close_date 是增减持区间；区间边界继续按来源契约处理。 |
| 41 | `stk_shock` | `trade_date` | `trade_date`, `period` | **需纠偏**：来源输出 trade_date 实为公告日期；标准逻辑名应为 announcement_date，不能因来源输入参数称交易日就忽略输出说明。 |
| 42 | `ft_limit` | `trade_date` | `trade_date`, `update_time` | **已用 trade_date**：期货涨跌停价格所属交易日；update_time 分开保留。 |
| 43 | `fut_basic` | `timestamp` | `list_date`, `delist_date`, `d_month`, `last_ddate`, `trade_time_desc`, `timestamp`, `update_time` | **保留其他语义**：timestamp 是固定 1970-01-01 技术占位；list_date、delist_date、last_ddate、d_month 分别是上市日、最后交易日、最后交割日、交割月。 |
| 44 | `fut_daily` | `trade_date` | `trade_date`, `update_time` | **已用 trade_date**：期货日行情所属交易日；夜盘归属由来源会话定义，不取 UTC 日期替代。 |
| 45 | `fut_holding` | `trade_date` | `trade_date`, `update_time` | **已用 trade_date**：期货持仓排名所属交易日；update_time 分开保留。 |
| 46 | `fut_mapping` | `trade_date` | `trade_date`, `update_time` | **已用 trade_date**：现行来源契约按连续合约和单一交易日取得映射；保留 trade_date，不擅自改为区间 effective_from。 |
| 47 | `fut_settle` | `trade_date` | `trade_date`, `update_time` | **已用 trade_date**：期货每日结算所属交易日；不改名。 |
| 48 | `daily` | `trade_date` | `trade_date` | **已用 trade_date**：A 股日行情交易日；不改名。 |
| 49 | `daily_basic` | `trade_date` | `trade_date` | **已用 trade_date**：A 股每日指标交易日；不改名。 |
| 50 | `stk_factor` | `trade_date` | `trade_date` | **已用 trade_date**：股票技术因子所属交易日；不改名。 |
| 51 | `stk_limit` | `trade_date` | `trade_date` | **已用 trade_date**：股票涨跌停价格所属交易日；不改名。 |
| 52 | `stk_st_daily` | `timestamp` | `timestamp` | **可统一**：timestamp → trade_date；现行规则只展开到 SSE 开市日，不把整个名称有效期的日历日期混入。 |
| 53 | `stk_suspend` | `timestamp` | `timestamp` | **可统一**：timestamp → trade_date；现行契约是 suspend_d 交易日，不代表全天停牌；旧字段说明中的连续日期不能代替现行规则。 |
| 54 | `stock_detail_info` | `无` | `update_time`, `list_date`, `delist_date` | **保留其他语义**：list_date、delist_date 是上市和退市日期；update_time 是观测时刻；当前主数据不是按交易日生成的快照。 |
| 55 | `balance` | `ann_date` | `ann_date`, `f_ann_date`, `end_date`, `update_time` | **保留其他语义**：ann_date、f_ann_date 是公告日期角色，end_date 是报告期末；update_time 单独保留。 |
| 56 | `cashflow` | `ann_date` | `ann_date`, `f_ann_date`, `end_date` | **保留其他语义**：ann_date、f_ann_date 是公告日期角色，end_date 是报告期末。 |
| 57 | `disclosure_date` | `ann_date` | `ann_date`, `end_date`, `pre_date`, `actual_date`, `modify_date`, `update_time` | **保留其他语义**：ann_date 为披露安排公告日；end_date 是报告期，pre_date、actual_date 为预计和实际披露日；modify_date 是修正记录，未核对格式前不强制解析成单一日期。 |
| 58 | `dividend` | `ann_date` | `end_date`, `ann_date`, `record_date`, `ex_date`, `pay_date`, `div_listdate`, `imp_ann_date`, `base_date`, `update_time` | **保留其他语义**：ann_date、record_date、ex_date、pay_date 等分别为公告、登记、除权除息、派息日期；end_date 表示分红年度所属报告期。 |
| 59 | `express` | `ann_date` | `ann_date`, `end_date` | **保留其他语义**：ann_date 是公告日，end_date 是报告期末；不改为交易日。 |
| 60 | `fina_audit` | `ann_date` | `ann_date`, `end_date` | **保留其他语义**：ann_date 是公告日，end_date 是审计报告期末。 |
| 61 | `fina_indicator` | `ann_date` | `ann_date`, `end_date` | **保留其他语义**：ann_date 是公告日，end_date 是报告期末。 |
| 62 | `fina_mainbz` | `end_date` | `end_date` | **保留其他语义**：end_date 是主营业务报告期末；不是公告日或交易日。 |
| 63 | `forecast` | `ann_date` | `ann_date`, `end_date`, `first_ann_date` | **保留其他语义**：ann_date、first_ann_date 是公告和首次公告日期；end_date 是报告期末。 |
| 64 | `income` | `ann_date` | `ann_date`, `f_ann_date`, `end_date` | **保留其他语义**：ann_date、f_ann_date 是公告日期角色，end_date 是报告期末。 |
| 65 | `margin_all` | `trade_date` | `trade_date` | **已用 trade_date**：融资融券汇总所属交易日；不改名。 |
| 66 | `margin_detail` | `trade_date` | `trade_date` | **已用 trade_date**：融资融券明细所属交易日；不改名。 |
| 67 | `margin_secs` | `trade_date` | `trade_date` | **已用 trade_date**：融资融券标的清单所属交易日；不改名。 |
| 68 | `margin_trading` | `trade_date` | `trade_date` | **已用 trade_date**：现有目录为两融交易数据，保留 trade_date；缺现行生产契约，未据此认证来源全量语义。 |
| 69 | `margin_zrz` | `trade_date` | `trade_date` | **已用 trade_date**：转融资所属交易日；不改名。 |
| 70 | `moneyflow` | `trade_date` | `trade_date` | **已用 trade_date**：资金流所属交易日；不改名。 |
| 71 | `moneyflow_dc` | `trade_date` | `trade_date` | **已用 trade_date**：东财资金流所属交易日；不改名。 |
| 72 | `moneyflow_hsgt` | `trade_date` | `trade_date` | **已用 trade_date**：现行来源合同按 trade_date 取沪深港通资金流；维护字典的来源说明缺项不触发改名。 |
| 73 | `moneyflow_ths` | `trade_date` | `trade_date` | **已用 trade_date**：同花顺资金流所属交易日；不改名。 |
| 74 | `sge_daily` | `trade_date` | `trade_date` | **已用 trade_date**：黄金现货行情交易日；遵守来源交易会话，不改名。 |
| 75 | `mv_etf_market_overview_daily` | `trade_date` | `trade_date` | **已用 trade_date**：原生 MV 的 SQL 已将 etf_share.timestamp AS trade_date；改基表须同步重建或调整其依赖。 |
| 76 | `mv_backtest_daily` | `trade_date` | `trade_date` | **已用 trade_date**：原生 MV 使用交易日；SQL 仍连接 stk_suspend.timestamp 和 stk_st_daily.timestamp，需与基表迁移一起处理。 |
| 77 | `mv_market_breadth_daily` | `trade_date` | `trade_date` | **已用 trade_date**：原生 MV 沿用 stk_factor.trade_date；不改名。 |
| 78 | `mv_retail_sentiment_daily` | `trade_date` | `trade_date` | **已用 trade_date**：原生 MV 已将 l2_daily_features.ts AS trade_date；这是两字段同义的直接 SQL 证据。 |
| 79 | `l2_daily_features` | `ts` | `ts` | **可统一**：ts → trade_date；契约明确为交易日的午夜日期载体，现有键为 (ts,symbol)。 |
| 80 | `l2_event_response_features` | `minute` | `trade_date`, `minute` | **已用 trade_date**：保留 trade_date；minute 是事件所属分钟时刻，建议明确为 minute_time，不能合并到日期。 |
| 81 | `l2_intraday_bar_features` | `minute` | `trade_date`, `minute` | **已用 trade_date**：保留 trade_date；minute 是分钟时刻，必须与按 Asia/Shanghai 解释的交易日一致。 |
| 82 | `l2_t0_training_labels` | `minute` | `trade_date`, `minute` | **已用 trade_date**：保留 trade_date；minute 是分钟时刻，标签前视窗口不改变事件日期含义。 |
| 83 | `l2_dataset_manifest` | `trade_date_ts` | `trade_date`, `trade_date_ts` | **已用 trade_date**：trade_date 是字符串业务日期；trade_date_ts 是其一对一 UTC 零点分区载体。逻辑字典关联两者，不直接改成同名列或删除载体。 |
| 84 | `company_intrinsic_valuation_consensus` | `valuation_date` | `valuation_date` | **保留其他语义**：valuation_date 是估值基准日期角色；历史生产契约不在当前仓库，不认定等于交易日。 |
| 85 | `company_intrinsic_valuation_model` | `valuation_date` | `valuation_date`, `report_end_date`, `report_ann_date` | **保留其他语义**：valuation_date、report_end_date、report_ann_date 分别为估值基准日、报告期末、报告公告日；保留角色区别。 |
| 86 | `daily_stock_analysis` | `updated_at` | `analysis_date`, `updated_at` | **保留其他语义**：analysis_date 是分析所属日期，updated_at 是更新时间角色；没有规则证明分析日期必为交易日。 |
| 87 | `industry_intrinsic_valuation_state` | `valuation_date` | `valuation_date` | **保留其他语义**：valuation_date 是行业估值基准日期角色；不靠午夜样本改成交易日。 |
| 88 | `industry_valuation_materialization_run` | `created_at` | `created_at`, `start_date`, `end_date` | **保留其他语义**：created_at 是运行创建时刻，start_date、end_date 是请求范围；样本范围含 2023-01-01，不能当作交易日。 |
| 89 | `industry_valuation_membership_conflict` | `valuation_date` | `valuation_date` | **保留其他语义**：valuation_date 是冲突所属的估值基准日期角色；不认证为交易日。 |
| 90 | `industry_valuation_route_assignment` | `effective_from` | `effective_from` | **保留其他语义**：effective_from 是路由生效边界；样本为 2023-01-01；生产契约缺失，待确认日期还是精确时刻，不能改为交易日。 |
| 91 | `equity_style_monthly` | `month` | `month` | **保留其他语义**：month 是月度周期身份；契约明确为月初日期标记，与 index_monthly 的月末交易日不同。 |
| 92 | `macro_core_monthly` | `month` | `month` | **保留其他语义**：month 是宏观观测月份，契约明确不是发布日期或交易月末。 |
| 93 | `macro_liquidity_credit_monthly` | `month` | `month` | **保留其他语义**：month 是月度周期；当前目录和样本不足以把它当作交易日。 |
| 94 | `market_breadth_monthly` | `month` | `month` | **保留其他语义**：month 是市场广度的统计月份，样本为月初标记；不是交易日。 |
| 95 | `market_sentiment_daily` | `trade_date` | `trade_date`, `month`, `updated_at` | **已用 trade_date**：现行 Java 契约把 trade_date 定为交易日期标记；month 是月份标签，updated_at 是计算观测时刻。 |
| 96 | `regime_features_monitor_daily` | `trade_date` | `trade_date`, `month`, `updated_at` | **已用 trade_date**：现行 Java 契约区分交易日与计算观测时刻；month 和 updated_at 不合并。 |
| 97 | `regime_features_monthly` | `month` | `month` | **保留其他语义**：month 是月频状态的周期标签；不改为交易日。 |
| 98 | `regime_market_monthly` | `month` | `month` | **保留其他语义**：month 是月频市场状态的周期标签；不改为交易日。 |
| 99 | `regime_display_materialization_run` | `started_at` | `started_at`, `requested_start_date`, `requested_end_date`, `source_as_of`, `completed_at` | **保留其他语义**：started_at、completed_at 是运行时刻，requested_* 是范围；source_as_of 为输入截止角色，缺生产契约时不认证为交易日。 |
| 100 | `regime_erp_source_status_daily` | `evaluation_date` | `evaluation_date`, `evaluated_at`, `source_as_of`, `bond_as_of` | **保留其他语义**：evaluation_date 是评估所属日期，evaluated_at 是评估时刻；source_as_of、bond_as_of 是来源截止角色，不能全部合并。 |
| 101 | `regime_margin_leverage_daily` | `trade_date` | `trade_date`, `computed_at` | **已用 trade_date**：保留既有 trade_date；computed_at 单独保留。目录与样本支持日频结果角色，历史生产规则仍待原生产方补证。 |
| 102 | `regime_stock_return_distribution_daily` | `as_of` | `as_of`, `window_start_as_of`, `computed_at` | **待核**：as_of 看起来是收益分布截至日期，但缺现行生产规则；只有确认其严格等于收益窗口结束交易日后，才可考虑 trade_date；window_start_as_of 和 computed_at 不能跟着改。 |
| 103 | `fut_index_conclusion_daily` | `trade_date` | `trade_date`, `updated_at` | **已用 trade_date**：保留既有 trade_date；updated_at 是结果更新时间角色；当前目录不替代历史生产方的完整时间契约。 |
| 104 | `fut_index_daily_snapshot` | `trade_date` | `trade_date`, `updated_at` | **已用 trade_date**：保留既有 trade_date；updated_at 与快照所属交易日分开。 |
| 105 | `fut_index_signal_daily` | `trade_date` | `trade_date`, `updated_at` | **已用 trade_date**：保留既有 trade_date；信号所属交易日不等于生成或可用时刻。 |

## 后续命名和迁移边界

标准数据元按业务角色命名：交易日用 `trade_date`，利率观测日用 `observation_date`，公告日用 `announcement_date`，报告期末用 `report_period_end_date`；月度、估值、分析、评估、接收与计算时间各自保留角色。日期在 Java 业务层使用 `LocalDate`，真实时刻使用 UTC `Instant`；旧 UTC 零点日期标记保持原编码，不整体加八小时。

先登记来源名、逻辑名和物理名的映射。新数据集不再使用含义不明的裸 `ts`、`timestamp`。本清单里的“建议逻辑名”不表示现行契约已经修改，也不表示可以直接对正式表执行重命名。

物理迁移前逐项核对来源映射、发送布局、指定时间列、分区、去重键、消费者、原生 MV 和恢复账本。ETF 概览 MV 依赖 ETF 的 timestamp，回测 MV 依赖 ST/停牌的 timestamp，散户情绪 MV 依赖 L2 的 ts。已有未决运行不能因改名重发或自动解锁。用显式迁移保留物理对象和恢复归属；完成完整键值回读、WAL 与依赖刷新验证后，再宣称迁移完成。

QuestDB 的指定时间列是物理时间轴，不要求列名叫 `timestamp`；`DATE` 也仍是毫秒时间戳，不等同于纯日历日期。见 [指定时间列](https://questdb.com/docs/concepts/designated-timestamp/) 和 [时间类型](https://questdb.com/docs/query/functions/date-time/)。

## 核查文件

- [105 个对象清单](2026-10-20-cn-time-fields-objects.csv)：每个对象一行。
- [时间字段清单](2026-10-20-cn-time-fields-columns.csv)：205 个时间或周期字段，每个字段一行。
- [实库证据快照](2026-10-20-cn-time-fields-evidence.json)：元数据、DDL、MV SQL 和每对象最多 2 行时间字段样本。

证据路径使用仓库根目录相对路径；`#对象名` 表示对应 JSON 条目或目录登记项。字段来源说明取自维护中的 `collected-fields.json`，不是本轮把所有官方接口重新验收。没有调用采集、写入、刷新或补数任务；没有运行行为测试。
