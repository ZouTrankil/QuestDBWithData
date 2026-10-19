package com.zoutrankil.data.config;

import com.zoutrankil.data.l2.application.*;
import com.zoutrankil.data.l2.port.*;
import com.zoutrankil.data.l2.storage.*;
import io.questdb.client.QuestDB;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds inspected L2 sources and fresh physical write sessions with the existing configuration. */
@Configuration(proxyBeanMethods = false)
public class L2Configuration {
    @Bean L2DatasetManifestTarget l2DatasetManifestTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties properties, @Value("${app.sync.l2-manifest.target-table:}") String table) {
        return new QuestDbL2DatasetManifestTarget(table, jdbc, questdb, properties);
    }
    @Bean L2DatasetManifestParquetSource l2DatasetManifestParquetSource(
            @Value("${app.sync.l2-manifest.dataset-root:F:/repo/engine/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot) {
        return new L2DatasetManifestParquetSource(Path.of(datasetRoot));
    }
    @Bean L2DailyFeaturesTarget l2DailyFeaturesTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties properties, @Value("${app.sync.l2-daily-features.target-table:}") String table) {
        return new QuestDbL2DailyFeaturesTarget(table, jdbc, questdb, properties);
    }
    @Bean L2DailyFeaturesParquetSource l2DailyFeaturesParquetSource(
            @Value("${app.sync.l2-daily-features.dataset-root:F:/repo/engine/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot) {
        return new L2DailyFeaturesParquetSource(Path.of(datasetRoot));
    }
    @Bean L2IntradayBarFeaturesTarget l2IntradayBarFeaturesTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties properties, @Value("${app.sync.l2-intraday-bar-features.target-table:}") String table) {
        return new QuestDbL2IntradayBarFeaturesTarget(table, jdbc, questdb, properties);
    }
    @Bean L2IntradayBarFeaturesParquetSource l2IntradayBarFeaturesParquetSource(
            @Value("${app.sync.l2-intraday-bar-features.dataset-root:F:/repo/engine/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot) {
        return new L2IntradayBarFeaturesParquetSource(Path.of(datasetRoot));
    }
    @Bean L2EventResponseFeaturesTarget l2EventResponseFeaturesTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties properties, @Value("${app.sync.l2-event-response-features.target-table:}") String table) {
        return new QuestDbL2EventResponseFeaturesTarget(table, jdbc, questdb, properties);
    }
    @Bean L2EventResponseFeaturesParquetSource l2EventResponseFeaturesParquetSource(
            @Value("${app.sync.l2-event-response-features.dataset-root:F:/repo/engine/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot) {
        return new L2EventResponseFeaturesParquetSource(Path.of(datasetRoot));
    }
    @Bean L2T0TrainingLabelsTarget l2T0TrainingLabelsTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties properties, @Value("${app.sync.l2-t0-training-labels.target-table:}") String table) {
        return new QuestDbL2T0TrainingLabelsTarget(table, jdbc, questdb, properties);
    }
    @Bean L2T0TrainingLabelsParquetSource l2T0TrainingLabelsParquetSource(
            @Value("${app.sync.l2-t0-training-labels.dataset-root:F:/repo/engine/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot) {
        return new L2T0TrainingLabelsParquetSource(Path.of(datasetRoot));
    }
}
