package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class BinanceArchiveMapperTest {
    private static final String SHA = "a".repeat(64);
    private static final Instant OBSERVED = Instant.parse("2026-10-20T12:00:00Z");

    private static byte[] zip(String name, String csv) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(name));
            out.write(csv.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void spotPrecisionChangesButDayAndCloseRemainExact() throws Exception {
        var day2024 = LocalDate.of(2024, 12, 31);
        var old =
                BinanceArchiveMapper.parse(
                                BinanceArchiveDataset.SPOT_1D,
                                zip(
                                        "x.csv",
                                        "1735603200000,100,120,90,110,2,1735689599999,220,3,1,110,0"),
                                day2024,
                                day2024,
                                SHA,
                                OBSERVED)
                        .getFirst();
        assertEquals("2024-12-31T00:00:00Z", old.get("open_time"));
        assertEquals("2024-12-31T23:59:59.999Z", old.get("close_time"));
        var day2025 = LocalDate.of(2025, 1, 1);
        var current =
                BinanceArchiveMapper.parse(
                                BinanceArchiveDataset.SPOT_1D,
                                zip(
                                        "x.csv",
                                        "1735689600000000,100,120,90,110,2,1735775999999999,220,3,1,110,0"),
                                day2025,
                                day2025,
                                SHA,
                                OBSERVED)
                        .getFirst();
        assertEquals("2025-01-01T00:00:00Z", current.get("open_time"));
        assertEquals("2025-01-01T23:59:59.999999Z", current.get("close_time"));
        assertEquals(3L, current.get("trade_count"));
        assertEquals(17, current.size());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceArchiveMapper.parse(
                                BinanceArchiveDataset.SPOT_1D,
                                zip(
                                        "x.csv",
                                        "1735689600000,100,120,90,110,2,1735775999999,220,3,1,110,0"),
                                day2025,
                                day2025,
                                SHA,
                                OBSERVED));
    }

    @Test
    void fundingRetainsRealEventMillisecondsAndVariableInterval() throws Exception {
        var day = LocalDate.of(2026, 9, 30);
        var rows =
                BinanceArchiveMapper.parse(
                        BinanceArchiveDataset.FUNDING,
                        zip(
                                "x.csv",
                                "calc_time,funding_interval_hours,last_funding_rate\n1790784000002,8,0.00006606\n1790787600003,1,-0.0001"),
                        day,
                        day,
                        SHA,
                        OBSERVED);
        assertEquals("2026-09-30T16:00:00.002Z", rows.getFirst().get("funding_time"));
        assertEquals(1, rows.getLast().get("funding_interval_hours"));
        assertEquals(-0.0001, rows.getLast().get("funding_rate"));
        assertFalse(rows.getFirst().containsKey("volume"));
    }

    @Test
    void markPlaceholderCountsAreNotTradesAndMissingHoursAreRejected() throws Exception {
        var day = LocalDate.of(2025, 1, 1);
        var csv = new StringBuilder();
        for (int h = 0; h < 24; h++) {
            long start = 1735689600000L + h * 3600000L;
            csv.append(start)
                    .append(",100,120,90,110,0,")
                    .append(start + 3599999)
                    .append(",0,3600,0,0,0\n");
        }
        var rows =
                BinanceArchiveMapper.parse(
                        BinanceArchiveDataset.MARK_1H,
                        zip("x.csv", csv.toString()),
                        day,
                        day,
                        SHA,
                        OBSERVED);
        assertFalse(rows.getFirst().containsKey("volume"));
        assertFalse(rows.getFirst().containsKey("trade_count"));
        assertEquals(
                BinanceArchiveDefinitions.definition(BinanceArchiveDataset.MARK_1H)
                        .columns()
                        .size(),
                rows.getFirst().size());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceArchiveMapper.parse(
                                BinanceArchiveDataset.MARK_1H,
                                zip("x.csv", csv.substring(csv.indexOf("\n") + 1)),
                                day,
                                day,
                                SHA,
                                OBSERVED));
    }

    @Test
    void unsafeZipAndUnknownHeaderAreRejected() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> BinanceArchiveMapper.csv(zip("../x.csv", "1")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceArchiveMapper.parse(
                                BinanceArchiveDataset.FUNDING,
                                zip("x.csv", "time,rate\n1,2"),
                                LocalDate.of(2020, 1, 1),
                                LocalDate.of(2020, 1, 1),
                                SHA,
                                OBSERVED));
    }

    @Test
    void nativeProductsUseNewTargetsAndFundingHasOwnTimeKey() {
        assertEquals(
                "btc_spot_1d",
                BinanceArchiveDefinitions.definition(BinanceArchiveDataset.SPOT_1D).objectName());
        assertEquals(
                "funding_time",
                BinanceArchiveDefinitions.definition(BinanceArchiveDataset.FUNDING)
                        .designatedTimestamp());
        assertTrue(
                BinanceArchiveDataset.FUNDING
                        .monthly(YearMonth.of(2025, 1))
                        .toString()
                        .endsWith("BTCUSDT-fundingRate-2025-01.zip"));
    }

    @Test
    void retainedPartialBarsReportMissingAndShortenedIntervalsWithoutInventingPrices()
            throws Exception {
        var day = LocalDate.of(2025, 1, 1);
        var csv = new StringBuilder();
        for (int h = 0; h < 24; h++) {
            if (h == 12) continue;
            long start = 1735689600000L + h * 3600000L;
            long close = start + (h == 5 ? 1799999 : 3599999);
            csv.append(start).append(",100,120,90,110,0,").append(close).append(",0,3600,0,0,0\n");
        }
        var result =
                BinanceArchiveMapper.inspect(
                        BinanceArchiveDataset.MARK_1H,
                        zip("x.csv", csv.toString()),
                        day,
                        day,
                        SHA,
                        OBSERVED);
        assertFalse(result.complete());
        assertEquals(23, result.rows().size());
        assertEquals(List.of("2025-01-01T12:00:00Z"), result.missingOpenTimes());
        assertEquals(
                "2025-01-01T05:29:59.999Z", result.shortenedBars().getFirst().get("close_time"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceArchiveMapper.parse(
                                BinanceArchiveDataset.MARK_1H,
                                zip("x.csv", csv.toString()),
                                day,
                                day,
                                SHA,
                                OBSERVED));
    }

    @Test
    void impossibleEmptyBarIsQuarantinedInsteadOfCreatingAPrice() throws Exception {
        var day = LocalDate.of(2020, 12, 21);
        long base = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        var csv = new StringBuilder();
        for (int h = 0; h < 24; h++) {
            long open = base + h * 3600000L;
            csv.append(open)
                    .append(",100,100,100,100,0,")
                    .append(h == 14 ? open - 1000 : open + 3599999)
                    .append(",0,0,0,0,0\n");
        }
        var result =
                BinanceArchiveMapper.inspect(
                        BinanceArchiveDataset.SPOT_1H,
                        zip("x.csv", csv.toString()),
                        day,
                        day,
                        SHA,
                        OBSERVED);
        assertEquals(23, result.rows().size());
        assertEquals(List.of("2020-12-21T14:00:00Z"), result.missingOpenTimes());
        assertEquals(1, result.excludedRows().size());
        assertFalse(result.complete());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceArchiveMapper.parse(
                                BinanceArchiveDataset.SPOT_1H,
                                zip("x.csv", csv.toString()),
                                day,
                                day,
                                SHA,
                                OBSERVED));
    }
}
