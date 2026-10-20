package com.zoutrankil.data.config;

import static com.zoutrankil.data.service.ReadBindingCatalog.generic;
import static com.zoutrankil.data.service.ReadBindingCatalog.typed;

import com.zoutrankil.data.derived.mapper.BacktestDailyViewMapper;
import com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper;
import com.zoutrankil.data.derived.mapper.EtfMarketOverviewDailyViewMapper;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyMapper;
import com.zoutrankil.data.derived.mapper.MarketBreadthDailyMapper;
import com.zoutrankil.data.derived.mapper.RetailSentimentDailyMapper;
import com.zoutrankil.data.domain.derived.BacktestDailyViewValue;
import com.zoutrankil.data.domain.derived.EquityStyleMonthly;
import com.zoutrankil.data.domain.derived.MacroCoreMonthly;
import com.zoutrankil.data.domain.derived.MarketBreadthDaily;
import com.zoutrankil.data.domain.derived.RetailSentimentDaily;
import com.zoutrankil.data.domain.fund.EtfMarketOverviewDailyView;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DatasetManifest;
import com.zoutrankil.data.domain.l2.L2IntradayBarFeatures;
import com.zoutrankil.data.domain.l2.L2T0TrainingLabels;
import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.l2.mapper.L2DatasetManifestMapper;
import com.zoutrankil.data.l2.mapper.L2IntradayBarFeaturesMapper;
import com.zoutrankil.data.l2.mapper.L2T0TrainingLabelsMapper;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.List;

/** Reviewed public row representations; a new dataset needs its own explicit registration. */
final class DefaultReadBindings {
    private DefaultReadBindings() {}

    // Active typed mappings use the native derived views; the retired cache adapters are absent.
    private static final List<ReadBindingCatalog.Registration<?>> REGISTRATIONS =
            List.of(
                    generic("stk_shock", 2),
                    generic("stk_high_shock", 2),
                    generic("shibor", 2),
                    generic("hibor", 2),
                    generic("shibor_lpr", 2),
                    typed(
                            "l2_dataset_manifest",
                            1,
                            L2DatasetManifest.class,
                            new L2DatasetManifestMapper()::fromValues),
                    typed(
                            "l2_daily_features",
                            1,
                            L2DailyFeatures.class,
                            new L2DailyFeaturesMapper()::fromValues),
                    typed(
                            "l2_intraday_bar_features",
                            1,
                            L2IntradayBarFeatures.class,
                            new L2IntradayBarFeaturesMapper()::fromValues),
                    typed(
                            "l2_t0_training_labels",
                            1,
                            L2T0TrainingLabels.class,
                            new L2T0TrainingLabelsMapper()::fromValues),
                    typed(
                            "mv_backtest_daily",
                            1,
                            BacktestDailyViewValue.class,
                            new BacktestDailyViewMapper()::fromValues),
                    typed(
                            "macro_core_monthly",
                            1,
                            MacroCoreMonthly.class,
                            new MacroCoreMonthlyMapper()::fromValues),
                    typed(
                            "equity_style_monthly",
                            1,
                            EquityStyleMonthly.class,
                            new EquityStyleMonthlyMapper()::fromValues),
                    typed(
                            "mv_etf_market_overview_daily",
                            1,
                            EtfMarketOverviewDailyView.class,
                            new EtfMarketOverviewDailyViewMapper()::fromValues),
                    typed(
                            "mv_retail_sentiment_daily",
                            1,
                            RetailSentimentDaily.class,
                            new RetailSentimentDailyMapper()::fromValues),
                    typed(
                            "mv_market_breadth_daily",
                            1,
                            MarketBreadthDaily.class,
                            new MarketBreadthDailyMapper()::fromValues),

                    // Explicit generic contracts from the registered repository definitions, not a
                    // catch-all fallback.
                    generic("cn_bond_yield_curve", 1),
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
                    // A typed repository exists, but the public read-group contract has always been
                    // DatasetValues.
                    generic("l2_event_response_features", 1),
                    generic("margin_all", 1),
                    generic("margin_detail", 1),
                    generic("margin_secs", 1),
                    generic("moneyflow", 1),
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

    static List<ReadBindingCatalog.Registration<?>> registrations(int etfAdjVersion) {
        return registrations(etfAdjVersion, 1);
    }

    static List<ReadBindingCatalog.Registration<?>> registrations(
            int etfAdjVersion, int etfDailyVersion) {
        return registrations(etfAdjVersion, etfDailyVersion, 1);
    }

    static List<ReadBindingCatalog.Registration<?>> registrations(
            int etfAdjVersion, int etfDailyVersion, int etfShareVersion) {
        int shareVersion = CnTradeDateLayout.fromVersion(etfShareVersion).schemaVersion();
        int dailyVersion = CnTradeDateLayout.fromVersion(etfDailyVersion).schemaVersion();
        int version = CnTradeDateLayout.fromVersion(etfAdjVersion).schemaVersion();
        return REGISTRATIONS.stream()
                .<ReadBindingCatalog.Registration<?>>map(
                        registration ->
                                switch (registration.datasetId()) {
                                    case "etf_adj" -> generic("etf_adj", version);
                                    case "etf_daily" -> generic("etf_daily", dailyVersion);
                                    case "etf_share" -> generic("etf_share", shareVersion);
                                    default -> registration;
                                })
                .toList();
    }

    static List<ReadBindingCatalog.Registration<?>> registrations() {
        return REGISTRATIONS;
    }
}
