package com.zoutrankil.data.config;

import com.zoutrankil.data.l2.application.FormalL2DailyFeaturesIngestion;
import com.zoutrankil.data.l2.application.L2DailyFeaturesParquetSource;
import com.zoutrankil.data.l2.storage.L2DailyFeaturesWritePort;
import com.zoutrankil.data.l2.storage.PersistentFormalL2IntervalAuthority;
import com.zoutrankil.data.l2.storage.QuestDbFormalL2FrontierReader;

import io.questdb.client.QuestDB;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

/** Formal source/write/frontier/lease composition; bean assembly has no storage effects. */
@Configuration(proxyBeanMethods = false)
public class FormalL2DailyFeaturesConfiguration {
    @Bean
    FormalL2DailyFeaturesIngestion formalL2DailyFeaturesIngestion(
            L2DailyFeaturesParquetSource source,
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            QuestDbProperties properties,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new FormalL2DailyFeaturesIngestion(
                source,
                L2DailyFeaturesWritePort.formal(jdbc, questdb),
                new QuestDbFormalL2FrontierReader(jdbc, properties),
                new PersistentFormalL2IntervalAuthority(
                        Path.of(ledger).toAbsolutePath().normalize()));
    }
}
