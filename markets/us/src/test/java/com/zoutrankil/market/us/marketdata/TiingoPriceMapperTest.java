package com.zoutrankil.market.us.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

class TiingoPriceMapperTest {
    private static final Instant OBSERVED = Instant.parse("2026-10-19T16:33:00Z");

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] distribution(String date, double amount, double split) {
        return bytes(
                """
                [{"date":"%sT00:00:00Z","open":32,"high":33,"low":31,"close":32.5,"volume":100,
                  "adjOpen":22,"adjHigh":23,"adjLow":21,"adjClose":22.5,"adjVolume":100,"divCash":%s,"splitFactor":%s}]
                """
                        .formatted(date, amount, split));
    }

    @Test
    void restoresDeclaredFractionalCashWithoutChangingSourcePricesOrQuantity() throws Exception {
        var down =
                TiingoPriceMapper.dailyMapping(
                        distribution("2007-12-13", 0.07, 1),
                        "US000000009458",
                        "b".repeat(64),
                        OBSERVED,
                        LocalDate.of(2013, 1, 7));
        var up =
                TiingoPriceMapper.dailyMapping(
                        distribution("2008-12-08", 0.08, 1),
                        "US000000009458",
                        "b".repeat(64),
                        OBSERVED,
                        LocalDate.of(2013, 1, 7));
        for (var mapped : List.of(down, up)) {
            var row = mapped.rows().getFirst();
            assertEquals(0.075, row.get("dividend"));
            assertEquals(1.0, row.get("split_factor"));
            assertEquals(32.5, row.get("close"));
            assertEquals(22.5, row.get("adj_close"));
            assertEquals(100.0, row.get("volume"));
            assertEquals("b".repeat(64), row.get("source_sha256"));
            assertEquals(OBSERVED.toString(), row.get("observed_at"));
            assertEquals(new HashSet<>(UsMarketDatasets.DAILY.storageColumns()), row.keySet());
            assertEquals(0, mapped.nonCashDistributions().size());
            assertEquals(
                    "VERIFIED_CASH_AMOUNT",
                    mapped.cashDividendCorrections().getFirst().get("eventType"));
        }
        assertEquals(0.07, down.cashDividendCorrections().getFirst().get("sourceDivCash"));
        assertEquals(0.08, up.cashDividendCorrections().getFirst().get("sourceDivCash"));
        assertEquals(0.075, down.cashDividendCorrections().getFirst().get("cashDividend"));
    }

    @Test
    void separatesDeclaredSpecialCashFromSourceAdjustmentAmount() throws Exception {
        var mapped =
                TiingoPriceMapper.dailyMapping(
                        distribution("2013-06-10", 1.49, 1),
                        "US000000041341",
                        "b".repeat(64),
                        OBSERVED,
                        LocalDate.of(2013, 11, 5));
        assertEquals(1.5, mapped.rows().getFirst().get("dividend"));
        var evidence = mapped.cashDividendCorrections().getFirst();
        assertEquals(1.49, evidence.get("sourceDivCash"));
        assertEquals("2013-06-10", evidence.get("sourceAdjustmentDate"));
        assertEquals(
                "https://www.sec.gov/Archives/edgar/data/12978/000119312513203859/d533500dex991.htm",
                evidence.get("primarySourceUrl"));
    }

    @Test
    void restoresOnlyVerifiedOrdinaryCashAndRetainsItsOriginalAmount() throws Exception {
        var mapped =
                TiingoPriceMapper.dailyMapping(
                        distribution("2007-06-27", 0.16, 1),
                        "US000000041341",
                        "b".repeat(64),
                        OBSERVED,
                        LocalDate.of(2013, 11, 5));
        assertEquals(0.15, mapped.rows().getFirst().get("dividend"));
        assertEquals(0.16, mapped.cashDividendCorrections().getFirst().get("sourceDivCash"));
    }

    @Test
    void rejectsCashSourceRevisionsAndConflictingSplitsBeforePublication() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TiingoPriceMapper.daily(
                                distribution("2007-12-13", 0.075, 1),
                                "US000000009458",
                                "b".repeat(64),
                                OBSERVED,
                                LocalDate.of(2013, 1, 7)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TiingoPriceMapper.daily(
                                distribution("2013-06-10", 1.49, 2),
                                "US000000041341",
                                "b".repeat(64),
                                OBSERVED,
                                LocalDate.of(2013, 11, 5)));
    }

    @Test
    void doesNotTransferVerifiedCashToReusableSymbolsOrUnlistedEventDates() throws Exception {
        var other =
                TiingoPriceMapper.dailyMapping(
                        distribution("2013-06-10", 1.49, 1),
                        "OMX",
                        "b".repeat(64),
                        OBSERVED,
                        LocalDate.of(2013, 11, 5));
        var unknown =
                TiingoPriceMapper.dailyMapping(
                        distribution("2011-06-09", 0.07, 1),
                        "US000000009458",
                        "b".repeat(64),
                        OBSERVED,
                        LocalDate.of(2013, 1, 7));
        assertEquals(1.49, other.rows().getFirst().get("dividend"));
        assertEquals(0.07, unknown.rows().getFirst().get("dividend"));
        assertEquals(0, other.cashDividendCorrections().size());
        assertEquals(0, unknown.cashDividendCorrections().size());
    }

    @Test
    void skipsFutureCashCorrectionBeforeInspectingItsRevisedSourceFields() throws Exception {
        var mapped =
                TiingoPriceMapper.dailyMapping(
                        distribution("2013-06-10", 2, 2),
                        "US000000041341",
                        "b".repeat(64),
                        OBSERVED,
                        LocalDate.of(2013, 6, 9));
        assertEquals(0, mapped.rows().size());
        assertEquals(0, mapped.cashDividendCorrections().size());
    }

    @Test
    void separatesVerifiedStockDistributionFromCashAndKeepsOriginalEvidence() throws Exception {
        var mapped =
                TiingoPriceMapper.dailyMapping(
                        distribution("2014-04-07", 9.52618, 1),
                        "US000000086368",
                        "a".repeat(64),
                        OBSERVED,
                        LocalDate.of(2022, 10, 31));
        var row = mapped.rows().getFirst();
        assertEquals(0.0, row.get("dividend"));
        assertEquals(1.0, row.get("split_factor"));
        assertEquals(32.5, row.get("close"));
        assertEquals(22.5, row.get("adj_close"));
        assertEquals(new HashSet<>(UsMarketDatasets.DAILY.storageColumns()), row.keySet());
        var evidence = mapped.nonCashDistributions().getFirst();
        assertEquals(9.52618, evidence.get("sourceDivCash"));
        assertEquals(0.0, evidence.get("cashDividend"));
        assertEquals(0.300795, evidence.get("sharesPerShare"));
        assertEquals("2014-04-04", evidence.get("distributionDate"));
        assertEquals("2014-04-07", evidence.get("sourceAdjustmentDate"));
        assertEquals("NON_CASH_SPIN_OFF", evidence.get("eventType"));
    }

    @Test
    void rejectsRevisedSourceAmountRatherThanGuessingAnotherCashValue() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TiingoPriceMapper.daily(
                                distribution("2014-04-07", 9.52619, 1),
                                "US000000086368",
                                "a".repeat(64),
                                OBSERVED,
                                LocalDate.of(2022, 10, 31)));
    }

    @Test
    void rejectsConflictingQuantitySplitOnVerifiedStockDistribution() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TiingoPriceMapper.daily(
                                distribution("2014-04-07", 9.52618, 2),
                                "US000000086368",
                                "a".repeat(64),
                                OBSERVED,
                                LocalDate.of(2022, 10, 31)));
    }

    @Test
    void doesNotApplyOldCommonShareRuleToReusedTickerOrAnotherBusinessDate() throws Exception {
        var etf =
                TiingoPriceMapper.dailyMapping(
                        distribution("2014-04-07", 9.52618, 1),
                        "SHLD",
                        "a".repeat(64),
                        OBSERVED,
                        LocalDate.of(2026, 10, 16));
        assertEquals(9.52618, etf.rows().getFirst().get("dividend"));
        assertEquals(0, etf.nonCashDistributions().size());
        var otherDay =
                TiingoPriceMapper.dailyMapping(
                        distribution("2014-04-08", 0.5, 1),
                        "US000000086368",
                        "a".repeat(64),
                        OBSERVED,
                        LocalDate.of(2022, 10, 31));
        assertEquals(0.5, otherDay.rows().getFirst().get("dividend"));
        assertEquals(0, otherDay.nonCashDistributions().size());
    }

    @Test
    void ignoresUnrequestedFutureEventBeforeApplyingDividendPolicy() throws Exception {
        var mapped =
                TiingoPriceMapper.dailyMapping(
                        distribution("2014-04-07", 9.52619, 2),
                        "US000000086368",
                        "a".repeat(64),
                        OBSERVED,
                        LocalDate.of(2014, 4, 6));
        assertEquals(0, mapped.rows().size());
        assertEquals(0, mapped.nonCashDistributions().size());
    }

    @Test
    void preservesUnadjustedPricesAndCorporateActionInsteadOfReplacingThemWithAdjustedPrices()
            throws Exception {
        var rows =
                TiingoPriceMapper.daily(
                        bytes(
                                """
                [{"date":"2006-01-03T00:00:00Z","open":72.332,"high":74.75,"low":72.25,"close":74.75,"volume":28829800,
                  "adjOpen":2.16,"adjHigh":2.23,"adjLow":2.15,"adjClose":2.23,"adjVolume":807235207,"divCash":0.15,"splitFactor":4}]
                """),
                        "AAPL",
                        "a".repeat(64),
                        OBSERVED,
                        LocalDate.of(2026, 10, 16));
        var row = rows.getFirst();
        assertEquals(74.75, row.get("close"));
        assertEquals(2.23, row.get("adj_close"));
        assertEquals(0.15, row.get("dividend"));
        assertEquals(4., row.get("split_factor"));
        assertEquals(28829800., row.get("volume"));
        assertEquals(new HashSet<>(UsMarketDatasets.DAILY.storageColumns()), row.keySet());
    }

    @Test
    void rejectsResponseAtTheObservedSilentTruncationBoundary() {
        String source = "[" + String.join(",", Collections.nCopies(10000, "{}")) + "]";
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TiingoPriceMapper.minute(
                                bytes(source),
                                "SPY",
                                "a",
                                OBSERVED,
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 10, 16)));
    }

    @Test
    void usesNewYorkTradingDateAndAggregatesOnlyObservedMinutes() throws Exception {
        var rows =
                TiingoPriceMapper.minute(
                        bytes(
                                """
                [{"date":"2026-01-02T14:30:00Z","open":10,"high":12,"low":9,"close":11,"volume":3},
                 {"date":"2026-01-02T14:32:00Z","open":11,"high":13,"low":10,"close":12,"volume":4}]
                """),
                        "SPY",
                        "a",
                        OBSERVED,
                        LocalDate.of(2026, 1, 2),
                        LocalDate.of(2026, 1, 2));
        var five = TiingoPriceMapper.fiveMinute(rows).getFirst();
        assertEquals("2026-01-02T14:30:00Z", five.get("ts"));
        assertEquals("2026-01-02T00:00:00Z", five.get("trade_date"));
        assertEquals(10., five.get("open"));
        assertEquals(12., five.get("close"));
        assertEquals(13., five.get("high"));
        assertEquals(9., five.get("low"));
        assertEquals(7., five.get("volume"));
        assertEquals("IEX", five.get("venue"));
    }

    @Test
    void unknownVenueVolumeRemainsNullAfterAggregation() throws Exception {
        var rows =
                TiingoPriceMapper.minute(
                        bytes(
                                "[{\"date\":\"2026-07-01T13:30:00Z\",\"open\":1,\"high\":1,\"low\":1,\"close\":1}]"),
                        "QQQ",
                        "a",
                        OBSERVED,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 1));
        assertNull(TiingoPriceMapper.fiveMinute(rows).getFirst().get("volume"));
    }

    @Test
    void rejectsDuplicateDatesAndDoesNotConvertMalformedNumbersToZero() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TiingoPriceMapper.daily(
                                bytes("[{\"date\":\"2026-01-02T00:00:00Z\",\"open\":\"bad\"}]"),
                                "SPY",
                                "a",
                                OBSERVED,
                                LocalDate.of(2026, 1, 2)));
    }
}
