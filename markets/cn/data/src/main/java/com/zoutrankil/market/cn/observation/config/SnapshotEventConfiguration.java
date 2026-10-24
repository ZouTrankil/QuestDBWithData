package com.zoutrankil.market.cn.observation.config;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.repository.DatasetPublicationReadModel;
import com.zoutrankil.data.repository.DatasetPublicationReadModel.State;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.application.SnapshotEventSyncOwner;
import com.zoutrankil.market.cn.observation.cli.SnapshotEventCommands;
import com.zoutrankil.market.cn.observation.storage.QuestDbSnapshotEventTables;

import io.questdb.client.QuestDB;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

/** 有限事件快照不启动日批，旧表必须经显式迁移才接公开读取。 */
@Configuration(proxyBeanMethods = false)
public class SnapshotEventConfiguration {
    @Bean
    SnapshotEventSyncOwner repurchaseOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new SnapshotEventSyncOwner(
                "repurchase",
                new QuestDbSnapshotEventTables("repurchase", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    SnapshotEventSyncOwner blockTradeOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new SnapshotEventSyncOwner(
                "block_trade",
                new QuestDbSnapshotEventTables("block_trade", jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    private ReadBindingCatalog.Registration<DatasetValues> read(
            String dataset, JdbcTemplate jdbc, Path ledger) {
        return ReadBindingCatalog.genericStable(
                dataset,
                2,
                () -> {
                    try {
                        if (DatasetPublicationReadModel.read(ledger, dataset) != State.CLEAR)
                            throw new IllegalStateException("事件快照仍有原锁或未决发布");
                        return new QuestDbSnapshotEventTables(dataset, jdbc, null).readVersion();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException("事件快照版本不可读", e);
                    }
                });
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> repurchaseRead(
            JdbcTemplate jdbc,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return read("repurchase", jdbc, Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> blockTradeRead(
            JdbcTemplate jdbc,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return read("block_trade", jdbc, Path.of(ledger));
    }

    @Bean
    @ConditionalOnNotWebApplication
    SnapshotEventCommands snapshotEventCommands(List<SnapshotEventSyncOwner> owners) {
        return new SnapshotEventCommands(owners);
    }
}
