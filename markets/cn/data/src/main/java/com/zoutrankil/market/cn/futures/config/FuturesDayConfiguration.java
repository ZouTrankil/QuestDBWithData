package com.zoutrankil.market.cn.futures.config;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.futures.application.FuturesDaySyncOwner;
import com.zoutrankil.market.cn.futures.cli.FuturesDayCommands;
import com.zoutrankil.market.cn.futures.storage.QuestDbFuturesDayTarget;

import io.questdb.client.QuestDB;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

/** 正式日频表沿用共享账本与已有物理目标，不借此启用任何日批。 */
@Configuration(proxyBeanMethods = false)
public class FuturesDayConfiguration {
    @Bean
    FuturesDaySyncOwner futuresDailyOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new FuturesDaySyncOwner(
                "fut_daily",
                new QuestDbFuturesDayTarget("fut_daily", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> futuresDailyRead() {
        return ReadBindingCatalog.generic("fut_daily", 1);
    }

    @Bean
    FuturesDaySyncOwner futuresMappingOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new FuturesDaySyncOwner(
                "fut_mapping",
                new QuestDbFuturesDayTarget("fut_mapping", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> futuresMappingRead() {
        return ReadBindingCatalog.generic("fut_mapping", 1);
    }

    @Bean
    FuturesDaySyncOwner futuresSettleOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new FuturesDaySyncOwner(
                "fut_settle",
                new QuestDbFuturesDayTarget("fut_settle", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> futuresSettleRead() {
        return ReadBindingCatalog.generic("fut_settle", 1);
    }

    @Bean
    FuturesDaySyncOwner futuresLimitOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new FuturesDaySyncOwner(
                "ft_limit",
                new QuestDbFuturesDayTarget("ft_limit", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> futuresLimitRead() {
        return ReadBindingCatalog.generic("ft_limit", 1);
    }

    @Bean
    @ConditionalOnNotWebApplication
    FuturesDayCommands futuresDayCommands(List<FuturesDaySyncOwner> owners) {
        return new FuturesDayCommands(owners);
    }
}
