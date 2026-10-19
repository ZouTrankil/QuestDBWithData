package com.zoutrankil.market.btc.app;

import com.zoutrankil.runtime.ProjectConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CryptoConfigurationTest {
    @Test void independentProfileLoadsUtcAndPersistentSourceBudgets() {
        var env=ProjectConfiguration.load("btc-data");assertEquals("UTC",env.getRequiredProperty("app.time.markets.btc"));
        assertEquals("2021-10-01",env.getRequiredProperty("app.crypto-data.history-start"));assertEquals(400L,env.getRequiredProperty("app.crypto-data.request-spacing-ms",Long.class));
    }
}
