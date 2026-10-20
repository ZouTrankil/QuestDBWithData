package com.zoutrankil.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.domain.temporal.MarketId;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

class MarketClocksTest {
    private StandardEnvironment environment(Map<String, Object> values) {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", values));
        return env;
    }

    @Test
    void eachMarketReadsItsOwnConfiguration() {
        var env =
                environment(
                        Map.of(
                                "app.time.business-zone",
                                "Asia/Shanghai",
                                "app.time.markets.cn",
                                "Asia/Shanghai",
                                "app.time.markets.us",
                                "America/New_York",
                                "app.time.markets.btc",
                                "UTC"));
        var times =
                MarketClocks.load(
                        env, Clock.fixed(Instant.parse("2026-10-18T16:30:00Z"), ZoneOffset.UTC));
        assertEquals(LocalDate.parse("2026-10-19"), times.require(MarketId.CN).today());
        assertEquals(LocalDate.parse("2026-10-18"), times.require(MarketId.US).today());
        assertEquals(ZoneId.of("UTC"), times.require(MarketId.BTC).zone());
    }

    @Test
    void conflictingLegacyCnConfigurationFailsInsteadOfShiftingDates() {
        var env =
                environment(
                        Map.of(
                                "app.time.business-zone",
                                "UTC",
                                "app.time.markets.cn",
                                "Asia/Shanghai"));
        assertThrows(
                IllegalArgumentException.class, () -> MarketClocks.load(env, Clock.systemUTC()));
    }

    @Test
    void oldSingleMarketConfigurationStillHasAnExplicitCnMeaning() {
        var times =
                MarketClocks.load(
                        environment(Map.of("app.time.business-zone", "Asia/Shanghai")),
                        Clock.systemUTC());
        assertEquals(ZoneId.of("Asia/Shanghai"), times.require(MarketId.CN).zone());
        assertEquals(ZoneId.of("America/New_York"), times.require(MarketId.US).zone());
    }
}
