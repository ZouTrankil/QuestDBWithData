package com.zoutrankil.data.domain.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

class MarketTimeTest {
    private MarketTime time(MarketId market, String instant) {
        return new MarketClock(
                market, market.defaultZone(), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void sameInstantHasDifferentBusinessDatesWithoutMovingStorageTime() {
        var instant = "2026-10-18T16:30:00Z";
        var cn = time(MarketId.CN, instant);
        var us = time(MarketId.US, instant);
        var btc = time(MarketId.BTC, instant);
        assertEquals(LocalDate.parse("2026-10-19"), cn.today());
        assertEquals(LocalDate.parse("2026-10-18"), us.today());
        assertEquals(LocalDate.parse("2026-10-18"), btc.today());
        assertEquals(cn.now(), us.now());
        assertEquals(us.now(), btc.now());
        assertEquals(Instant.parse("2026-10-18T16:00:00Z"), cn.startOfDay(cn.today()));
        assertEquals(
                Instant.parse("2026-10-19T00:00:00Z"),
                new TemporalValues.CalendarTimestamp(cn.today()).storageCarrier());
    }

    @Test
    void newYorkSessionChangesUtcOffsetWithDaylightSaving() {
        var us = time(MarketId.US, "2026-01-01T00:00:00Z");
        assertEquals(
                Instant.parse("2026-01-05T14:30:00Z"),
                us.toInstant(LocalDateTime.parse("2026-01-05T09:30:00")));
        assertEquals(
                Instant.parse("2026-07-06T13:30:00Z"),
                us.toInstant(LocalDateTime.parse("2026-07-06T09:30:00")));
        var spring = LocalDate.parse("2026-03-08");
        var autumn = LocalDate.parse("2026-11-01");
        assertEquals(23, Duration.between(us.startOfDay(spring), us.endOfDay(spring)).toHours());
        assertEquals(25, Duration.between(us.startOfDay(autumn), us.endOfDay(autumn)).toHours());
    }

    @Test
    void missingOrRepeatedLocalTimesRequireRealOffsetEvidence() {
        var us = time(MarketId.US, "2026-01-01T00:00:00Z");
        assertThrows(
                IllegalArgumentException.class,
                () -> us.toInstant(LocalDateTime.parse("2026-03-08T02:30:00")));
        var repeated = LocalDateTime.parse("2026-11-01T01:30:00");
        assertThrows(IllegalArgumentException.class, () -> us.toInstant(repeated));
        assertEquals(
                Duration.ofHours(1),
                Duration.between(
                        us.toInstant(repeated, ZoneOffset.ofHours(-4)),
                        us.toInstant(repeated, ZoneOffset.ofHours(-5))));
        assertThrows(
                IllegalArgumentException.class,
                () -> us.toInstant(repeated, ZoneOffset.ofHours(-6)));
    }

    @Test
    void btcUsesUtcMidnightAndCanDeclareAnExchangeSpecificCut() {
        var btc = time(MarketId.BTC, "2026-10-19T00:00:00Z");
        assertEquals(LocalDate.parse("2026-10-19"), btc.today());
        assertEquals(btc.now(), btc.startOfDay(btc.today()));
        var exchange = new MarketClock(MarketId.BTC, ZoneId.of("Asia/Shanghai"), btc.clock());
        assertEquals(Instant.parse("2026-10-18T16:00:00Z"), exchange.startOfDay(exchange.today()));
        assertEquals(btc.now(), exchange.now());
    }

    @Test
    void jvmDefaultDoesNotChangeAnyMarketClockOrDateMarker() {
        var previous = TimeZone.getDefault();
        try {
            var cn = time(MarketId.CN, "2026-10-18T16:30:00Z");
            var expected = cn.today();
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals(expected, cn.today());
            assertEquals(expected, new BusinessTime(cn).today());
            assertEquals(
                    Instant.parse("2026-10-19T00:00:00Z"),
                    new BusinessTime(cn).todaySnapshotMarker());
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void fixedOffsetCannotReplaceTheNewYorkRegionRules() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketClock(MarketId.US, ZoneOffset.ofHours(-5), Clock.systemUTC()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketClock(MarketId.CN, ZoneOffset.UTC, Clock.systemUTC()));
    }
}
