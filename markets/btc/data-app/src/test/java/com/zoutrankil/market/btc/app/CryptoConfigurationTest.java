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
                "2020-01-01", env.getRequiredProperty("app.crypto-data.archive-history-start"));
        assertEquals(
                400L, env.getRequiredProperty("app.crypto-data.request-spacing-ms", Long.class));
        assertEquals(
                180,
                env.getRequiredProperty(
                        "app.crypto-data.quality-trade-download-timeout-seconds", Integer.class));
        assertEquals(
                7200,
                env.getRequiredProperty(
                        "app.crypto-data.research-price-max-age-seconds", Integer.class));
        assertEquals(
                2, env.getRequiredProperty("app.crypto-data.daily-source-attempts", Integer.class));
        assertEquals(
                3600,
                env.getRequiredProperty("app.crypto-data.daily-timeout-seconds", Integer.class));
        assertEquals(
                30,
                env.getRequiredProperty("app.crypto-data.daily-log-retention-days", Integer.class));
        assertEquals(
                10737418240L,
                env.getRequiredProperty("app.crypto-data.daily-max-root-bytes", Long.class));
        assertEquals(
                1073741824L,
                env.getRequiredProperty("app.crypto-data.daily-min-free-bytes", Long.class));
        assertEquals(
                1, env.getRequiredProperty("app.crypto-data.daily-max-lag-days", Integer.class));
        assertEquals(
                2,
                env.getRequiredProperty("app.crypto-data.revision-recent-months", Integer.class));
        assertEquals(
                18, env.getRequiredProperty("app.crypto-data.revision-max-files", Integer.class));
        assertEquals(
                1,
                env.getRequiredProperty("app.crypto-data.schedule-start-utc-hour", Integer.class));
        assertEquals(
                3, env.getRequiredProperty("app.crypto-data.schedule-retry-hours", Integer.class));
        assertEquals(
                8, env.getRequiredProperty("app.crypto-data.schedule-max-attempts", Integer.class));
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
