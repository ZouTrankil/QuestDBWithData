package com.zoutrankil.data.config;

import com.zoutrankil.data.maintenance.application.EtfOverviewMaintenance;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Checks both JOIN sources; ordinary CLI commands never start a background refresh loop. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication
@ConditionalOnProperty(name="app.materialized-views.etf-overview.enabled",havingValue="true")
public class EtfOverviewRefreshConfiguration {
    @Bean(destroyMethod="shutdown") ThreadPoolTaskScheduler etfOverviewRefreshScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("etf-overview-refresh-");scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(130);return scheduler;
    }

    @Bean ApplicationListener<ApplicationReadyEvent> startEtfOverviewRefresh(
            ThreadPoolTaskScheduler etfOverviewRefreshScheduler,EtfOverviewMaintenance maintenance,
            @Value("${app.materialized-views.etf-overview.check-interval:1m}") Duration interval) {
        if(interval.compareTo(Duration.ofSeconds(1))<0)throw new IllegalArgumentException("ETF check interval must be at least 1s");
        return event -> etfOverviewRefreshScheduler.scheduleWithFixedDelay(() -> {
            try { maintenance.refreshIfInstalled(); }
            catch(Exception failure) {
                LoggerFactory.getLogger(EtfOverviewRefreshConfiguration.class)
                        .warn("ETF overview refresh not certified: {}",failure.getMessage());
            }
        },interval);
    }
}
