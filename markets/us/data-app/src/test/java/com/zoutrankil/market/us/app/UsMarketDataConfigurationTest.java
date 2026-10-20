package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.runtime.ProjectConfiguration;

import org.junit.jupiter.api.Test;

import java.util.List;

class UsMarketDataConfigurationTest {
    @Test
    void loadsIndexedYamlListsThroughTheIndependentProfile() {
        var symbols = UsCoreCollector.configuredSymbols(ProjectConfiguration.load("us-data"));
        assertEquals(16, symbols.size());
        assertTrue(
                symbols.containsAll(
                        List.of("SPY", "QQQ", "IWM", "TLT", "GLD", "USO", "AAPL", "NVDA")));
        var page = UsBroadDailyCollector.publicationPage(ProjectConfiguration.load("us-data"));
        assertEquals(10000, page.rows());
        assertEquals(8388608, page.bytes());
        var environment = ProjectConfiguration.load("us-data");
        assertTrue(
                environment.getProperty("app.market-data.broad-prefetch-enabled", Boolean.class));
        assertEquals(256, UsBroadDailyPrefetch.capacity(environment));
        assertEquals(
                new UsBroadSourceRetry.Policy(3, 5000), UsBroadSourceRetry.policy(environment));
        assertTrue(environment.getProperty("app.market-data.broad-annual-enabled", Boolean.class));
        assertEquals(128, UsBroadDailyCollector.annualSymbols(environment));
    }
}
