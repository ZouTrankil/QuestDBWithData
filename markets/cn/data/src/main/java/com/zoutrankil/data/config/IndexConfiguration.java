package com.zoutrankil.data.config;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.calendar.storage.QuestDbExchangeCalendarReadPort;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.storage.*;
import com.zoutrankil.data.stock.port.StockDetailNameReadPort;
import com.zoutrankil.data.stock.storage.QuestDbStockDetailNameReadPort;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/** Target construction and cross-family read adapters belong to composition. */
@Configuration(proxyBeanMethods = false)
public class IndexConfiguration {
    @Bean IndexCatalogTarget indexCatalogTarget(JdbcTemplate jdbc,
            @Value("${app.sync.index-catalog-table:index}") String table) {
        return new QuestDbIndexCatalogTarget(jdbc, table);
    }
    @Bean IndexMembershipTarget indexMembershipTarget(JdbcTemplate jdbc,
            @Value("${app.sync.index-member-table:index_member}") String table) {
        return new QuestDbIndexMembershipTarget(jdbc, table);
    }
    @Bean ThsIndexTarget thsIndexTarget(JdbcTemplate jdbc,
            @Value("${app.sync.ths-index-table:ths_index}") String table) {
        return new QuestDbThsIndexTarget(table, jdbc);
    }
    @Bean ThsMemberTarget thsMemberTarget(JdbcTemplate jdbc,
            @Value("${app.sync.ths-member-table:ths_member}") String table) {
        return new QuestDbThsMemberTarget(table, jdbc);
    }
    @Bean IndexDailyMarketTarget indexDailyMarketTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.index-daily-market-table:java_d019_index_daily_market_acceptance}") String table) {
        return new QuestDbIndexDailyMarketTarget(table, jdbc, questdb);
    }
    @Bean IndexDailyBasicTarget indexDailyBasicTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.index-daily-basic-table:java_d020_index_daily_basic_acceptance}") String table) {
        return new QuestDbIndexDailyBasicTarget(table, jdbc, questdb);
    }
    @Bean IndexWeightTarget indexWeightTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.index-weight-table:java_d021_index_weight_acceptance}") String table) {
        return new QuestDbIndexWeightTarget(table, jdbc, questdb);
    }
    @Bean IndexMonthlyTarget indexMonthlyTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.index-monthly-table:java_d022_index_monthly_acceptance}") String table) {
        return new QuestDbIndexMonthlyTarget(table, jdbc, questdb);
    }
    @Bean DcIndexTarget dcIndexTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.dc-index-table:java_d023_dc_index_acceptance}") String table) {
        return new QuestDbDcIndexTarget(table, jdbc, questdb);
    }
    @Bean StockDetailNameReadPort stockDetailNameReadPort(JdbcTemplate jdbc,
            @Value("${app.sync.stock-detail-table:stock_detail_info}") String table) {
        return new QuestDbStockDetailNameReadPort(jdbc, table);
    }
    @Bean ExchangeCalendarReadPort exchangeCalendarReadPort(ExchangeCalendarReadRepository repository) {
        return new QuestDbExchangeCalendarReadPort(repository);
    }
}
