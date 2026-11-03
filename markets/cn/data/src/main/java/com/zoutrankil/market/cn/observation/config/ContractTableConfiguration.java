package com.zoutrankil.market.cn.observation.config;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.application.ContractTableSyncOwner;
import com.zoutrankil.market.cn.observation.cli.ContractKeyRepairCommands;
import com.zoutrankil.market.cn.observation.cli.ContractTableCommands;
import com.zoutrankil.market.cn.observation.mapper.ContractTableDefinition;
import com.zoutrankil.market.cn.observation.storage.QuestDbContractTableTarget;

import io.questdb.client.QuestDB;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

/** 注册既有来源的有限 owner，不启动常驻调度，不自动改变原表。 */
@Configuration(proxyBeanMethods = false)
public class ContractTableConfiguration {
    @Bean
    ContractTableSyncOwner FutBasicOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "fut_basic",
                new QuestDbContractTableTarget("fut_basic", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> FutBasicRead() {
        return ReadBindingCatalog.generic("fut_basic", 1);
    }

    @Bean
    ContractTableSyncOwner DisclosureDateOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "disclosure_date",
                new QuestDbContractTableTarget("disclosure_date", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> DisclosureDateRead() {
        return ReadBindingCatalog.generic("disclosure_date", 1);
    }

    @Bean
    ContractTableSyncOwner ShareFloatOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "share_float",
                new QuestDbContractTableTarget("share_float", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> ShareFloatRead() {
        return ReadBindingCatalog.generic("share_float", 1);
    }

    @Bean
    ContractTableSyncOwner DividendOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "dividend",
                new QuestDbContractTableTarget("dividend", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> DividendRead() {
        return ReadBindingCatalog.generic("dividend", 1);
    }

    @Bean
    ContractTableSyncOwner FinaAuditOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "fina_audit",
                new QuestDbContractTableTarget("fina_audit", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> FinaAuditRead() {
        return ReadBindingCatalog.generic("fina_audit", 1);
    }

    @Bean
    ContractTableSyncOwner FinaMainbzOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "fina_mainbz",
                new QuestDbContractTableTarget("fina_mainbz", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> FinaMainbzRead() {
        return ReadBindingCatalog.generic("fina_mainbz", 1);
    }

    @Bean
    ContractTableSyncOwner pledgeStatOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "pledge_stat",
                new QuestDbContractTableTarget("pledge_stat", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> pledgeStatRead() {
        return ReadBindingCatalog.generic("pledge_stat", 1);
    }

    @Bean
    ContractTableSyncOwner holdernumberOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "stk_holdernumber",
                new QuestDbContractTableTarget("stk_holdernumber", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> holdernumberRead() {
        return ReadBindingCatalog.generic("stk_holdernumber", 1);
    }

    @Bean
    ContractTableSyncOwner StkHoldertradeOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "stk_holdertrade",
                new QuestDbContractTableTarget("stk_holdertrade", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> StkHoldertradeRead() {
        return ReadBindingCatalog.generic("stk_holdertrade", 1);
    }

    @Bean
    ContractTableSyncOwner StkShockOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "stk_shock",
                new QuestDbContractTableTarget("stk_shock", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ContractTableSyncOwner StkHighShockOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "stk_high_shock",
                new QuestDbContractTableTarget("stk_high_shock", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ContractTableSyncOwner brokerRecommendOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "broker_recommend",
                new QuestDbContractTableTarget("broker_recommend", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> brokerRecommendRead(JdbcTemplate jdbc) {
        return guardedRead("broker_recommend", jdbc);
    }

    @Bean
    ContractTableSyncOwner stockAlertOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "stk_alert",
                new QuestDbContractTableTarget("stk_alert", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> stockAlertRead(JdbcTemplate jdbc) {
        return guardedRead("stk_alert", jdbc);
    }

    @Bean
    ContractTableSyncOwner futuresHoldingOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "fut_holding",
                new QuestDbContractTableTarget("fut_holding", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> futuresHoldingRead() {
        return ReadBindingCatalog.generic("fut_holding", 1);
    }

    @Bean
    ContractTableSyncOwner cyqChipsOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "cyq_chips",
                new QuestDbContractTableTarget("cyq_chips", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> cyqChipsRead() {
        return ReadBindingCatalog.generic("cyq_chips", 1);
    }

    @Bean
    ContractTableSyncOwner sgeDailyOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new ContractTableSyncOwner(
                "sge_daily",
                new QuestDbContractTableTarget("sge_daily", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> sgeDailyRead(JdbcTemplate jdbc) {
        return guardedRead("sge_daily", jdbc);
    }

    // 旧键与新读取键不一致时先拒绝分页，不能让 LIMIT 掩盖跨页重复。
    private static ReadBindingCatalog.Registration<DatasetValues> guardedRead(
            String dataset, JdbcTemplate jdbc) {
        return ReadBindingCatalog.generic(
                dataset,
                1,
                () -> {
                    QuestDbWriteChecks.preflight(
                            jdbc, dataset, ContractTableDefinition.from(dataset));
                    return null;
                });
    }

    @Bean
    @ConditionalOnNotWebApplication
    ContractKeyRepairCommands contractKeyRepairCommands(JdbcTemplate jdbc) {
        return new ContractKeyRepairCommands(jdbc);
    }

    @Bean
    @ConditionalOnNotWebApplication
    ContractTableCommands contractTableCommands(List<ContractTableSyncOwner> owners) {
        return new ContractTableCommands(owners);
    }
}
