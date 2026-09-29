# F001逐对象处置与读取证据

本清单是迁移基线，不是数据完整性认证。未确认owner的对象继续保留待核实。

| 对象 | 当前存在 | Java投影 | 处置 | 实际样本行 | schema差异 |
| --- | --- | --- | --- | --- | --- |
| backtest_daily | True | True | retirement_candidate | 1 | 0 |
| backtest_daily_cache | True | True | implement_from_registered_contract | 1 | 0 |
| backtest_daily_cache_coverage | True | True | implement_from_registered_contract | 1 | 0 |
| balance | True | True | implement_from_registered_contract | 1 | 0 |
| block_trade | True | True | implement_from_registered_contract | 1 | 0 |
| broker_recommend | True | True | implement_from_registered_contract | 1 | 0 |
| broker_statement_cashflows | True | True | owner_confirmation_required | 1 | 0 |
| broker_statement_deliveries | True | True | owner_confirmation_required | 1 | 0 |
| cashflow | True | True | implement_from_registered_contract | 1 | 0 |
| cashflow_period_meta | True | True | owner_confirmation_required | 1 | 0 |
| cn_bond_yield_curve | True | True | implement_from_registered_contract | 1 | 0 |
| cn_cpi | True | True | implement_from_registered_contract | 1 | 0 |
| cn_gdp | True | True | implement_from_registered_contract | 1 | 0 |
| cn_m | True | True | implement_from_registered_contract | 1 | 0 |
| cn_pmi | True | True | implement_from_registered_contract | 1 | 0 |
| cn_ppi | True | True | implement_from_registered_contract | 1 | 0 |
| company_intrinsic_valuation_consensus | True | True | owner_confirmation_required | 1 | 0 |
| company_intrinsic_valuation_model | True | True | owner_confirmation_required | 1 | 0 |
| cyq_chips | True | True | implement_from_registered_contract | 1 | 0 |
| cyq_perf | True | True | implement_from_registered_contract | 1 | 0 |
| daily | True | True | implement_from_registered_contract | 1 | 0 |
| daily_basic | True | True | implement_from_registered_contract | 1 | 0 |
| daily_stock_analysis | True | True | owner_confirmation_required | 1 | 0 |
| dc_index | True | True | implement_from_registered_contract | 1 | 0 |
| disclosure_date | True | True | implement_from_registered_contract | 1 | 0 |
| dividend | True | True | implement_from_registered_contract | 1 | 0 |
| eco_cal | True | True | implement_from_registered_contract | 1 | 0 |
| equity_style_monthly | True | True | implement_from_registered_contract | 1 | 0 |
| etf_adj | True | True | implement_from_registered_contract | 1 | 0 |
| etf_basic | True | True | implement_from_registered_contract | 1 | 0 |
| etf_daily | True | True | implement_from_registered_contract | 1 | 0 |
| etf_factor | True | True | implement_from_registered_contract | 1 | 0 |
| etf_market_overview_daily_cache | True | True | implement_from_registered_contract | 1 | 0 |
| etf_portfolio | True | True | implement_from_registered_contract | 1 | 0 |
| etf_share | True | True | implement_from_registered_contract | 1 | 0 |
| exchange_calendar | True | True | implement_from_registered_contract | 1 | 0 |
| express | True | True | implement_from_registered_contract | 1 | 0 |
| express_period_meta | True | True | owner_confirmation_required | 1 | 0 |
| factor_catalog | True | True | owner_confirmation_required | 1 | 0 |
| factor_corr_snapshot | True | True | implement_from_registered_contract | 1 | 0 |
| factor_daily_metric | True | True | implement_from_registered_contract | 1 | 0 |
| factor_definition | True | True | implement_from_registered_contract | 1 | 0 |
| factor_dependency | True | True | implement_from_registered_contract | 1 | 0 |
| factor_ic_daily | True | True | implement_from_registered_contract | 1 | 0 |
| factor_materialization_run | True | True | implement_from_registered_contract | 1 | 0 |
| factor_monitor_daily | True | True | implement_from_registered_contract | 1 | 0 |
| factor_monitor_summary | True | True | implement_from_registered_contract | 1 | 0 |
| factor_observation | True | True | implement_from_registered_contract | 1 | 0 |
| factor_platform_run | True | True | implement_from_registered_contract | 1 | 0 |
| factor_regime_daily | True | True | implement_from_registered_contract | 1 | 0 |
| factor_registry | True | True | implement_from_registered_contract | 1 | 0 |
| factor_usage | True | True | implement_from_registered_contract | 1 | 0 |
| factor_validation_daily | True | True | implement_from_registered_contract | 1 | 0 |
| fina_audit | True | True | implement_from_registered_contract | 1 | 0 |
| fina_indicator | True | True | implement_from_registered_contract | 1 | 0 |
| fina_indicator_period_meta | True | True | owner_confirmation_required | 1 | 0 |
| fina_mainbz | True | True | implement_from_registered_contract | 1 | 0 |
| forecast | True | True | implement_from_registered_contract | 1 | 0 |
| forecast_period_meta | True | True | owner_confirmation_required | 1 | 0 |
| ft_limit | True | True | implement_from_registered_contract | 1 | 0 |
| fut_basic | True | True | implement_from_registered_contract | 1 | 0 |
| fut_daily | True | True | implement_from_registered_contract | 1 | 0 |
| fut_holding | True | True | implement_from_registered_contract | 1 | 0 |
| fut_index_conclusion_daily | True | True | implement_from_registered_contract | 1 | 0 |
| fut_index_daily_snapshot | True | True | implement_from_registered_contract | 1 | 0 |
| fut_index_signal_daily | True | True | implement_from_registered_contract | 1 | 0 |
| fut_mapping | True | True | implement_from_registered_contract | 1 | 0 |
| fut_settle | True | True | implement_from_registered_contract | 1 | 0 |
| hibor | True | True | implement_from_registered_contract | 1 | 0 |
| income | True | True | implement_from_registered_contract | 1 | 0 |
| income_period_meta | True | True | owner_confirmation_required | 1 | 0 |
| index | True | True | owner_confirmation_required | 1 | 0 |
| index_daily_basic | True | True | implement_from_registered_contract | 1 | 0 |
| index_daily_market | True | True | implement_from_registered_contract | 1 | 0 |
| index_member | True | True | implement_from_registered_contract | 1 | 0 |
| index_monthly | True | True | implement_from_registered_contract | 1 | 0 |
| index_weight | True | True | implement_from_registered_contract | 1 | 0 |
| industry_intrinsic_valuation_state | True | True | owner_confirmation_required | 1 | 0 |
| industry_valuation_materialization_run | True | True | owner_confirmation_required | 1 | 0 |
| industry_valuation_membership_conflict | True | True | owner_confirmation_required | 1 | 0 |
| industry_valuation_route_assignment | True | True | owner_confirmation_required | 1 | 0 |
| l2_daily_features | True | True | implement_from_registered_contract | 1 | 0 |
| l2_dataset_manifest | True | True | owner_confirmation_required | 1 | 0 |
| l2_event_response_features | True | True | owner_confirmation_required | 1 | 0 |
| l2_intraday_bar_features | True | True | owner_confirmation_required | 1 | 0 |
| l2_live_gateway_stats | True | True | owner_confirmation_required | 1 | 0 |
| l2_live_micro_state_1m | True | True | owner_confirmation_required | 1 | 0 |
| l2_live_normalized_events | True | True | implement_from_registered_contract | 1 | 0 |
| l2_live_subscription_events | True | True | owner_confirmation_required | 1 | 0 |
| l2_live_subscription_intents | True | True | owner_confirmation_required | 1 | 0 |
| l2_t0_training_labels | True | True | owner_confirmation_required | 1 | 0 |
| macro_core_monthly | True | True | implement_from_registered_contract | 1 | 0 |
| macro_liquidity_credit_monthly | True | True | implement_from_registered_contract | 1 | 0 |
| margin_all | True | True | implement_from_registered_contract | 1 | 0 |
| margin_detail | True | True | implement_from_registered_contract | 1 | 0 |
| margin_secs | True | True | implement_from_registered_contract | 1 | 0 |
| margin_trading | True | True | implement_from_registered_contract | 1 | 0 |
| margin_zrz | True | True | implement_from_registered_contract | 1 | 0 |
| market_barometer_cache_coverage | True | True | implement_from_registered_contract | 1 | 0 |
| market_breadth_daily_cache | True | True | retirement_candidate | 1 | 0 |
| market_breadth_monthly | True | True | implement_from_registered_contract | 1 | 0 |
| market_sentiment_daily | True | True | owner_confirmation_required | 1 | 0 |
| moneyflow | True | True | implement_from_registered_contract | 1 | 0 |
| moneyflow_dc | True | True | implement_from_registered_contract | 1 | 0 |
| moneyflow_hsgt | True | True | implement_from_registered_contract | 1 | 0 |
| moneyflow_ths | True | True | implement_from_registered_contract | 1 | 0 |
| mv_market_breadth_daily_v1 | True | True | owner_confirmation_required | 1 | 0 |
| mv_retail_sentiment_daily_v1 | True | True | owner_confirmation_required | 1 | 0 |
| order_fills | True | True | owner_confirmation_required | 0 | 0 |
| order_records | True | True | owner_confirmation_required | 1 | 0 |
| orders | True | True | owner_confirmation_required | 0 | 0 |
| paired_execution_plans | True | True | owner_confirmation_required | 1 | 0 |
| pledge_stat | True | True | implement_from_registered_contract | 1 | 0 |
| prediction_definition_v1 | True | True | implement_from_registered_contract | 1 | 0 |
| qmt_1m_bars | True | True | owner_confirmation_required | 1 | 0 |
| qmt_assets | True | True | owner_confirmation_required | 1 | 0 |
| qmt_orders | True | True | owner_confirmation_required | 1 | 0 |
| qmt_positions | True | True | owner_confirmation_required | 1 | 0 |
| qmt_tick_data | True | True | owner_confirmation_required | 1 | 0 |
| qmt_trades | True | True | owner_confirmation_required | 1 | 0 |
| regime_display_materialization_run | True | True | owner_confirmation_required | 1 | 0 |
| regime_erp_source_status_daily | True | True | owner_confirmation_required | 1 | 0 |
| regime_features_monitor_daily | True | True | implement_from_registered_contract | 1 | 0 |
| regime_features_monthly | True | True | implement_from_registered_contract | 1 | 0 |
| regime_ma200_breadth_daily | True | True | owner_confirmation_required | 1 | 0 |
| regime_margin_leverage_daily | True | True | owner_confirmation_required | 1 | 0 |
| regime_market_monthly | True | True | implement_from_registered_contract | 1 | 0 |
| regime_stock_return_distribution_daily | True | True | owner_confirmation_required | 1 | 0 |
| repurchase | True | True | implement_from_registered_contract | 1 | 0 |
| retail_sentiment_daily_cache | True | True | retirement_candidate | 1 | 0 |
| rmb_index_daily | True | True | implement_from_registered_contract | 1 | 0 |
| sf_month | True | True | implement_from_registered_contract | 1 | 0 |
| sge_daily | True | True | implement_from_registered_contract | 1 | 0 |
| share_float | True | True | implement_from_registered_contract | 1 | 0 |
| shibor | True | True | implement_from_registered_contract | 1 | 0 |
| shibor_lpr | True | True | implement_from_registered_contract | 1 | 0 |
| stk_alert | True | True | implement_from_registered_contract | 1 | 0 |
| stk_factor | True | True | implement_from_registered_contract | 1 | 0 |
| stk_high_shock | True | True | implement_from_registered_contract | 1 | 0 |
| stk_holdernumber | True | True | implement_from_registered_contract | 1 | 0 |
| stk_holdertrade | True | True | implement_from_registered_contract | 1 | 0 |
| stk_limit | True | True | implement_from_registered_contract | 1 | 0 |
| stk_shock | True | True | implement_from_registered_contract | 1 | 0 |
| stk_st_daily | True | True | implement_from_registered_contract | 1 | 0 |
| stk_suspend | True | True | implement_from_registered_contract | 1 | 0 |
| stock_detail_info | True | True | implement_from_registered_contract | 1 | 0 |
| stock_minute_bars | True | True | implement_from_registered_contract | 1 | 0 |
| strategy_backtest_equity_daily | True | True | owner_confirmation_required | 1 | 0 |
| strategy_backtest_positions | True | True | owner_confirmation_required | 1 | 0 |
| strategy_backtest_runs | True | True | owner_confirmation_required | 1 | 0 |
| strategy_backtest_trades | True | True | owner_confirmation_required | 1 | 0 |
| strategy_daily_runs | True | True | owner_confirmation_required | 1 | 0 |
| strategy_instances | True | True | owner_confirmation_required | 1 | 0 |
| strategy_production_equity_daily | True | True | owner_confirmation_required | 1 | 0 |
| strategy_production_orders | True | True | owner_confirmation_required | 1 | 0 |
| strategy_production_pnl_attribution_daily | True | True | owner_confirmation_required | 1 | 0 |
| strategy_production_positions | True | True | owner_confirmation_required | 1 | 0 |
| strategy_production_runs | True | True | owner_confirmation_required | 1 | 0 |
| strategy_production_state_daily | True | True | owner_confirmation_required | 1 | 0 |
| strategy_recommended_orders | True | True | owner_confirmation_required | 1 | 0 |
| strategy_run_artifact_manifest | True | True | owner_confirmation_required | 1 | 0 |
| strategy_target_positions | True | True | owner_confirmation_required | 1 | 0 |
| strategy_templates | True | True | owner_confirmation_required | 1 | 0 |
| ths_index | True | True | implement_from_registered_contract | 1 | 0 |
| ths_member | True | True | implement_from_registered_contract | 1 | 0 |
| tracked_symbols | True | True | owner_confirmation_required | 1 | 0 |
| trade_records | True | True | owner_confirmation_required | 1 | 0 |
| trade_signals | True | True | owner_confirmation_required | 1 | 0 |
| us_market_daily | True | True | implement_from_registered_contract | 1 | 0 |
| us_tbr | True | True | implement_from_registered_contract | 1 | 0 |
| us_tltr | True | True | implement_from_registered_contract | 1 | 0 |
| us_trltr | True | True | implement_from_registered_contract | 1 | 0 |
| us_trycr | True | True | implement_from_registered_contract | 1 | 0 |
| us_tycr | True | True | implement_from_registered_contract | 1 | 0 |
| v_backtest_daily | True | True | compatibility_interface | 1 | 0 |
| v_etf_market_overview_daily | True | True | compatibility_interface | 1 | 0 |
| v_macro_core_monthly | True | True | compatibility_interface | 1 | 0 |
| v_macro_liquidity_credit_monthly | True | True | compatibility_interface | 1 | 0 |
| v_market_breadth_daily | True | True | compatibility_interface | 1 | 0 |
| v_market_breadth_monthly | True | True | compatibility_interface | 1 | 0 |
| v_regime_features_monitor_daily | True | True | compatibility_interface | 1 | 0 |
| v_regime_features_monthly | True | True | compatibility_interface | 1 | 0 |
| v_regime_market_monthly | True | True | compatibility_interface | 1 | 0 |
| v_retail_sentiment_daily | True | True | compatibility_interface | 1 | 0 |

## 原缺失模型

- alpha_research_admission_v1：当前存在=False；conditional_owner_review
- alpha_source_definition_v1：当前存在=False；conditional_owner_review
- alpha_source_member_v1：当前存在=False；conditional_owner_review
- factor_lifecycle_event：当前存在=False；conditional_owner_review
- prediction_observation_v1：当前存在=False；conditional_owner_review
- index_daily：当前存在=False；conditional_owner_review
- macro_bond_yield：当前存在=False；conditional_owner_review
- eco_cal_daily_agg：当前存在=False；conditional_owner_review
- eco_cal_quantified：当前存在=False；conditional_owner_review
- factor_exposure_snapshot_daily：当前存在=False；conditional_owner_review
- macro_news：当前存在=False；conditional_owner_review
- stock_news：当前存在=False；conditional_owner_review
- report_rc：当前存在=False；conditional_owner_review
- swan_industry：当前存在=False；conditional_owner_review
