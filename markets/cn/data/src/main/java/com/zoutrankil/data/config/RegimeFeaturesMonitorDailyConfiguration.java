package com.zoutrankil.data.config;

import com.zoutrankil.data.derived.storage.NativeDailyWindowPublicationStorage;
import com.zoutrankil.data.derived.storage.RegimeFeaturesMonitorDailyStorage;

import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.derived.application.RegimeFeaturesMonitorDailyJobService;
import com.zoutrankil.data.derived.application.NativeDailyWindowPublication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;

/** Assembles native regime storage without opening a connection or ledger. */
@Configuration(proxyBeanMethods=false)
public class RegimeFeaturesMonitorDailyConfiguration {
    @Bean RegimeFeaturesMonitorDailyJobService regimeFeaturesMonitorDailyJobService(JdbcTemplate jdbc,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger,
            @Value("${app.sync.regime-monitor-table:regime_features_monitor_daily}") String table) {
        var storage = new RegimeFeaturesMonitorDailyStorage(jdbc);
        return new RegimeFeaturesMonitorDailyJobService(storage, storage,
                writer->new NativeDailyWindowPublication<>(new NativeDailyWindowPublicationStorage(jdbc),
                        Path.of(ledger),"regime_features_monitor_daily",writer),ledger,table);
    }
}
