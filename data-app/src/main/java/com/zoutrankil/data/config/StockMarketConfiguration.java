package com.zoutrankil.data.config;

import com.zoutrankil.data.stock.port.StockFactorTarget;
import com.zoutrankil.data.stock.port.StockLimitTarget;
import com.zoutrankil.data.stock.port.StockStDailyTarget;
import com.zoutrankil.data.stock.port.StockSuspendTarget;
import com.zoutrankil.data.stock.storage.QuestDbStockFactorTarget;
import com.zoutrankil.data.stock.storage.QuestDbStockLimitTarget;
import com.zoutrankil.data.stock.storage.QuestDbStockStDailyTarget;
import com.zoutrankil.data.stock.storage.QuestDbStockSuspendTarget;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class StockMarketConfiguration {
    @Bean StockFactorTarget stockFactorTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.stk-factor-table:stk_factor}") String table) {
        return new QuestDbStockFactorTarget(table, jdbc, questdb);
    }
    @Bean StockLimitTarget stockLimitTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.stk-limit-table:java_d010_stk_limit_acceptance}") String table) {
        return new QuestDbStockLimitTarget(table, jdbc, questdb);
    }
    @Bean StockStDailyTarget stockStDailyTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.stk-st-daily-table:java_d012_stk_st_daily_acceptance}") String table) {
        return new QuestDbStockStDailyTarget(table, jdbc, questdb);
    }
    @Bean StockSuspendTarget stockSuspendTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.stk-suspend-table:stk_suspend_d011_isolated}") String table) {
        return new QuestDbStockSuspendTarget(table, jdbc, questdb);
    }
}
