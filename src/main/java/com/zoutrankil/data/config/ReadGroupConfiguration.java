package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadGroupReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/** Only registered read definitions are exposed; projections retain strict Java field types. */
@Configuration
public class ReadGroupConfiguration {
    @Bean
    DatasetImplementation stockBasicLatestDataset() {
        return () -> StockBasicDataset.LATEST;
    }

    @Bean
    ReadGroupReader readGroupReader(DatasetRegistry datasets, QuestDbBoundedReader reader) {
        var bindings = new java.util.ArrayList<ReadGroupReader.Binding<?>>();
        var l2ManifestMapper = new com.zoutrankil.data.mapper.L2DatasetManifestMapper();
        for (var definition : datasets.definitions()) {
            if (definition.datasetId().equals("l2_dataset_manifest")) {
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.L2DatasetManifest.class,
                        l2ManifestMapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("l2_daily_features")) {
                var mapper = new com.zoutrankil.data.mapper.L2DailyFeaturesMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.L2DailyFeatures.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("l2_intraday_bar_features")) {
                var mapper = new com.zoutrankil.data.mapper.L2IntradayBarFeaturesMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.L2IntradayBarFeatures.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("l2_t0_training_labels")) {
                var mapper = new com.zoutrankil.data.mapper.L2T0TrainingLabelsMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.L2T0TrainingLabels.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("backtest_daily")) {
                var mapper = new com.zoutrankil.data.mapper.BacktestDailyMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.BacktestDaily.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("backtest_daily_cache_coverage")) {
                var mapper = new com.zoutrankil.data.mapper.BacktestDailyCacheCoverageMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.BacktestDailyCacheCoverage.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("backtest_daily_cache")) {
                var mapper = new com.zoutrankil.data.mapper.BacktestDailyCacheMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.BacktestDailyCache.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("v_backtest_daily")) {
                var mapper = new com.zoutrankil.data.mapper.BacktestDailyViewMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.BacktestDailyViewValue.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("market_barometer_cache_coverage")) {
                var mapper = new com.zoutrankil.data.mapper.MarketBarometerCacheCoverageMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.MarketBarometerCacheCoverage.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("equity_style_monthly")) {
                var mapper = new com.zoutrankil.data.mapper.EquityStyleMonthlyMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.EquityStyleMonthly.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("v_etf_market_overview_daily")) {
                var mapper = new com.zoutrankil.data.mapper.EtfMarketOverviewDailyViewMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.EtfMarketOverviewDailyView.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("etf_market_overview_daily_cache")) {
                var mapper = new com.zoutrankil.data.mapper.EtfMarketOverviewDailyCacheMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.EtfMarketOverviewDailyCache.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("retail_sentiment_daily_cache")) {
                var mapper = new com.zoutrankil.data.mapper.RetailSentimentDailyCacheMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.RetailSentimentDailyCache.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("v_retail_sentiment_daily")) {
                var mapper = new com.zoutrankil.data.mapper.RetailSentimentDailyViewMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.RetailSentimentDailyView.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("mv_retail_sentiment_daily_v1")) {
                var mapper = new com.zoutrankil.data.mapper.RetailSentimentDailyV1Mapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.RetailSentimentDailyV1.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("mv_market_breadth_daily_v1")) {
                var mapper = new com.zoutrankil.data.mapper.MarketBreadthDailyV1Mapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.MarketBreadthDailyV1.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("market_breadth_daily_cache")) {
                var mapper = new com.zoutrankil.data.mapper.MarketBreadthDailyCacheMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.MarketBreadthDailyCache.class,
                        mapper::fromValues, () -> null));
            } else if (definition.datasetId().equals("v_market_breadth_daily")) {
                var mapper = new com.zoutrankil.data.mapper.MarketBreadthDailyViewMapper();
                bindings.add(new ReadGroupReader.Binding<>(definition,
                        com.zoutrankil.data.domain.MarketBreadthDailyView.class,
                        mapper::fromValues, () -> null));
            } else if (definition.capabilities().contains(DatasetDefinition.Capability.READ))
                bindings.add(new ReadGroupReader.Binding<>(definition, DatasetValues.class,
                        java.util.function.Function.identity(), () -> null));
        }
        return new ReadGroupReader(datasets, reader, bindings);
    }

}
