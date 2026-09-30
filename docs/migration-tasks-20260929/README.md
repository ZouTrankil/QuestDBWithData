# Java数据迁移串行任务计划

状态：Orca历史启动失败已记录；用户改由当前会话本地直接串行执行。F001–F017、D001–D003已完成本地验收，人工复核待办；下一项D004。详见[执行状态](execution-status.md)。原有注意事项见[迁移renw](../迁移renw)。

按功能阶段组织，每个数据一个独立任务文件；全部主线任务串行执行。优先级按依赖与复用面制定：公共能力→身份/日历→A股核心→ETF→指数→资金事件→财报→宏观→期货→离线L2→衍生视图→因子估值→策略结果→外部运行状态。此顺序是本计划的实施顺序，非来源发布时间顺序。

## 数量和边界

- 17个公共功能任务、184个主线数据任务；Q001–Q003为3个活跃专项准入对象，Q004–Q014为11个已退出落库候选的历史任务卡。共215张登记卡（含归档卡）；主线201项。详见[Q对象处置记录](q-object-disposition.md)。

- 149个backup/staging/WAL测试候选不参与应用迁移；原件保留，不自动删除。
- Java已有184个schema projection，只有部分stock_basic示例读写链路；projection存在不算数据任务完成。
- 每个单数据卡均包含当前分区、主时间、键、字段清单、Python入口、sync模式及读写验收。未知信息显式标记，实施前冻结。
- `stock_detail_info`单独处理与Java `stock_basic`快照的兼容，不能把只抓L状态的示例当完整证券主数据。
- `margin_stock`等配置别名不得产生重复物理数据任务；每个审计对象只出现一次。
- 旧cache、无owner对象和缺失模型必须先确认处置，不为凑齐数量创造重复系统。

## 使用方式

1. 先读[公共契约](00-common-contract.md)，再从F001依次执行。
2. 每次只派发一张任务卡，使用卡末的Orca执行提示。前项通过才进入下一项；失败或blocked停止序列。
3. 每张数据卡是一项端到端工作：Java定义、来源模式、分区、完整键、读取、写入、任务注册和验收，不把多个表塞进一个任务。
4. [manifest.json](manifest.json)提供固定计划ID、顺序、前置和prompt，供协调器读取；它不是Orca已导入或原生可直接导入的格式声明。
5. 调度/任务管理是本项目Java功能；Orca只编排开发任务，两者的task/run ID不得混用。

## Orca协调器约束

- 执行前加载当前版本 `orca skills get orchestration`。本次没有创建Run/Task/Dispatch。
- 建立计划ID到实际Orca Task/Dispatch ID的映射，不把D001等计划编号当运行时ID。
- max_active_dispatches=1；忽略通用指南的并行偏好，遵守用户本次串行要求。
- 任务spec由公共契约和单卡正文组成。worker只完成当前卡，报告证据后结束，协调器验收后才派发下一卡。
- blocked/in_doubt/来源不明不自动跳过、不自动重放；记录原因并修复前置，不能把后续任务标完成。
- Q001–Q003需有逐对象准入记录；Q004–Q014已标记retired_with_evidence，不在活跃执行顺序中。

## 后续批量组合

F013实现SyncGroupDefinition，F014实现批量读取，F015实现批量写入；均引用独立单数据定义。组合默认串行，job内部并发必须受共享限流约束。

建议组合定义（待成员验收后启用，以下不是当前运行配置）：

- reference-refresh：exchange_calendar、stock_detail_info，其他静态资料显式追加。
- equity-post-close：daily、daily_basic、stk_factor、stk_limit、stk_suspend、stk_st_daily。
- etf-post-close：etf_daily、etf_adj、etf_share、etf_factor；etf_basic按自己的刷新频率。
- financial-period：按选定单表job及报告期组合，普通/VIP能力和修订窗口分别保留。
- research-special：cyq_perf、cyq_chips、fina_mainbz，显式区间与额度，不自动加入daily。
- l2-offline：manifest及相应单数据job按依赖执行，先核实源身份和批次。

