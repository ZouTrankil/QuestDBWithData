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
            @Value("${app.sync.l2-manifest.dataset-root:D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot,
            @Value("${app.sync.l2-manifest.python-executable:D:/work/fund_2/back-monitor/.venv/Scripts/python.exe}") String pythonExecutable,
            @Value("${app.sync.l2-manifest.reader-script:tools/read_l2_dataset_manifest.py}") String readerScript) {
        return new L2DatasetManifestParquetSource(Path.of(datasetRoot), Path.of(readerScript), pythonExecutable);
    }
    @Bean L2DailyFeaturesTarget l2DailyFeaturesTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties properties, @Value("${app.sync.l2-daily-features.target-table:}") String table) {
        return new QuestDbL2DailyFeaturesTarget(table, jdbc, questdb, properties);
    }
    @Bean L2DailyFeaturesParquetSource l2DailyFeaturesParquetSource(
            @Value("${app.sync.l2-daily-features.dataset-root:D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot,
            @Value("${app.sync.l2-daily-features.python-executable:D:/work/fund_2/back-monitor/.venv/Scripts/python.exe}") String pythonExecutable,
            @Value("${app.sync.l2-daily-features.reader-script:tools/read_l2_daily_features.py}") String readerScript) {
        return new L2DailyFeaturesParquetSource(Path.of(datasetRoot), Path.of(readerScript), pythonExecutable);
    }
    @Bean L2IntradayBarFeaturesTarget l2IntradayBarFeaturesTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties properties, @Value("${app.sync.l2-intraday-bar-features.target-table:}") String table) {
        return new QuestDbL2IntradayBarFeaturesTarget(table, jdbc, questdb, properties);
    }
    @Bean L2IntradayBarFeaturesParquetSource l2IntradayBarFeaturesParquetSource(
            @Value("${app.sync.l2-intraday-bar-features.dataset-root:D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot,
            @Value("${app.sync.l2-intraday-bar-features.python-executable:D:/work/fund_2/back-monitor/.venv/Scripts/python.exe}") String pythonExecutable,
            @Value("${app.sync.l2-intraday-bar-features.reader-script:tools/read_l2_intraday_bar_features.py}") String readerScript) {
        return new L2IntradayBarFeaturesParquetSource(Path.of(datasetRoot), Path.of(readerScript), pythonExecutable);
    }
    @Bean L2EventResponseFeaturesTarget l2EventResponseFeaturesTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties properties, @Value("${app.sync.l2-event-response-features.target-table:}") String table) {
        return new QuestDbL2EventResponseFeaturesTarget(table, jdbc, questdb, properties);
    }
    @Bean L2EventResponseFeaturesParquetSource l2EventResponseFeaturesParquetSource(
            @Value("${app.sync.l2-event-response-features.dataset-root:D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot,
            @Value("${app.sync.l2-event-response-features.python-executable:D:/work/fund_2/back-monitor/.venv/Scripts/python.exe}") String pythonExecutable,
            @Value("${app.sync.l2-event-response-features.reader-script:tools/read_l2_event_response_features.py}") String readerScript) {
        return new L2EventResponseFeaturesParquetSource(Path.of(datasetRoot), Path.of(readerScript), pythonExecutable);
    }
    @Bean L2T0TrainingLabelsTarget l2T0TrainingLabelsTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties properties, @Value("${app.sync.l2-t0-training-labels.target-table:}") String table) {
        return new QuestDbL2T0TrainingLabelsTarget(table, jdbc, questdb, properties);
    }
    @Bean L2T0TrainingLabelsParquetSource l2T0TrainingLabelsParquetSource(
            @Value("${app.sync.l2-t0-training-labels.dataset-root:D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot,
            @Value("${app.sync.l2-t0-training-labels.python-executable:D:/work/fund_2/back-monitor/.venv/Scripts/python.exe}") String pythonExecutable,
            @Value("${app.sync.l2-t0-training-labels.reader-script:tools/read_l2_t0_training_labels.py}") String readerScript) {
        return new L2T0TrainingLabelsParquetSource(Path.of(datasetRoot), Path.of(readerScript), pythonExecutable);
    }
}
