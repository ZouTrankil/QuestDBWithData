package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.EtfBasic;
import com.zoutrankil.data.domain.EtfBasicKey;
import com.zoutrankil.data.etf.storage.QuestDbEtfBasicTarget;
import com.zoutrankil.data.domain.EtfPortfolio;
import com.zoutrankil.data.domain.EtfPortfolioKey;
import com.zoutrankil.data.etf.storage.QuestDbEtfPortfolioTarget;
import com.zoutrankil.data.domain.EtfShare;
import com.zoutrankil.data.domain.EtfShareKey;
import com.zoutrankil.data.etf.storage.QuestDbEtfShareTarget;
import com.zoutrankil.data.etf.port.EtfWriteTarget;
import com.zoutrankil.data.domain.EtfAdj;
import com.zoutrankil.data.domain.EtfAdjKey;
import com.zoutrankil.data.domain.EtfDaily;
import com.zoutrankil.data.domain.EtfDailyKey;
import com.zoutrankil.data.domain.EtfFactor;
import com.zoutrankil.data.domain.EtfFactorKey;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.port.EtfAdjTarget;
import com.zoutrankil.data.etf.storage.QuestDbEtfAdjTarget;
import com.zoutrankil.data.etf.storage.QuestDbEtfDailyTarget;
import com.zoutrankil.data.etf.storage.QuestDbEtfFactorTarget;
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
    EtfTarget<EtfDaily,EtfDailyKey> etfDailyTarget(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.etf-daily-table:java_d014_etf_daily_acceptance}") String table) {
        return new QuestDbEtfDailyTarget(table,jdbc,questdb);
    }

    @Bean
    EtfAdjTarget etfAdjTarget(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.etf-adj-table:java_d015_etf_adj_acceptance}") String table) {
        return new QuestDbEtfAdjTarget(table,jdbc,questdb);
    }

    @Bean
    EtfTarget<EtfFactor,EtfFactorKey> etfFactorTarget(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.etf-factor-table:java_d017_etf_factor_acceptance}") String table) {
        return new QuestDbEtfFactorTarget(table,jdbc,questdb);
    }

    @Bean
    EtfWriteTarget<EtfBasic,EtfBasicKey> etfBasicTarget(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.etf-basic-table:java_d013_etf_basic_acceptance}") String table) {
        return new QuestDbEtfBasicTarget(table,jdbc,questdb);
    }

    @Bean
    EtfTarget<EtfPortfolio,EtfPortfolioKey> etfPortfolioTarget(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.etf-portfolio-table:java_d018_etf_portfolio_acceptance}") String table) {
        return new QuestDbEtfPortfolioTarget(table,jdbc,questdb);
    }

    @Bean
    EtfTarget<EtfShare,EtfShareKey> etfShareTarget(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.etf-share-table:java_d016_etf_share_acceptance}") String table) {
        return new QuestDbEtfShareTarget(table,jdbc,questdb);
    }
}
