package com.zoutrankil.data.config;

import com.zoutrankil.data.stock.port.StockDetailTarget;
import com.zoutrankil.data.stock.storage.QuestDbStockDetailTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Database operations for the application-owned stock reference publication protocol. */
@Configuration(proxyBeanMethods = false)
public class StockReferenceConfiguration {
    @Bean
    StockDetailTarget stockDetailTarget(JdbcTemplate jdbc,
            @Value("${app.sync.stock-detail-table:stock_detail_info}") String table) {
        return new QuestDbStockDetailTarget(jdbc,table);
    }
}
