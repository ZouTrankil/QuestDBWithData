package com.zoutrankil.market.us.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

class YahooPriceMapperTest {
    private final String response =
            """
        {"chart":{"error":null,"result":[{"meta":{"symbol":"X","currency":"USD","exchangeTimezoneName":"America/New_York","instrumentType":"EQUITY"},
        "timestamp":[1767364200,1767623400],"events":{"splits":{"one":{"date":1767623400,"numerator":2,"denominator":1}},"dividends":{"cash":{"date":1767364200,"amount":0.25}}},
        "indicators":{"quote":[{"open":[10,11],"high":[12,13],"low":[9,10],"close":[11,12],"volume":[200,300]}],"adjclose":[{"adjclose":[10,12]}]}}]}}
        """;

    @Test
    void rejectsSourceOhlcConflictsWithoutReturningAPartialRequestedRange() throws Exception {
        for (String bad :
                List.of(
                        response.replace("\"open\":[10,11]", "\"open\":[13,11]"),
                        response.replace("\"close\":[11,12]", "\"close\":[13,12]"),
                        response.replace("\"open\":[10,11]", "\"open\":[8,11]"),
                        response.replace("\"close\":[11,12]", "\"close\":[8,12]"),
                        response.replace("\"low\":[9,10]", "\"low\":[14,10]"))) {
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    YahooPriceMapper.daily(
                                            bad.getBytes(StandardCharsets.UTF_8),
                                            "X",
                                            "X",
                                            "sha",
                                            Instant.EPOCH,
                                            LocalDate.of(2026, 1, 1),
                                            LocalDate.of(2026, 1, 5)));
            assertTrue(failure.getMessage().contains("OHLC"));
            assertTrue(failure.getMessage().contains("X 2026-01-02"));
        }
    }

    @Test
    void limitsOhlcValidationToTheSelectedDatesAndKeepsFutureSplitReconstruction()
            throws Exception {
        byte[] bytes =
                response.replace("\"close\":[11,12]", "\"close\":[11,99]")
                        .getBytes(StandardCharsets.UTF_8);
        var prepared = YahooPriceMapper.prepare(bytes, "X", "X", "sha", Instant.EPOCH);
        var first = prepared.rows(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2));
        assertEquals(
                YahooPriceMapper.dailyWindow(
                        response.getBytes(StandardCharsets.UTF_8),
                        "X",
                        "X",
                        "sha",
                        Instant.EPOCH,
                        LocalDate.of(2026, 1, 2),
                        LocalDate.of(2026, 1, 2)),
                first);
        assertEquals(22., first.getFirst().get("close"));
        assertEquals(.5, first.getFirst().get("dividend"));
        assertEquals(100., first.getFirst().get("volume"));
        assertTrue(prepared.rows(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> prepared.rows(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5)));
        assertThrows(
                IllegalArgumentException.class,
                () -> prepared.rows(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5)));
    }

    @Test
    void keepsFlatZeroVolumeQuotesWithoutChangingEitherPriceBasis() throws Exception {
        String flat =
                response.replace("\"high\":[12,13]", "\"high\":[10,13]")
                        .replace("\"low\":[9,10]", "\"low\":[10,10]")
                        .replace("\"close\":[11,12]", "\"close\":[10,12]")
                        .replace("\"volume\":[200,300]", "\"volume\":[0,300]");
        var rows =
                YahooPriceMapper.daily(
                        flat.getBytes(StandardCharsets.UTF_8),
                        "X",
                        "X",
                        "sha",
                        Instant.EPOCH,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 5));
        assertEquals(2, rows.size());
        var first = rows.getFirst();
        assertEquals(20, first.size());
        for (String field : List.of("open", "high", "low", "close")) {
            assertEquals(20., first.get(field));
            assertEquals(10., first.get("adj_" + field));
        }
        assertEquals(0., first.get("volume"));
        assertEquals(0., first.get("adj_volume"));
    }

    @Test
    void reversesFutureSplitsForPricesVolumesAndDividendsWithoutApplyingTheSplitTwiceOnItsDate()
            throws Exception {
        var rows =
                YahooPriceMapper.daily(
                        response.getBytes(StandardCharsets.UTF_8),
                        "X",
                        "X",
                        "sha",
                        Instant.EPOCH,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 5));
        assertEquals(2, rows.size());
        var first = rows.getFirst();
        assertEquals(22., first.get("close"));
        assertEquals(100., first.get("volume"));
        assertEquals(.5, first.get("dividend"));
        assertEquals(10., first.get("adj_close"));
        assertEquals(12., rows.getLast().get("close"));
        assertEquals(2., rows.getLast().get("split_factor"));
        assertTrue(first.get("raw_price_basis").toString().contains("RECONSTRUCTED"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        YahooPriceMapper.daily(
                                response.replace("[11,12]", "[null,12]")
                                        .getBytes(StandardCharsets.UTF_8),
                                "X",
                                "X",
                                "sha",
                                Instant.EPOCH,
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 1, 5)));
    }

    @Test
    void preparedResponseOwnsItsInputAndRecreatesRowsWithoutLosingFutureCorporateActions()
            throws Exception {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        var prepared = YahooPriceMapper.prepare(bytes, "X", "X", "sha", Instant.EPOCH);
        Arrays.fill(bytes, (byte) 0);
        var first = prepared.rows(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3));
        assertEquals(1, first.size());
        assertEquals(22., first.getFirst().get("close"));
        assertEquals(.5, first.getFirst().get("dividend"));
        assertEquals(100., first.getFirst().get("volume"));
        first.getFirst().put("close", 999.);
        var again = prepared.rows(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3));
        assertEquals(22., again.getFirst().get("close"));
        assertEquals(20, again.getFirst().size());
        assertEquals("2026-01-02T00:00:00Z", again.getFirst().get("trade_date"));
        var splitDay = prepared.rows(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5));
        assertEquals(12., splitDay.getFirst().get("close"));
        assertEquals(2., splitDay.getFirst().get("split_factor"));
    }

    @Test
    void preparationDoesNotHideBadSelectedRowsOrRejectAnUnselectedBadPrice() throws Exception {
        var prepared =
                YahooPriceMapper.prepare(
                        response.replace("\"close\":[11,12]", "\"close\":[11,null]")
                                .getBytes(StandardCharsets.UTF_8),
                        "X",
                        "X",
                        "sha",
                        Instant.EPOCH);
        assertEquals(1, prepared.rows(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3)).size());
        assertThrows(
                IllegalArgumentException.class,
                () -> prepared.rows(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5)));
        assertThrows(
                IllegalArgumentException.class,
                () -> prepared.rows(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5)));
        assertThrows(
                IllegalArgumentException.class,
                () -> prepared.rows(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 1)));
        var duplicate =
                YahooPriceMapper.prepare(
                        response.replace("1767623400", "1767364200")
                                .getBytes(StandardCharsets.UTF_8),
                        "X",
                        "X",
                        "sha",
                        Instant.EPOCH);
        assertTrue(duplicate.rows(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5)).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> duplicate.rows(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3)));
    }
}
