# 逐表与 View 验收索引（v0.2）

上一版按类别套用的条目已由本版独立文件替代。当前 **172张表、10个普通View、2个MV**；另有3个活跃未落库对象（Q001–Q003）的逐项准入门槛；Q004–Q014已退出落库候选，详见[处置记录](../migration-tasks-20260929/q-object-disposition.md)。每张表至少两组专属输入/预期结果、明确键冲突检查和完整字段矩阵；View依据实际导出SQL给出独立算例。

**阅读顺序：先表，后View/MV。** 各自按优先级排序；执行时仍以真实上游依赖为前置，不强行等待所有低优先级表才验证核心View。

规则依据包括已审计结构、Python模型/调用索引和View SQL。本文没有宣称已逐函数证明全部业务公式；需要源码确认的口径已写成阻塞条件，不允许开发者自行补默认值以通过。

## 表：每表独立验收

| 优先级 | 编号 | 表 | 独立标准 |
|---|---|---|---|
| P0 | D001 | `exchange_calendar` | [字段、键、专属样例与预期](tables/D001-exchange_calendar.md) |
| P0 | D002 | `stock_detail_info` | [字段、键、专属样例与预期](tables/D002-stock_detail_info.md) |
| P0 | D007 | `daily` | [字段、键、专属样例与预期](tables/D007-daily.md) |
| P0 | D008 | `daily_basic` | [字段、键、专属样例与预期](tables/D008-daily_basic.md) |
| P0 | D009 | `stk_factor` | [字段、键、专属样例与预期](tables/D009-stk_factor.md) |
| P0 | D010 | `stk_limit` | [字段、键、专属样例与预期](tables/D010-stk_limit.md) |
| P0 | D011 | `stk_suspend` | [字段、键、专属样例与预期](tables/D011-stk_suspend.md) |
| P0 | D012 | `stk_st_daily` | [字段、键、专属样例与预期](tables/D012-stk_st_daily.md) |
| P1 | D003 | `index` | [字段、键、专属样例与预期](tables/D003-index.md) |
| P1 | D004 | `ths_index` | [字段、键、专属样例与预期](tables/D004-ths_index.md) |
| P1 | D005 | `index_member` | [字段、键、专属样例与预期](tables/D005-index_member.md) |
| P1 | D006 | `ths_member` | [字段、键、专属样例与预期](tables/D006-ths_member.md) |
| P1 | D013 | `etf_basic` | [字段、键、专属样例与预期](tables/D013-etf_basic.md) |
| P1 | D014 | `etf_daily` | [字段、键、专属样例与预期](tables/D014-etf_daily.md) |
| P1 | D015 | `etf_adj` | [字段、键、专属样例与预期](tables/D015-etf_adj.md) |
| P1 | D016 | `etf_share` | [字段、键、专属样例与预期](tables/D016-etf_share.md) |
| P1 | D017 | `etf_factor` | [字段、键、专属样例与预期](tables/D017-etf_factor.md) |
| P1 | D018 | `etf_portfolio` | [字段、键、专属样例与预期](tables/D018-etf_portfolio.md) |
| P1 | D019 | `index_daily_market` | [字段、键、专属样例与预期](tables/D019-index_daily_market.md) |
| P1 | D020 | `index_daily_basic` | [字段、键、专属样例与预期](tables/D020-index_daily_basic.md) |
| P1 | D021 | `index_weight` | [字段、键、专属样例与预期](tables/D021-index_weight.md) |
| P1 | D022 | `index_monthly` | [字段、键、专属样例与预期](tables/D022-index_monthly.md) |
| P1 | D023 | `dc_index` | [字段、键、专属样例与预期](tables/D023-dc_index.md) |
| P2 | D024 | `moneyflow` | [字段、键、专属样例与预期](tables/D024-moneyflow.md) |
| P2 | D025 | `moneyflow_ths` | [字段、键、专属样例与预期](tables/D025-moneyflow_ths.md) |
| P2 | D026 | `moneyflow_dc` | [字段、键、专属样例与预期](tables/D026-moneyflow_dc.md) |
| P2 | D027 | `moneyflow_hsgt` | [字段、键、专属样例与预期](tables/D027-moneyflow_hsgt.md) |
| P2 | D028 | `margin_all` | [字段、键、专属样例与预期](tables/D028-margin_all.md) |
| P2 | D029 | `margin_detail` | [字段、键、专属样例与预期](tables/D029-margin_detail.md) |
| P2 | D030 | `margin_secs` | [字段、键、专属样例与预期](tables/D030-margin_secs.md) |
| P2 | D031 | `margin_zrz` | [字段、键、专属样例与预期](tables/D031-margin_zrz.md) |
| P2 | D032 | `margin_trading` | [字段、键、专属样例与预期](tables/D032-margin_trading.md) |
| P2 | D033 | `block_trade` | [字段、键、专属样例与预期](tables/D033-block_trade.md) |
| P2 | D034 | `stk_shock` | [字段、键、专属样例与预期](tables/D034-stk_shock.md) |
| P2 | D035 | `stk_high_shock` | [字段、键、专属样例与预期](tables/D035-stk_high_shock.md) |
| P2 | D036 | `stk_alert` | [字段、键、专属样例与预期](tables/D036-stk_alert.md) |
| P2 | D037 | `repurchase` | [字段、键、专属样例与预期](tables/D037-repurchase.md) |
| P2 | D038 | `share_float` | [字段、键、专属样例与预期](tables/D038-share_float.md) |
| P2 | D039 | `stk_holdernumber` | [字段、键、专属样例与预期](tables/D039-stk_holdernumber.md) |
| P2 | D040 | `stk_holdertrade` | [字段、键、专属样例与预期](tables/D040-stk_holdertrade.md) |
| P2 | D041 | `pledge_stat` | [字段、键、专属样例与预期](tables/D041-pledge_stat.md) |
| P2 | D042 | `broker_recommend` | [字段、键、专属样例与预期](tables/D042-broker_recommend.md) |
| P2 | D043 | `cyq_perf` | [字段、键、专属样例与预期](tables/D043-cyq_perf.md) |
| P2 | D044 | `cyq_chips` | [字段、键、专属样例与预期](tables/D044-cyq_chips.md) |
| P2 | D045 | `disclosure_date` | [字段、键、专属样例与预期](tables/D045-disclosure_date.md) |
| P2 | D046 | `income_period_meta` | [字段、键、专属样例与预期](tables/D046-income_period_meta.md) |
| P2 | D047 | `income` | [字段、键、专属样例与预期](tables/D047-income.md) |
| P2 | D048 | `cashflow_period_meta` | [字段、键、专属样例与预期](tables/D048-cashflow_period_meta.md) |
| P2 | D049 | `cashflow` | [字段、键、专属样例与预期](tables/D049-cashflow.md) |
| P2 | D050 | `balance` | [字段、键、专属样例与预期](tables/D050-balance.md) |
| P2 | D051 | `fina_indicator_period_meta` | [字段、键、专属样例与预期](tables/D051-fina_indicator_period_meta.md) |
| P2 | D052 | `fina_indicator` | [字段、键、专属样例与预期](tables/D052-fina_indicator.md) |
| P2 | D053 | `express_period_meta` | [字段、键、专属样例与预期](tables/D053-express_period_meta.md) |
| P2 | D054 | `express` | [字段、键、专属样例与预期](tables/D054-express.md) |
| P2 | D055 | `forecast_period_meta` | [字段、键、专属样例与预期](tables/D055-forecast_period_meta.md) |
| P2 | D056 | `forecast` | [字段、键、专属样例与预期](tables/D056-forecast.md) |
| P2 | D057 | `fina_audit` | [字段、键、专属样例与预期](tables/D057-fina_audit.md) |
| P2 | D058 | `dividend` | [字段、键、专属样例与预期](tables/D058-dividend.md) |
| P2 | D059 | `fina_mainbz` | [字段、键、专属样例与预期](tables/D059-fina_mainbz.md) |
| P3 | D060 | `cn_bond_yield_curve` | [字段、键、专属样例与预期](tables/D060-cn_bond_yield_curve.md) |
| P3 | D061 | `shibor` | [字段、键、专属样例与预期](tables/D061-shibor.md) |
| P3 | D062 | `shibor_lpr` | [字段、键、专属样例与预期](tables/D062-shibor_lpr.md) |
| P3 | D063 | `hibor` | [字段、键、专属样例与预期](tables/D063-hibor.md) |
| P3 | D064 | `rmb_index_daily` | [字段、键、专属样例与预期](tables/D064-rmb_index_daily.md) |
| P3 | D065 | `cn_cpi` | [字段、键、专属样例与预期](tables/D065-cn_cpi.md) |
| P3 | D066 | `cn_ppi` | [字段、键、专属样例与预期](tables/D066-cn_ppi.md) |
| P3 | D067 | `cn_pmi` | [字段、键、专属样例与预期](tables/D067-cn_pmi.md) |
| P3 | D068 | `cn_m` | [字段、键、专属样例与预期](tables/D068-cn_m.md) |
| P3 | D069 | `sf_month` | [字段、键、专属样例与预期](tables/D069-sf_month.md) |
| P3 | D070 | `cn_gdp` | [字段、键、专属样例与预期](tables/D070-cn_gdp.md) |
| P3 | D071 | `eco_cal` | [字段、键、专属样例与预期](tables/D071-eco_cal.md) |
| P3 | D072 | `sge_daily` | [字段、键、专属样例与预期](tables/D072-sge_daily.md) |
| P3 | D073 | `us_tbr` | [字段、键、专属样例与预期](tables/D073-us_tbr.md) |
| P3 | D074 | `us_tltr` | [字段、键、专属样例与预期](tables/D074-us_tltr.md) |
| P3 | D075 | `us_trltr` | [字段、键、专属样例与预期](tables/D075-us_trltr.md) |
| P3 | D076 | `us_trycr` | [字段、键、专属样例与预期](tables/D076-us_trycr.md) |
| P3 | D077 | `us_tycr` | [字段、键、专属样例与预期](tables/D077-us_tycr.md) |
| P3 | D078 | `us_market_daily` | [字段、键、专属样例与预期](tables/D078-us_market_daily.md) |
| P3 | D079 | `fut_basic` | [字段、键、专属样例与预期](tables/D079-fut_basic.md) |
| P3 | D080 | `fut_mapping` | [字段、键、专属样例与预期](tables/D080-fut_mapping.md) |
| P3 | D081 | `fut_daily` | [字段、键、专属样例与预期](tables/D081-fut_daily.md) |
| P3 | D082 | `fut_settle` | [字段、键、专属样例与预期](tables/D082-fut_settle.md) |
| P3 | D083 | `ft_limit` | [字段、键、专属样例与预期](tables/D083-ft_limit.md) |
| P3 | D084 | `fut_holding` | [字段、键、专属样例与预期](tables/D084-fut_holding.md) |
| P4 | D085 | `l2_dataset_manifest` | [字段、键、专属样例与预期](tables/D085-l2_dataset_manifest.md) |
| P4 | D086 | `l2_daily_features` | [字段、键、专属样例与预期](tables/D086-l2_daily_features.md) |
| P4 | D087 | `l2_intraday_bar_features` | [字段、键、专属样例与预期](tables/D087-l2_intraday_bar_features.md) |
| P4 | D088 | `l2_event_response_features` | [字段、键、专属样例与预期](tables/D088-l2_event_response_features.md) |
| P4 | D089 | `l2_t0_training_labels` | [字段、键、专属样例与预期](tables/D089-l2_t0_training_labels.md) |
| P4 | D090 | `backtest_daily` | [字段、键、专属样例与预期](tables/D090-backtest_daily.md) |
| P4 | D091 | `backtest_daily_cache_coverage` | [字段、键、专属样例与预期](tables/D091-backtest_daily_cache_coverage.md) |
| P4 | D092 | `backtest_daily_cache` | [字段、键、专属样例与预期](tables/D092-backtest_daily_cache.md) |
| P4 | D094 | `market_barometer_cache_coverage` | [字段、键、专属样例与预期](tables/D094-market_barometer_cache_coverage.md) |
| P4 | D097 | `market_breadth_daily_cache` | [字段、键、专属样例与预期](tables/D097-market_breadth_daily_cache.md) |
| P4 | D100 | `retail_sentiment_daily_cache` | [字段、键、专属样例与预期](tables/D100-retail_sentiment_daily_cache.md) |
| P4 | D101 | `etf_market_overview_daily_cache` | [字段、键、专属样例与预期](tables/D101-etf_market_overview_daily_cache.md) |
| P4 | D103 | `equity_style_monthly` | [字段、键、专属样例与预期](tables/D103-equity_style_monthly.md) |
| P4 | D104 | `macro_core_monthly` | [字段、键、专属样例与预期](tables/D104-macro_core_monthly.md) |
| P4 | D106 | `macro_liquidity_credit_monthly` | [字段、键、专属样例与预期](tables/D106-macro_liquidity_credit_monthly.md) |
| P4 | D108 | `market_breadth_monthly` | [字段、键、专属样例与预期](tables/D108-market_breadth_monthly.md) |
| P4 | D110 | `regime_market_monthly` | [字段、键、专属样例与预期](tables/D110-regime_market_monthly.md) |
| P4 | D112 | `regime_features_monthly` | [字段、键、专属样例与预期](tables/D112-regime_features_monthly.md) |
| P4 | D114 | `regime_features_monitor_daily` | [字段、键、专属样例与预期](tables/D114-regime_features_monitor_daily.md) |
| P4 | D116 | `regime_display_materialization_run` | [字段、键、专属样例与预期](tables/D116-regime_display_materialization_run.md) |
| P4 | D117 | `regime_erp_source_status_daily` | [字段、键、专属样例与预期](tables/D117-regime_erp_source_status_daily.md) |
| P4 | D118 | `regime_ma200_breadth_daily` | [字段、键、专属样例与预期](tables/D118-regime_ma200_breadth_daily.md) |
| P4 | D119 | `regime_margin_leverage_daily` | [字段、键、专属样例与预期](tables/D119-regime_margin_leverage_daily.md) |
| P4 | D120 | `regime_stock_return_distribution_daily` | [字段、键、专属样例与预期](tables/D120-regime_stock_return_distribution_daily.md) |
| P4 | D121 | `market_sentiment_daily` | [字段、键、专属样例与预期](tables/D121-market_sentiment_daily.md) |
| P4 | D122 | `fut_index_daily_snapshot` | [字段、键、专属样例与预期](tables/D122-fut_index_daily_snapshot.md) |
| P4 | D123 | `fut_index_signal_daily` | [字段、键、专属样例与预期](tables/D123-fut_index_signal_daily.md) |
| P4 | D124 | `fut_index_conclusion_daily` | [字段、键、专属样例与预期](tables/D124-fut_index_conclusion_daily.md) |
| P4 | D125 | `daily_stock_analysis` | [字段、键、专属样例与预期](tables/D125-daily_stock_analysis.md) |
| P5 | D126 | `factor_catalog` | [字段、键、专属样例与预期](tables/D126-factor_catalog.md) |
| P5 | D127 | `factor_registry` | [字段、键、专属样例与预期](tables/D127-factor_registry.md) |
| P5 | D128 | `factor_definition` | [字段、键、专属样例与预期](tables/D128-factor_definition.md) |
| P5 | D129 | `factor_dependency` | [字段、键、专属样例与预期](tables/D129-factor_dependency.md) |
| P5 | D130 | `prediction_definition_v1` | [字段、键、专属样例与预期](tables/D130-prediction_definition_v1.md) |
| P5 | D131 | `factor_platform_run` | [字段、键、专属样例与预期](tables/D131-factor_platform_run.md) |
| P5 | D132 | `factor_materialization_run` | [字段、键、专属样例与预期](tables/D132-factor_materialization_run.md) |
| P5 | D133 | `factor_observation` | [字段、键、专属样例与预期](tables/D133-factor_observation.md) |
| P5 | D134 | `factor_daily_metric` | [字段、键、专属样例与预期](tables/D134-factor_daily_metric.md) |
| P5 | D135 | `factor_ic_daily` | [字段、键、专属样例与预期](tables/D135-factor_ic_daily.md) |
| P5 | D136 | `factor_regime_daily` | [字段、键、专属样例与预期](tables/D136-factor_regime_daily.md) |
| P5 | D137 | `factor_validation_daily` | [字段、键、专属样例与预期](tables/D137-factor_validation_daily.md) |
| P5 | D138 | `factor_corr_snapshot` | [字段、键、专属样例与预期](tables/D138-factor_corr_snapshot.md) |
| P5 | D139 | `factor_monitor_daily` | [字段、键、专属样例与预期](tables/D139-factor_monitor_daily.md) |
| P5 | D140 | `factor_monitor_summary` | [字段、键、专属样例与预期](tables/D140-factor_monitor_summary.md) |
| P5 | D141 | `factor_usage` | [字段、键、专属样例与预期](tables/D141-factor_usage.md) |
| P5 | D142 | `industry_valuation_route_assignment` | [字段、键、专属样例与预期](tables/D142-industry_valuation_route_assignment.md) |
| P5 | D143 | `industry_valuation_materialization_run` | [字段、键、专属样例与预期](tables/D143-industry_valuation_materialization_run.md) |
| P5 | D144 | `industry_valuation_membership_conflict` | [字段、键、专属样例与预期](tables/D144-industry_valuation_membership_conflict.md) |
| P5 | D145 | `company_intrinsic_valuation_model` | [字段、键、专属样例与预期](tables/D145-company_intrinsic_valuation_model.md) |
| P5 | D146 | `company_intrinsic_valuation_consensus` | [字段、键、专属样例与预期](tables/D146-company_intrinsic_valuation_consensus.md) |
| P5 | D147 | `industry_intrinsic_valuation_state` | [字段、键、专属样例与预期](tables/D147-industry_intrinsic_valuation_state.md) |
| P5 | D148 | `strategy_templates` | [字段、键、专属样例与预期](tables/D148-strategy_templates.md) |
| P5 | D149 | `strategy_instances` | [字段、键、专属样例与预期](tables/D149-strategy_instances.md) |
| P5 | D150 | `strategy_backtest_runs` | [字段、键、专属样例与预期](tables/D150-strategy_backtest_runs.md) |
| P5 | D151 | `strategy_daily_runs` | [字段、键、专属样例与预期](tables/D151-strategy_daily_runs.md) |
| P5 | D152 | `strategy_backtest_equity_daily` | [字段、键、专属样例与预期](tables/D152-strategy_backtest_equity_daily.md) |
| P5 | D153 | `strategy_backtest_positions` | [字段、键、专属样例与预期](tables/D153-strategy_backtest_positions.md) |
| P5 | D154 | `strategy_backtest_trades` | [字段、键、专属样例与预期](tables/D154-strategy_backtest_trades.md) |
| P5 | D155 | `strategy_target_positions` | [字段、键、专属样例与预期](tables/D155-strategy_target_positions.md) |
| P5 | D156 | `strategy_recommended_orders` | [字段、键、专属样例与预期](tables/D156-strategy_recommended_orders.md) |
| P5 | D157 | `strategy_run_artifact_manifest` | [字段、键、专属样例与预期](tables/D157-strategy_run_artifact_manifest.md) |
| P5 | D158 | `strategy_production_runs` | [字段、键、专属样例与预期](tables/D158-strategy_production_runs.md) |
| P5 | D159 | `strategy_production_equity_daily` | [字段、键、专属样例与预期](tables/D159-strategy_production_equity_daily.md) |
| P5 | D160 | `strategy_production_positions` | [字段、键、专属样例与预期](tables/D160-strategy_production_positions.md) |
| P5 | D161 | `strategy_production_orders` | [字段、键、专属样例与预期](tables/D161-strategy_production_orders.md) |
| P5 | D162 | `strategy_production_state_daily` | [字段、键、专属样例与预期](tables/D162-strategy_production_state_daily.md) |
| P5 | D163 | `strategy_production_pnl_attribution_daily` | [字段、键、专属样例与预期](tables/D163-strategy_production_pnl_attribution_daily.md) |
| P6 | D164 | `tracked_symbols` | [字段、键、专属样例与预期](tables/D164-tracked_symbols.md) |
| P6 | D165 | `l2_live_subscription_intents` | [字段、键、专属样例与预期](tables/D165-l2_live_subscription_intents.md) |
| P6 | D166 | `l2_live_subscription_events` | [字段、键、专属样例与预期](tables/D166-l2_live_subscription_events.md) |
| P6 | D167 | `l2_live_normalized_events` | [字段、键、专属样例与预期](tables/D167-l2_live_normalized_events.md) |
| P6 | D168 | `l2_live_micro_state_1m` | [字段、键、专属样例与预期](tables/D168-l2_live_micro_state_1m.md) |
| P6 | D169 | `l2_live_gateway_stats` | [字段、键、专属样例与预期](tables/D169-l2_live_gateway_stats.md) |
| P6 | D170 | `qmt_tick_data` | [字段、键、专属样例与预期](tables/D170-qmt_tick_data.md) |
| P6 | D171 | `qmt_1m_bars` | [字段、键、专属样例与预期](tables/D171-qmt_1m_bars.md) |
| P6 | D172 | `stock_minute_bars` | [字段、键、专属样例与预期](tables/D172-stock_minute_bars.md) |
| P6 | D173 | `qmt_assets` | [字段、键、专属样例与预期](tables/D173-qmt_assets.md) |
| P6 | D174 | `qmt_positions` | [字段、键、专属样例与预期](tables/D174-qmt_positions.md) |
| P6 | D175 | `qmt_orders` | [字段、键、专属样例与预期](tables/D175-qmt_orders.md) |
| P6 | D176 | `qmt_trades` | [字段、键、专属样例与预期](tables/D176-qmt_trades.md) |
| P6 | D177 | `trade_signals` | [字段、键、专属样例与预期](tables/D177-trade_signals.md) |
| P6 | D178 | `paired_execution_plans` | [字段、键、专属样例与预期](tables/D178-paired_execution_plans.md) |
| P6 | D179 | `orders` | [字段、键、专属样例与预期](tables/D179-orders.md) |
| P6 | D180 | `order_records` | [字段、键、专属样例与预期](tables/D180-order_records.md) |
| P6 | D181 | `order_fills` | [字段、键、专属样例与预期](tables/D181-order_fills.md) |
| P6 | D182 | `trade_records` | [字段、键、专属样例与预期](tables/D182-trade_records.md) |
| P6 | D183 | `broker_statement_deliveries` | [字段、键、专属样例与预期](tables/D183-broker_statement_deliveries.md) |
| P6 | D184 | `broker_statement_cashflows` | [字段、键、专属样例与预期](tables/D184-broker_statement_cashflows.md) |