读组合示例语义：datasets=[daily,etf_daily]，各自symbols/dateRange/projection/pageSize；返回每个数据独立结果与游标。
写组合示例语义：batches=[{datasetId:daily,rows:...},{datasetId:etf_daily,rows:...}]；分别校验、提交、验证和返回状态，不承诺跨表原子性。

## 串行任务索引

| 顺序ID | 功能阶段 | 单个对象/功能 | 前置 | 任务卡 |
| --- | --- | --- | --- | --- |
| F001 | 01-functions | 迁移范围与生成模型基线 | 无 | [任务卡](01-functions/F001-baseline.md) |
| F002 | 01-functions | 统一日期与时刻转换 | F001 | [任务卡](01-functions/F002-temporal.md) |
| F003 | 01-functions | DatasetDefinition注册与校验 | F002 | [任务卡](01-functions/F003-dataset-definition.md) |
| F004 | 01-functions | 共享HTTP客户端与连接复用 | F003 | [任务卡](01-functions/F004-http-client.md) |
| F005 | 01-functions | 共享限流与重试预算 | F004 | [任务卡](01-functions/F005-rate-limit.md) |
| F006 | 01-functions | 日期分片与分页执行器 | F005 | [任务卡](01-functions/F006-slice-planner.md) |
| F007 | 01-functions | 单数据有界读取接口 | F006 | [任务卡](01-functions/F007-read-contract.md) |
| F008 | 01-functions | 单数据分批写入与完成验证 | F007 | [任务卡](01-functions/F008-write-contract.md) |
| F009 | 01-functions | SyncJobDefinition与版本管理 | F008 | [任务卡](01-functions/F009-job-definition.md) |
| F010 | 01-functions | 同步运行账本与状态查询 | F009 | [任务卡](01-functions/F010-run-ledger.md) |
| F011 | 01-functions | 单任务执行与互斥 | F010 | [任务卡](01-functions/F011-single-runner.md) |
| F012 | 01-functions | 断点恢复与任务取消 | F011 | [任务卡](01-functions/F012-resume.md) |
| F013 | 01-functions | 批量同步组合定义与串行运行 | F012 | [任务卡](01-functions/F013-sync-composition.md) |
| F014 | 01-functions | 批量读取组合 | F013 | [任务卡](01-functions/F014-read-composition.md) |
| F015 | 01-functions | 批量写入组合 | F014 | [任务卡](01-functions/F015-write-composition.md) |
| F016 | 01-functions | 同步计划管理 | F015 | [任务卡](01-functions/F016-schedule-management.md) |
| F017 | 01-functions | 同步任务管理CLI | F016 | [任务卡](01-functions/F017-sync-cli.md) |
| D001 | 02-reference | exchange_calendar | F017 | [任务卡](02-reference/D001-exchange_calendar.md) |
| D002 | 02-reference | stock_detail_info | D001 | [任务卡](02-reference/D002-stock_detail_info.md) |
| D003 | 02-reference | index | D002 | [任务卡](02-reference/D003-index.md) |
| D004 | 02-reference | ths_index | D003 | [任务卡](02-reference/D004-ths_index.md) |
| D005 | 02-reference | index_member | D004 | [任务卡](02-reference/D005-index_member.md) |
| D006 | 02-reference | ths_member | D005 | [任务卡](02-reference/D006-ths_member.md) |
| D007 | 03-equity | daily | D006 | [任务卡](03-equity/D007-daily.md) |
| D008 | 03-equity | daily_basic | D007 | [任务卡](03-equity/D008-daily_basic.md) |
| D009 | 03-equity | stk_factor | D008 | [任务卡](03-equity/D009-stk_factor.md) |
| D010 | 03-equity | stk_limit | D009 | [任务卡](03-equity/D010-stk_limit.md) |
| D011 | 03-equity | stk_suspend | D010 | [任务卡](03-equity/D011-stk_suspend.md) |
| D012 | 03-equity | stk_st_daily | D011 | [任务卡](03-equity/D012-stk_st_daily.md) |
| D013 | 04-etf | etf_basic | D012 | [任务卡](04-etf/D013-etf_basic.md) |
| D014 | 04-etf | etf_daily | D013 | [任务卡](04-etf/D014-etf_daily.md) |
| D015 | 04-etf | etf_adj | D014 | [任务卡](04-etf/D015-etf_adj.md) |
| D016 | 04-etf | etf_share | D015 | [任务卡](04-etf/D016-etf_share.md) |
| D017 | 04-etf | etf_factor | D016 | [任务卡](04-etf/D017-etf_factor.md) |
| D018 | 04-etf | etf_portfolio | D017 | [任务卡](04-etf/D018-etf_portfolio.md) |
| D019 | 05-index | index_daily_market | D018 | [任务卡](05-index/D019-index_daily_market.md) |
| D020 | 05-index | index_daily_basic | D019 | [任务卡](05-index/D020-index_daily_basic.md) |
| D021 | 05-index | index_weight | D020 | [任务卡](05-index/D021-index_weight.md) |
| D022 | 05-index | index_monthly | D021 | [任务卡](05-index/D022-index_monthly.md) |
| D023 | 05-index | dc_index | D022 | [任务卡](05-index/D023-dc_index.md) |
| D024 | 06-flows | moneyflow | D023 | [任务卡](06-flows/D024-moneyflow.md) |
| D025 | 06-flows | moneyflow_ths | D024 | [任务卡](06-flows/D025-moneyflow_ths.md) |
| D026 | 06-flows | moneyflow_dc | D025 | [任务卡](06-flows/D026-moneyflow_dc.md) |
| D027 | 06-flows | moneyflow_hsgt | D026 | [任务卡](06-flows/D027-moneyflow_hsgt.md) |
| D028 | 06-flows | margin_all | D027 | [任务卡](06-flows/D028-margin_all.md) |
| D029 | 06-flows | margin_detail | D028 | [任务卡](06-flows/D029-margin_detail.md) |
| D030 | 06-flows | margin_secs | D029 | [任务卡](06-flows/D030-margin_secs.md) |
| D031 | 06-flows | margin_zrz | D030 | [任务卡](06-flows/D031-margin_zrz.md) |
| D032 | 06-flows | margin_trading | D031 | [任务卡](06-flows/D032-margin_trading.md) |
| D033 | 06-flows | block_trade | D032 | [任务卡](06-flows/D033-block_trade.md) |
| D034 | 06-flows | stk_shock | D033 | [任务卡](06-flows/D034-stk_shock.md) |
| D035 | 06-flows | stk_high_shock | D034 | [任务卡](06-flows/D035-stk_high_shock.md) |
| D036 | 06-flows | stk_alert | D035 | [任务卡](06-flows/D036-stk_alert.md) |
| D037 | 06-flows | repurchase | D036 | [任务卡](06-flows/D037-repurchase.md) |
| D038 | 06-flows | share_float | D037 | [任务卡](06-flows/D038-share_float.md) |
| D039 | 06-flows | stk_holdernumber | D038 | [任务卡](06-flows/D039-stk_holdernumber.md) |
| D040 | 06-flows | stk_holdertrade | D039 | [任务卡](06-flows/D040-stk_holdertrade.md) |
| D041 | 06-flows | pledge_stat | D040 | [任务卡](06-flows/D041-pledge_stat.md) |
| D042 | 06-flows | broker_recommend | D041 | [任务卡](06-flows/D042-broker_recommend.md) |
| D043 | 06-flows | cyq_perf | D042 | [任务卡](06-flows/D043-cyq_perf.md) |
| D044 | 06-flows | cyq_chips | D043 | [任务卡](06-flows/D044-cyq_chips.md) |
| D045 | 07-financial | disclosure_date | D044 | [任务卡](07-financial/D045-disclosure_date.md) |
| D046 | 07-financial | income_period_meta | D045 | [任务卡](07-financial/D046-income_period_meta.md) |
| D047 | 07-financial | income | D046 | [任务卡](07-financial/D047-income.md) |
| D048 | 07-financial | cashflow_period_meta | D047 | [任务卡](07-financial/D048-cashflow_period_meta.md) |
| D049 | 07-financial | cashflow | D048 | [任务卡](07-financial/D049-cashflow.md) |
| D050 | 07-financial | balance | D049 | [任务卡](07-financial/D050-balance.md) |
| D051 | 07-financial | fina_indicator_period_meta | D050 | [任务卡](07-financial/D051-fina_indicator_period_meta.md) |
| D052 | 07-financial | fina_indicator | D051 | [任务卡](07-financial/D052-fina_indicator.md) |
| D053 | 07-financial | express_period_meta | D052 | [任务卡](07-financial/D053-express_period_meta.md) |
| D054 | 07-financial | express | D053 | [任务卡](07-financial/D054-express.md) |
| D055 | 07-financial | forecast_period_meta | D054 | [任务卡](07-financial/D055-forecast_period_meta.md) |
| D056 | 07-financial | forecast | D055 | [任务卡](07-financial/D056-forecast.md) |
| D057 | 07-financial | fina_audit | D056 | [任务卡](07-financial/D057-fina_audit.md) |
| D058 | 07-financial | dividend | D057 | [任务卡](07-financial/D058-dividend.md) |
| D059 | 07-financial | fina_mainbz | D058 | [任务卡](07-financial/D059-fina_mainbz.md) |
| D060 | 08-macro | cn_bond_yield_curve | D059 | [任务卡](08-macro/D060-cn_bond_yield_curve.md) |
| D061 | 08-macro | shibor | D060 | [任务卡](08-macro/D061-shibor.md) |
| D062 | 08-macro | shibor_lpr | D061 | [任务卡](08-macro/D062-shibor_lpr.md) |
| D063 | 08-macro | hibor | D062 | [任务卡](08-macro/D063-hibor.md) |
| D064 | 08-macro | rmb_index_daily | D063 | [任务卡](08-macro/D064-rmb_index_daily.md) |
| D065 | 08-macro | cn_cpi | D064 | [任务卡](08-macro/D065-cn_cpi.md) |
| D066 | 08-macro | cn_ppi | D065 | [任务卡](08-macro/D066-cn_ppi.md) |
| D067 | 08-macro | cn_pmi | D066 | [任务卡](08-macro/D067-cn_pmi.md) |
| D068 | 08-macro | cn_m | D067 | [任务卡](08-macro/D068-cn_m.md) |
| D069 | 08-macro | sf_month | D068 | [任务卡](08-macro/D069-sf_month.md) |
| D070 | 08-macro | cn_gdp | D069 | [任务卡](08-macro/D070-cn_gdp.md) |
| D071 | 08-macro | eco_cal | D070 | [任务卡](08-macro/D071-eco_cal.md) |
| D072 | 08-macro | sge_daily | D071 | [任务卡](08-macro/D072-sge_daily.md) |
| D073 | 08-macro | us_tbr | D072 | [任务卡](08-macro/D073-us_tbr.md) |
| D074 | 08-macro | us_tltr | D073 | [任务卡](08-macro/D074-us_tltr.md) |
| D075 | 08-macro | us_trltr | D074 | [任务卡](08-macro/D075-us_trltr.md) |
| D076 | 08-macro | us_trycr | D075 | [任务卡](08-macro/D076-us_trycr.md) |
| D077 | 08-macro | us_tycr | D076 | [任务卡](08-macro/D077-us_tycr.md) |
| D078 | 08-macro | us_market_daily | D077 | [任务卡](08-macro/D078-us_market_daily.md) |
| D079 | 09-futures | fut_basic | D078 | [任务卡](09-futures/D079-fut_basic.md) |
| D080 | 09-futures | fut_mapping | D079 | [任务卡](09-futures/D080-fut_mapping.md) |
| D081 | 09-futures | fut_daily | D080 | [任务卡](09-futures/D081-fut_daily.md) |
| D082 | 09-futures | fut_settle | D081 | [任务卡](09-futures/D082-fut_settle.md) |
| D083 | 09-futures | ft_limit | D082 | [任务卡](09-futures/D083-ft_limit.md) |
| D084 | 09-futures | fut_holding | D083 | [任务卡](09-futures/D084-fut_holding.md) |
| D085 | 10-l2 | l2_dataset_manifest | D084 | [任务卡](10-l2/D085-l2_dataset_manifest.md) |
| D086 | 10-l2 | l2_daily_features | D085 | [任务卡](10-l2/D086-l2_daily_features.md) |
| D087 | 10-l2 | l2_intraday_bar_features | D086 | [任务卡](10-l2/D087-l2_intraday_bar_features.md) |
| D088 | 10-l2 | l2_event_response_features | D087 | [任务卡](10-l2/D088-l2_event_response_features.md) |
| D089 | 10-l2 | l2_t0_training_labels | D088 | [任务卡](10-l2/D089-l2_t0_training_labels.md) |
| D090 | 11-derived | backtest_daily | D089 | [任务卡](11-derived/D090-backtest_daily.md) |
| D091 | 11-derived | backtest_daily_cache_coverage | D090 | [任务卡](11-derived/D091-backtest_daily_cache_coverage.md) |
| D092 | 11-derived | backtest_daily_cache | D091 | [任务卡](11-derived/D092-backtest_daily_cache.md) |
| D093 | 11-derived | v_backtest_daily | D092 | [任务卡](11-derived/D093-v_backtest_daily.md) |
| D094 | 11-derived | market_barometer_cache_coverage | D093 | [任务卡](11-derived/D094-market_barometer_cache_coverage.md) |
| D095 | 11-derived | mv_market_breadth_daily_v1 | D094 | [任务卡](11-derived/D095-mv_market_breadth_daily_v1.md) |
| D096 | 11-derived | v_market_breadth_daily | D095 | [任务卡](11-derived/D096-v_market_breadth_daily.md) |
| D097 | 11-derived | market_breadth_daily_cache | D096 | [任务卡](11-derived/D097-market_breadth_daily_cache.md) |
| D098 | 11-derived | mv_retail_sentiment_daily_v1 | D097 | [任务卡](11-derived/D098-mv_retail_sentiment_daily_v1.md) |
| D099 | 11-derived | v_retail_sentiment_daily | D098 | [任务卡](11-derived/D099-v_retail_sentiment_daily.md) |
| D100 | 11-derived | retail_sentiment_daily_cache | D099 | [任务卡](11-derived/D100-retail_sentiment_daily_cache.md) |
| D101 | 11-derived | etf_market_overview_daily_cache | D100 | [任务卡](11-derived/D101-etf_market_overview_daily_cache.md) |
| D102 | 11-derived | v_etf_market_overview_daily | D101 | [任务卡](11-derived/D102-v_etf_market_overview_daily.md) |
| D103 | 11-derived | equity_style_monthly | D102 | [任务卡](11-derived/D103-equity_style_monthly.md) |
| D104 | 11-derived | macro_core_monthly | D103 | [任务卡](11-derived/D104-macro_core_monthly.md) |
| D105 | 11-derived | v_macro_core_monthly | D104 | [任务卡](11-derived/D105-v_macro_core_monthly.md) |
| D106 | 11-derived | macro_liquidity_credit_monthly | D105 | [任务卡](11-derived/D106-macro_liquidity_credit_monthly.md) |
| D107 | 11-derived | v_macro_liquidity_credit_monthly | D106 | [任务卡](11-derived/D107-v_macro_liquidity_credit_monthly.md) |
| D108 | 11-derived | market_breadth_monthly | D107 | [任务卡](11-derived/D108-market_breadth_monthly.md) |
| D109 | 11-derived | v_market_breadth_monthly | D108 | [任务卡](11-derived/D109-v_market_breadth_monthly.md) |
| D110 | 11-derived | regime_market_monthly | D109 | [任务卡](11-derived/D110-regime_market_monthly.md) |
| D111 | 11-derived | v_regime_market_monthly | D110 | [任务卡](11-derived/D111-v_regime_market_monthly.md) |
| D112 | 11-derived | regime_features_monthly | D111 | [任务卡](11-derived/D112-regime_features_monthly.md) |
| D113 | 11-derived | v_regime_features_monthly | D112 | [任务卡](11-derived/D113-v_regime_features_monthly.md) |
| D114 | 11-derived | regime_features_monitor_daily | D113 | [任务卡](11-derived/D114-regime_features_monitor_daily.md) |
| D115 | 11-derived | v_regime_features_monitor_daily | D114 | [任务卡](11-derived/D115-v_regime_features_monitor_daily.md) |
| D116 | 11-derived | regime_display_materialization_run | D115 | [任务卡](11-derived/D116-regime_display_materialization_run.md) |
| D117 | 11-derived | regime_erp_source_status_daily | D116 | [任务卡](11-derived/D117-regime_erp_source_status_daily.md) |
| D118 | 11-derived | regime_ma200_breadth_daily | D117 | [任务卡](11-derived/D118-regime_ma200_breadth_daily.md) |
| D119 | 11-derived | regime_margin_leverage_daily | D118 | [任务卡](11-derived/D119-regime_margin_leverage_daily.md) |
| D120 | 11-derived | regime_stock_return_distribution_daily | D119 | [任务卡](11-derived/D120-regime_stock_return_distribution_daily.md) |
| D121 | 11-derived | market_sentiment_daily | D120 | [任务卡](11-derived/D121-market_sentiment_daily.md) |
| D122 | 11-derived | fut_index_daily_snapshot | D121 | [任务卡](11-derived/D122-fut_index_daily_snapshot.md) |
| D123 | 11-derived | fut_index_signal_daily | D122 | [任务卡](11-derived/D123-fut_index_signal_daily.md) |
| D124 | 11-derived | fut_index_conclusion_daily | D123 | [任务卡](11-derived/D124-fut_index_conclusion_daily.md) |
| D125 | 11-derived | daily_stock_analysis | D124 | [任务卡](11-derived/D125-daily_stock_analysis.md) |
| D126 | 12-factor | factor_catalog | D125 | [任务卡](12-factor/D126-factor_catalog.md) |
| D127 | 12-factor | factor_registry | D126 | [任务卡](12-factor/D127-factor_registry.md) |
| D128 | 12-factor | factor_definition | D127 | [任务卡](12-factor/D128-factor_definition.md) |
| D129 | 12-factor | factor_dependency | D128 | [任务卡](12-factor/D129-factor_dependency.md) |
| D130 | 12-factor | prediction_definition_v1 | D129 | [任务卡](12-factor/D130-prediction_definition_v1.md) |
| D131 | 12-factor | factor_platform_run | D130 | [任务卡](12-factor/D131-factor_platform_run.md) |
| D132 | 12-factor | factor_materialization_run | D131 | [任务卡](12-factor/D132-factor_materialization_run.md) |
| D133 | 12-factor | factor_observation | D132 | [任务卡](12-factor/D133-factor_observation.md) |
| D134 | 12-factor | factor_daily_metric | D133 | [任务卡](12-factor/D134-factor_daily_metric.md) |
| D135 | 12-factor | factor_ic_daily | D134 | [任务卡](12-factor/D135-factor_ic_daily.md) |
| D136 | 12-factor | factor_regime_daily | D135 | [任务卡](12-factor/D136-factor_regime_daily.md) |
| D137 | 12-factor | factor_validation_daily | D136 | [任务卡](12-factor/D137-factor_validation_daily.md) |
| D138 | 12-factor | factor_corr_snapshot | D137 | [任务卡](12-factor/D138-factor_corr_snapshot.md) |
| D139 | 12-factor | factor_monitor_daily | D138 | [任务卡](12-factor/D139-factor_monitor_daily.md) |
| D140 | 12-factor | factor_monitor_summary | D139 | [任务卡](12-factor/D140-factor_monitor_summary.md) |
| D141 | 12-factor | factor_usage | D140 | [任务卡](12-factor/D141-factor_usage.md) |
| D142 | 12-factor | industry_valuation_route_assignment | D141 | [任务卡](12-factor/D142-industry_valuation_route_assignment.md) |
| D143 | 12-factor | industry_valuation_materialization_run | D142 | [任务卡](12-factor/D143-industry_valuation_materialization_run.md) |
| D144 | 12-factor | industry_valuation_membership_conflict | D143 | [任务卡](12-factor/D144-industry_valuation_membership_conflict.md) |
| D145 | 12-factor | company_intrinsic_valuation_model | D144 | [任务卡](12-factor/D145-company_intrinsic_valuation_model.md) |
| D146 | 12-factor | company_intrinsic_valuation_consensus | D145 | [任务卡](12-factor/D146-company_intrinsic_valuation_consensus.md) |
| D147 | 12-factor | industry_intrinsic_valuation_state | D146 | [任务卡](12-factor/D147-industry_intrinsic_valuation_state.md) |
| D148 | 13-strategy | strategy_templates | D147 | [任务卡](13-strategy/D148-strategy_templates.md) |
| D149 | 13-strategy | strategy_instances | D148 | [任务卡](13-strategy/D149-strategy_instances.md) |
| D150 | 13-strategy | strategy_backtest_runs | D149 | [任务卡](13-strategy/D150-strategy_backtest_runs.md) |
| D151 | 13-strategy | strategy_daily_runs | D150 | [任务卡](13-strategy/D151-strategy_daily_runs.md) |
| D152 | 13-strategy | strategy_backtest_equity_daily | D151 | [任务卡](13-strategy/D152-strategy_backtest_equity_daily.md) |
| D153 | 13-strategy | strategy_backtest_positions | D152 | [任务卡](13-strategy/D153-strategy_backtest_positions.md) |
| D154 | 13-strategy | strategy_backtest_trades | D153 | [任务卡](13-strategy/D154-strategy_backtest_trades.md) |
| D155 | 13-strategy | strategy_target_positions | D154 | [任务卡](13-strategy/D155-strategy_target_positions.md) |
| D156 | 13-strategy | strategy_recommended_orders | D155 | [任务卡](13-strategy/D156-strategy_recommended_orders.md) |
| D157 | 13-strategy | strategy_run_artifact_manifest | D156 | [任务卡](13-strategy/D157-strategy_run_artifact_manifest.md) |
| D158 | 13-strategy | strategy_production_runs | D157 | [任务卡](13-strategy/D158-strategy_production_runs.md) |
| D159 | 13-strategy | strategy_production_equity_daily | D158 | [任务卡](13-strategy/D159-strategy_production_equity_daily.md) |
| D160 | 13-strategy | strategy_production_positions | D159 | [任务卡](13-strategy/D160-strategy_production_positions.md) |
| D161 | 13-strategy | strategy_production_orders | D160 | [任务卡](13-strategy/D161-strategy_production_orders.md) |
| D162 | 13-strategy | strategy_production_state_daily | D161 | [任务卡](13-strategy/D162-strategy_production_state_daily.md) |
| D163 | 13-strategy | strategy_production_pnl_attribution_daily | D162 | [任务卡](13-strategy/D163-strategy_production_pnl_attribution_daily.md) |
| D164 | 14-runtime | tracked_symbols | D163 | [任务卡](14-runtime/D164-tracked_symbols.md) |
| D165 | 14-runtime | l2_live_subscription_intents | D164 | [任务卡](14-runtime/D165-l2_live_subscription_intents.md) |
| D166 | 14-runtime | l2_live_subscription_events | D165 | [任务卡](14-runtime/D166-l2_live_subscription_events.md) |
| D167 | 14-runtime | l2_live_normalized_events | D166 | [任务卡](14-runtime/D167-l2_live_normalized_events.md) |
| D168 | 14-runtime | l2_live_micro_state_1m | D167 | [任务卡](14-runtime/D168-l2_live_micro_state_1m.md) |
| D169 | 14-runtime | l2_live_gateway_stats | D168 | [任务卡](14-runtime/D169-l2_live_gateway_stats.md) |
| D170 | 14-runtime | qmt_tick_data | D169 | [任务卡](14-runtime/D170-qmt_tick_data.md) |
| D171 | 14-runtime | qmt_1m_bars | D170 | [任务卡](14-runtime/D171-qmt_1m_bars.md) |
| D172 | 14-runtime | stock_minute_bars | D171 | [任务卡](14-runtime/D172-stock_minute_bars.md) |
| D173 | 14-runtime | qmt_assets | D172 | [任务卡](14-runtime/D173-qmt_assets.md) |
| D174 | 14-runtime | qmt_positions | D173 | [任务卡](14-runtime/D174-qmt_positions.md) |
| D175 | 14-runtime | qmt_orders | D174 | [任务卡](14-runtime/D175-qmt_orders.md) |
| D176 | 14-runtime | qmt_trades | D175 | [任务卡](14-runtime/D176-qmt_trades.md) |
| D177 | 14-runtime | trade_signals | D176 | [任务卡](14-runtime/D177-trade_signals.md) |
| D178 | 14-runtime | paired_execution_plans | D177 | [任务卡](14-runtime/D178-paired_execution_plans.md) |
| D179 | 14-runtime | orders | D178 | [任务卡](14-runtime/D179-orders.md) |
| D180 | 14-runtime | order_records | D179 | [任务卡](14-runtime/D180-order_records.md) |
| D181 | 14-runtime | order_fills | D180 | [任务卡](14-runtime/D181-order_fills.md) |
| D182 | 14-runtime | trade_records | D181 | [任务卡](14-runtime/D182-trade_records.md) |
| D183 | 14-runtime | broker_statement_deliveries | D182 | [任务卡](14-runtime/D183-broker_statement_deliveries.md) |
| D184 | 14-runtime | broker_statement_cashflows | D183 | [任务卡](14-runtime/D184-broker_statement_cashflows.md) |
| Q001 | 15-conditional | alpha_research_admission_v1（未落库，待准入） | D184 | [任务卡](15-conditional/Q001-alpha_research_admission_v1.md) |
| Q002 | 15-conditional | alpha_source_definition_v1（未落库，待准入） | Q001 | [任务卡](15-conditional/Q002-alpha_source_definition_v1.md) |
| Q003 | 15-conditional | alpha_source_member_v1（未落库，待准入） | Q002 | [任务卡](15-conditional/Q003-alpha_source_member_v1.md) |
| Q004 | 15-conditional | eco_cal_daily_agg（已退出落库候选） | Q003 | [任务卡](15-conditional/Q004-eco_cal_daily_agg.md) |
| Q005 | 15-conditional | eco_cal_quantified（已退出落库候选） | Q004 | [任务卡](15-conditional/Q005-eco_cal_quantified.md) |
| Q006 | 15-conditional | factor_exposure_snapshot_daily（已退出落库候选） | Q005 | [任务卡](15-conditional/Q006-factor_exposure_snapshot_daily.md) |
| Q007 | 15-conditional | factor_lifecycle_event（已退出落库候选） | Q006 | [任务卡](15-conditional/Q007-factor_lifecycle_event.md) |
| Q008 | 15-conditional | index_daily（已退出落库候选） | Q007 | [任务卡](15-conditional/Q008-index_daily.md) |
| Q009 | 15-conditional | macro_bond_yield（已退出落库候选） | Q008 | [任务卡](15-conditional/Q009-macro_bond_yield.md) |
| Q010 | 15-conditional | macro_news（已退出落库候选） | Q009 | [任务卡](15-conditional/Q010-macro_news.md) |
| Q011 | 15-conditional | prediction_observation_v1（已退出落库候选） | Q010 | [任务卡](15-conditional/Q011-prediction_observation_v1.md) |
| Q012 | 15-conditional | report_rc（已退出落库候选） | Q011 | [任务卡](15-conditional/Q012-report_rc.md) |
| Q013 | 15-conditional | stock_news（已退出落库候选） | Q012 | [任务卡](15-conditional/Q013-stock_news.md) |
| Q014 | 15-conditional | swan_industry（已退出落库候选） | Q013 | [任务卡](15-conditional/Q014-swan_industry.md) |

Q001–Q003仍为活跃专项准入；Q004–Q014表格行仅保留历史任务卡入口，不应执行建表。原因见[Q对象处置记录](q-object-disposition.md)。

## 用户逐表复核入口

[逐项完成登记](completion-register.md)保留所有任务初始状态；完成任务必须填写真实sync、增量checkpoint、QuestDB回读和容错证据。机器记录格式见[结果模板](results/template.json)。Python项目绝对路径已写入每张任务卡及执行提示。
