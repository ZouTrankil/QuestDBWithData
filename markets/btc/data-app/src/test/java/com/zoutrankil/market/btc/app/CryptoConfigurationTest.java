package com.zoutrankil.market.btc.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.market.btc.marketdata.CryptoTradingClosure;
import com.zoutrankil.runtime.ProjectConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.time.Instant;

class CryptoConfigurationTest {
    @Test
    void independentProfileLoadsUtcAndPersistentSourceBudgets() {
        var env = ProjectConfiguration.load("btc-data");
        assertEquals("UTC", env.getRequiredProperty("app.time.markets.btc"));
        assertEquals("2021-10-01", env.getRequiredProperty("app.crypto-data.history-start"));
        assertEquals(
                400L, env.getRequiredProperty("app.crypto-data.request-spacing-ms", Long.class));
        var closures =
                Binder.get(env)
                        .bind(
                                "app.crypto-data.venue-closures",
                                Bindable.listOf(CryptoTradingClosure.class))
                        .orElseThrow(() -> new IllegalStateException("停市证据配置未加载"));
        assertEquals(1, closures.size());
        assertTrue(
                closures.getFirst()
                        .containsHour("BINANCE", "SPOT", Instant.parse("2023-03-24T13:00:00Z")));
        assertFalse(
                closures.getFirst()
                        .containsHour("BINANCE", "SPOT", Instant.parse("2023-03-24T11:00:00Z")));
    }
}
