package com.zoutrankil.data.config;

import com.zoutrankil.data.service.EtfMarketOverviewCacheOwnerGateway;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Target access is deferred; an unconfigured publisher cannot start a process or write data. */
@Configuration
public class EtfMarketOverviewCacheOwnerConfiguration {
    @Bean
    EtfMarketOverviewCacheOwnerGateway etfMarketOverviewCacheOwnerGateway(
            @Value("${app.sync.etf-market-overview-cache.python-executable:}") String python,
            @Value("${app.sync.etf-market-overview-cache.bridge-script:tools/d101_etf_cache_owner_bridge.py}") String bridge,
            @Value("${app.sync.etf-market-overview-cache.artifact-root:artifacts/java-migration/D101/commands/owner}") String artifacts,
            @Value("${app.sync.etf-market-overview-cache.private-root:var/d101-isolated-questdb}") String privateRoot,
            @Value("${app.sync.etf-market-overview-cache.expected-pid:0}") long pid,
            @Value("${app.sync.etf-market-overview-cache.process-timeout-seconds:90}") int timeoutSeconds,
            @Value("${app.sync.etf-market-overview-cache.max-source-rows:50000}") int maxSourceRows) {
        return new EtfMarketOverviewCacheOwnerGateway(new EtfMarketOverviewCacheOwnerGateway.Config(
                Path.of(python), Path.of(bridge), Path.of(artifacts), Path.of(privateRoot), pid,
                Duration.ofSeconds(timeoutSeconds), maxSourceRows));
    }
}
