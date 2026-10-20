package com.zoutrankil.market.btc.marketdata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class CryptoPriceMapperTest {
    private byte[] archive(boolean micros, boolean omitHour) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("BTCUSDT.csv"));
            for (int hour = 0; hour < 24; hour++)
                if (!omitHour || hour != 12) {
                    long stamp =
                            Instant.parse("2026-01-02T00:00:00Z")
                                            .plusSeconds(hour * 3600L)
                                            .toEpochMilli()
                                    * (micros ? 1000 : 1);
                    zip.write(
                            (stamp + ",10,12,9,11,2,0,22,1,1,11,0\n")
                                    .getBytes(StandardCharsets.UTF_8));
                }
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void acceptsNewSpotMicrosecondsAndAggregatesOnlyCompleteUtcDays() throws Exception {
        var day = LocalDate.of(2026, 1, 2);
        var rows =
                CryptoPriceMapper.binance(
                        archive(true, false),
                        "BTCUSDT",
                        false,
                        "a".repeat(64),
                        Instant.EPOCH,
                        day,
                        day);
        assertEquals(24, rows.size());
        var daily = CryptoPriceMapper.daily(rows).getFirst();
        assertEquals(1440, daily.get("interval_minutes"));
        assertEquals(48., daily.get("volume"));
        assertEquals(528., daily.get("quote_volume"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CryptoPriceMapper.binance(
                                archive(false, false),
                                "BTCUSDT",
                                false,
                                "sha",
                                Instant.EPOCH,
                                day,
                                day));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CryptoPriceMapper.binance(
                                archive(true, true),
                                "BTCUSDT",
                                false,
                                "sha",
                                Instant.EPOCH,
                                day,
                                day));
        assertThrows(
                IllegalArgumentException.class, () -> CryptoPriceMapper.daily(rows.subList(0, 23)));
    }

    @Test
    void perpetualUsesMillisecondsAndCannotMixExchangesWithinADay() throws Exception {
        var day = LocalDate.of(2026, 1, 2);
        var rows =
                CryptoPriceMapper.binance(
                        archive(false, false), "ETHUSDT", true, "sha", Instant.EPOCH, day, day);
        assertEquals("USDT_PERPETUAL", rows.getFirst().get("instrument"));
        var changed = new ArrayList<>(rows);
        var other = new LinkedHashMap<>(rows.getFirst());
        other.put("exchange", "BYBIT");
        changed.set(0, other);
        assertThrows(IllegalArgumentException.class, () -> CryptoPriceMapper.daily(changed));
    }

    @Test
    void anExplicitClosureDoesNotGenerateAnHourAndNativeDailyMustReconcileTheActualTrades()
            throws Exception {
        var day = LocalDate.of(2026, 1, 2);
        var closure =
                new CryptoTradingClosure(
                        "BINANCE",
                        "SPOT",
                        Instant.parse("2026-01-02T12:00:00Z"),
                        Instant.parse("2026-01-02T13:00:00Z"),
                        "https://www.binance.com/en/support/test-fixture");
        var sparse =
                CryptoPriceMapper.binance(
                        archive(true, true),
                        "BTCUSDT",
                        false,
                        "sha",
                        Instant.EPOCH,
                        day,
                        day,
                        60,
                        List.of(closure));
        assertEquals(23, sparse.size());
        assertEquals(
                List.of(Instant.parse("2026-01-02T12:00:00Z")),
                CryptoPriceMapper.missingHours(sparse, day, day));
        var complete =
                CryptoPriceMapper.binance(
                        archive(true, false), "BTCUSDT", false, "sha", Instant.EPOCH, day, day);
        var nativeDay = CryptoPriceMapper.daily(complete);
        assertThrows(
                IllegalArgumentException.class,
                () -> CryptoPriceMapper.reconcileNativeDaily(sparse, nativeDay));
        var noTrade = new ArrayList<>(complete);
        var hour = new LinkedHashMap<>(noTrade.get(12));
        hour.put("volume", 0.);
        hour.put("quote_volume", 0.);
        noTrade.set(12, hour);
        assertDoesNotThrow(
                () ->
                        CryptoPriceMapper.reconcileNativeDaily(
                                sparse, CryptoPriceMapper.daily(noTrade)));
        assertFalse(
                closure.containsHour(
                        "BINANCE", "USDT_PERPETUAL", Instant.parse("2026-01-02T12:00:00Z")));
    }
}
