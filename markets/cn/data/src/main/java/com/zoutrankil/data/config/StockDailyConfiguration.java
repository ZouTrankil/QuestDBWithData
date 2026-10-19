package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.StockBasicDataset;
import com.zoutrankil.data.stock.port.*;
import com.zoutrankil.data.stock.storage.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class StockDailyConfiguration {
    @Bean public DailyTarget dailyTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb, QuestDbProperties properties,
            @Value("${app.sync.daily-table:daily}") String table) {
        return new DailyQuestDbTarget(table,jdbc,questdb,properties);
    }
    @Bean public DailyBasicTarget dailyBasicTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb, QuestDbProperties properties,
            @Value("${app.sync.daily-basic-table:daily_basic}") String table) {
        return new DailyBasicQuestDbTarget(table,jdbc,questdb,properties);
    }
    @Bean public StockBasicTarget stockBasicTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb, QuestDbProperties properties) {
        return new StockBasicQuestDbTarget(StockBasicDataset.DEFINITION.objectName(),jdbc,questdb,properties);
    }
}
