package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.fund.EtfBasic;
import com.zoutrankil.data.domain.fund.EtfBasicKey;
import com.zoutrankil.data.domain.fund.EtfDaily;
import com.zoutrankil.data.domain.fund.EtfDailyKey;
import com.zoutrankil.data.domain.fund.EtfFactor;
import com.zoutrankil.data.domain.fund.EtfFactorKey;
import com.zoutrankil.data.domain.fund.EtfPortfolio;
import com.zoutrankil.data.domain.fund.EtfPortfolioKey;
import com.zoutrankil.data.domain.fund.EtfShare;
import com.zoutrankil.data.domain.fund.EtfShareKey;
import com.zoutrankil.data.etf.port.EtfAdjTarget;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.port.EtfWriteTarget;
import com.zoutrankil.data.etf.storage.QuestDbEtfAdjTarget;
import com.zoutrankil.data.etf.storage.QuestDbEtfBasicTarget;
import com.zoutrankil.data.etf.storage.QuestDbEtfDailyTarget;
import com.zoutrankil.data.etf.storage.QuestDbEtfFactorTarget;
import com.zoutrankil.data.etf.storage.QuestDbEtfPortfolioTarget;
import com.zoutrankil.data.etf.storage.QuestDbEtfShareTarget;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/** Database dependencies enter the ETF family through configured target ports. */
@Configuration(proxyBeanMethods = false)
public class EtfConfiguration {
    @Bean
    EtfTarget<EtfDaily, EtfDailyKey> etfDailyTarget(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.etf-daily-table:java_d014_etf_daily_acceptance}") String table,
            @Value("${app.sync.etf-daily-schema-version:1}") int version) {
        return new QuestDbEtfDailyTarget(
                table, jdbc, questdb, CnTradeDateLayout.fromVersion(version));
    }

    @Bean
    EtfAdjTarget etfAdjTarget(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.etf-adj-table:java_d015_etf_adj_acceptance}") String table,
            @Value("${app.sync.etf-adj-schema-version:1}") int schemaVersion) {
        return new QuestDbEtfAdjTarget(
                table, jdbc, questdb, CnTradeDateLayout.fromVersion(schemaVersion));
    }

    @Bean
    EtfTarget<EtfFactor, EtfFactorKey> etfFactorTarget(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.etf-factor-table:java_d017_etf_factor_acceptance}") String table) {
        return new QuestDbEtfFactorTarget(table, jdbc, questdb);
    }

    @Bean
    EtfWriteTarget<EtfBasic, EtfBasicKey> etfBasicTarget(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.etf-basic-table:java_d013_etf_basic_acceptance}") String table) {
        return new QuestDbEtfBasicTarget(table, jdbc, questdb);
    }

    @Bean
    EtfTarget<EtfPortfolio, EtfPortfolioKey> etfPortfolioTarget(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.etf-portfolio-table:java_d018_etf_portfolio_acceptance}")
                    String table) {
        return new QuestDbEtfPortfolioTarget(table, jdbc, questdb);
    }

    @Bean
    EtfTarget<EtfShare, EtfShareKey> etfShareTarget(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.etf-share-table:java_d016_etf_share_acceptance}") String table,
            @Value("${app.sync.etf-share-schema-version:1}") int version) {
        return new QuestDbEtfShareTarget(
                table, jdbc, questdb, CnTradeDateLayout.fromVersion(version));
    }
}