## 普通 View 和物化视图：按 SQL 验收

| 编号 | 类型 | 对象 | 独立标准 |
|---|---|---|---|
| D093 | VIEW | `v_backtest_daily` | [基表、逐列公式、算例与刷新](views/D093-v_backtest_daily.md) |
| D095 | MATERIALIZED_VIEW | `mv_market_breadth_daily_v1` | [基表、逐列公式、算例与刷新](views/D095-mv_market_breadth_daily_v1.md) |
| D096 | VIEW | `v_market_breadth_daily` | [基表、逐列公式、算例与刷新](views/D096-v_market_breadth_daily.md) |
| D098 | MATERIALIZED_VIEW | `mv_retail_sentiment_daily_v1` | [基表、逐列公式、算例与刷新](views/D098-mv_retail_sentiment_daily_v1.md) |
| D099 | VIEW | `v_retail_sentiment_daily` | [基表、逐列公式、算例与刷新](views/D099-v_retail_sentiment_daily.md) |
| D102 | VIEW | `v_etf_market_overview_daily` | [基表、逐列公式、算例与刷新](views/D102-v_etf_market_overview_daily.md) |
| D105 | VIEW | `v_macro_core_monthly` | [基表、逐列公式、算例与刷新](views/D105-v_macro_core_monthly.md) |
| D107 | VIEW | `v_macro_liquidity_credit_monthly` | [基表、逐列公式、算例与刷新](views/D107-v_macro_liquidity_credit_monthly.md) |
| D109 | VIEW | `v_market_breadth_monthly` | [基表、逐列公式、算例与刷新](views/D109-v_market_breadth_monthly.md) |
| D111 | VIEW | `v_regime_market_monthly` | [基表、逐列公式、算例与刷新](views/D111-v_regime_market_monthly.md) |
| D113 | VIEW | `v_regime_features_monthly` | [基表、逐列公式、算例与刷新](views/D113-v_regime_features_monthly.md) |
| D115 | VIEW | `v_regime_features_monitor_daily` | [基表、逐列公式、算例与刷新](views/D115-v_regime_features_monitor_daily.md) |

