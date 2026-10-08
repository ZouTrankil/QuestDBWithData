package com.zoutrankil.data.config;

import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.l2.mapper.L2DatasetManifestMapper;
import com.zoutrankil.data.l2.mapper.L2IntradayBarFeaturesMapper;
import com.zoutrankil.data.l2.mapper.L2T0TrainingLabelsMapper;

import com.zoutrankil.data.derived.mapper.BacktestDailyCacheCoverageMapper;
import com.zoutrankil.data.derived.mapper.BacktestDailyCacheMapper;
import com.zoutrankil.data.derived.mapper.BacktestDailyMapper;
import com.zoutrankil.data.derived.mapper.BacktestDailyViewMapper;
import com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper;
import com.zoutrankil.data.derived.mapper.EtfMarketOverviewDailyCacheMapper;
import com.zoutrankil.data.derived.mapper.EtfMarketOverviewDailyViewMapper;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyMapper;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyViewMapper;
import com.zoutrankil.data.derived.mapper.MarketBarometerCacheCoverageMapper;
import com.zoutrankil.data.derived.mapper.MarketBreadthDailyCacheMapper;
import com.zoutrankil.data.derived.mapper.MarketBreadthDailyV1Mapper;
import com.zoutrankil.data.derived.mapper.MarketBreadthDailyViewMapper;
import com.zoutrankil.data.derived.mapper.RetailSentimentDailyCacheMapper;
import com.zoutrankil.data.derived.mapper.RetailSentimentDailyV1Mapper;
import com.zoutrankil.data.derived.mapper.RetailSentimentDailyViewMapper;

import com.zoutrankil.data.domain.*;

import com.zoutrankil.data.service.ReadBindingCatalog;
import java.util.List;

import static com.zoutrankil.data.service.ReadBindingCatalog.generic;
import static com.zoutrankil.data.service.ReadBindingCatalog.typed;

/** Reviewed public row representations; a new dataset needs its own explicit registration. */
final class DefaultReadBindings {
    private DefaultReadBindings() {}

    // All twenty typed mappings preserve the pre-registry read-group contract. These mappers are stateless.
    private static final List<ReadBindingCatalog.Registration<?>> REGISTRATIONS = List.of(
            typed("l2_dataset_manifest", 1, L2DatasetManifest.class, new L2DatasetManifestMapper()::fromValues),
            typed("l2_daily_features", 1, L2DailyFeatures.class, new L2DailyFeaturesMapper()::fromValues),
            typed("l2_intraday_bar_features", 1, L2IntradayBarFeatures.class, new L2IntradayBarFeaturesMapper()::fromValues),
            typed("l2_t0_training_labels", 1, L2T0TrainingLabels.class, new L2T0TrainingLabelsMapper()::fromValues),
            typed("backtest_daily", 1, BacktestDaily.class, new BacktestDailyMapper()::fromValues),
            typed("backtest_daily_cache_coverage", 1, BacktestDailyCacheCoverage.class, new BacktestDailyCacheCoverageMapper()::fromValues),
            typed("backtest_daily_cache", 1, BacktestDailyCache.class, new BacktestDailyCacheMapper()::fromValues),
            typed("v_backtest_daily", 1, BacktestDailyViewValue.class, new BacktestDailyViewMapper()::fromValues),
            typed("market_barometer_cache_coverage", 1, MarketBarometerCacheCoverage.class, new MarketBarometerCacheCoverageMapper()::fromValues),
            typed("v_macro_core_monthly", 1, MacroCoreMonthlyView.class, new MacroCoreMonthlyViewMapper()::fromValues),
            typed("macro_core_monthly", 1, MacroCoreMonthly.class, new MacroCoreMonthlyMapper()::fromValues),
            typed("equity_style_monthly", 1, EquityStyleMonthly.class, new EquityStyleMonthlyMapper()::fromValues),
            typed("v_etf_market_overview_daily", 1, EtfMarketOverviewDailyView.class, new EtfMarketOverviewDailyViewMapper()::fromValues),
            typed("etf_market_overview_daily_cache", 1, EtfMarketOverviewDailyCache.class, new EtfMarketOverviewDailyCacheMapper()::fromValues),
            typed("retail_sentiment_daily_cache", 1, RetailSentimentDailyCache.class, new RetailSentimentDailyCacheMapper()::fromValues),
            typed("v_retail_sentiment_daily", 1, RetailSentimentDailyView.class, new RetailSentimentDailyViewMapper()::fromValues),
            typed("mv_retail_sentiment_daily_v1", 1, RetailSentimentDailyV1.class, new RetailSentimentDailyV1Mapper()::fromValues),
            typed("mv_market_breadth_daily_v1", 1, MarketBreadthDailyV1.class, new MarketBreadthDailyV1Mapper()::fromValues),
            typed("market_breadth_daily_cache", 1, MarketBreadthDailyCache.class, new MarketBreadthDailyCacheMapper()::fromValues),
            typed("v_market_breadth_daily", 1, MarketBreadthDailyView.class, new MarketBreadthDailyViewMapper()::fromValues),

            // Explicit generic contracts from the registered repository definitions, not a catch-all fallback.
            generic("daily", 1),
            generic("daily_basic", 1),
            generic("dc_index", 1),
            generic("etf_adj", 1),
            generic("etf_basic", 1),
            generic("etf_daily", 1),
            generic("etf_factor", 1),
            generic("etf_portfolio", 1),
            generic("etf_share", 1),
            generic("exchange_calendar", 1),
            generic("index", 1),
            generic("index_daily_basic", 1),
            generic("index_daily_market", 1),
            generic("index_member", 1),
            generic("index_monthly", 1),
            generic("index_weight", 1),
            // A typed repository exists, but the public read-group contract has always been DatasetValues.
            generic("l2_event_response_features", 1),
            generic("margin_all", 1),
            generic("margin_detail", 1),
            generic("margin_secs", 1),
            generic("margin_zrz", 1),
            generic("moneyflow", 1),
            generic("moneyflow_dc", 1),
            generic("moneyflow_hsgt", 1),
            generic("moneyflow_ths", 1),
            generic("stk_factor", 1),
            generic("stk_limit", 1),
            generic("stk_st_daily", 1),
            generic("stk_suspend", 1),
            generic("stock_basic_latest", 1),
            generic("stock_basic_snapshot", 1),
            generic("stock_detail_info", 1),
            generic("ths_index", 1),
            generic("ths_member", 1),
            generic("market_sentiment_daily", 1),
            generic("regime_features_monitor_daily", 1));

    static List<ReadBindingCatalog.Registration<?>> registrations() { return REGISTRATIONS; }
}
