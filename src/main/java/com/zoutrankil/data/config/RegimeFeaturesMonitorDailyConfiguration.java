package com.zoutrankil.data.config;

import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.RegimeFeaturesMonitorDailyJobService;
import com.zoutrankil.data.service.NativeDailyWindowPublication;
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
        return new RegimeFeaturesMonitorDailyJobService(new RegimeFeaturesMonitorDailyStorage(jdbc),
                writer->new NativeDailyWindowPublication<>(new NativeDailyWindowPublicationStorage(jdbc),
                        Path.of(ledger),"regime_features_monitor_daily",writer),ledger,table);
    }
}