## 活跃未落库对象：先逐项准入

| 编号 | 对象 | 准入门槛 |
|---|---|---|
| Q001 | `alpha_research_admission_v1` | [专项准入](conditional/Q001-alpha_research_admission_v1.md) |
| Q002 | `alpha_source_definition_v1` | [专项准入](conditional/Q002-alpha_source_definition_v1.md) |
| Q003 | `alpha_source_member_v1` | [专项准入](conditional/Q003-alpha_source_member_v1.md) |

## 已退出落库候选（历史记录）

以下对象不再要求专项准入或创建独立表；各自历史门槛保留在原卡中，不能视作活跃任务。处置依据见[Q对象处置记录](../migration-tasks-20260929/q-object-disposition.md)。

| 编号 | 对象 | 处置 |
|---|---|---|
| Q004 | `eco_cal_daily_agg` | 保留原始 `eco_cal` 与内存计算，不建聚合表 |
| Q005 | `eco_cal_quantified` | 保留内存量化，不持久化无消费者结果 |
| Q006 | `factor_exposure_snapshot_daily` | 无生产者/消费者，退出候选 |
| Q007 | `factor_lifecycle_event` | 写入已退役，正式发布走现有因子产品 |
| Q008 | `index_daily` | 并入 D019 `index_daily_market`，迁移旧表名消费者 |
| Q009 | `macro_bond_yield` | 并入 D060 核对；先验证 10Y tenor 映射 |
| Q010 | `macro_news` | 无接入链路，退出候选 |
| Q011 | `prediction_observation_v1` | 无模型绑定及真实发布/消费方，退出候选 |
| Q012 | `report_rc` | 保留 provider/read API，暂不建缓存表 |
| Q013 | `stock_news` | 无接入链路，退出候选 |
| Q014 | `swan_industry` | 不建独立表；行业成员沿用 `index_member` |

## 使用要求

- 在每个文件逐用例填写PASS/FAIL/BLOCKED及证据，不能只在本索引批量勾“完成”。
- 通用故障/恢复证据可复用公共执行器报告，但必须证明此表入口确实经过该执行器；独特业务用例不可用其他表替代。
- 结构/key未明确的对象保持blocked；不把“未来需要确认”当已通过的标准。
- 用户认可标准与用户认可实现是两个步骤，均保留独立记录。
