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
            } else if (definition.capabilities().contains(DatasetDefinition.Capability.READ))
                bindings.add(new ReadGroupReader.Binding<>(definition, DatasetValues.class,
                        java.util.function.Function.identity(), () -> null));
        }
        return new ReadGroupReader(datasets, reader, bindings);
    }

}
