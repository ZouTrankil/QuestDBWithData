package com.zoutrankil.data.strategy.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.strategy.score.FrozenScalarValues;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class StrategyInputRowsTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

    @Test
    void dailyIdentityChangesWithoutScalingOrFillingFeatures() {
        var source =
                FrozenScalarValues.copyOf(
                        Map.of(
                                "timestamp",
                                DAY,
                                "ts_code",
                                "510300.SH",
                                "amount",
                                123.45,
                                "vol",
                                400.));
        var rows = StrategyInputRows.standard("etf_daily", List.of(source));
        var row = rows.getFirst();
        assertEquals(DAY, row.get("trade_date"));
        assertEquals("510300.SH", row.get("instrument_code"));
        assertEquals(123.45, row.get("amount"));
        assertEquals(400., row.get("vol"));
        assertFalse(row.containsKey("timestamp"));
        assertFalse(row.containsKey("turnover_rate"));
        assertEquals(source, StrategyInputRows.legacyModel("etf_daily", rows).getFirst());
        assertThrows(UnsupportedOperationException.class, () -> row.put("amount", 0));
    }

    @Test
    void holdingsKeepTwoInstrumentsAndTwoDatesAndNeverTurnSharesIntoMoney() {
        var original =
                Map.<String, Object>of(
                        "ts_code",
                        "510300.SH",
                        "symbol",
                        "000001.SZ",
                        "ann_date",
                        DAY,
                        "end_date",
                        DAY.minusDays(15),
                        "amount",
                        5000.,
                        "mkv",
                        43210.,
                        "stk_mkv_ratio",
                        4.2);
        var rows = StrategyInputRows.standard("etf_portfolio", List.of(original));
        var row = rows.getFirst();
        assertEquals("510300.SH", row.get("etf_code"));
        assertEquals("000001.SZ", row.get("constituent_code"));
        assertEquals(DAY, row.get("announcement_date"));
        assertEquals(DAY.minusDays(15), row.get("report_period_end_date"));
        assertEquals(5000., row.get("holding_quantity"));
        assertEquals(43210., row.get("holding_market_value"));
        assertEquals(4.2, row.get("holding_market_value_ratio"));
        assertEquals(original, StrategyInputRows.legacyModel("etf_portfolio", rows).getFirst());
    }

    @Test
    void staticEpochAndNullableDatesDoNotBecomeTradingDatesOrAvailability() {
        var original =
                Map.<String, Object>of(
                        "ts_code",
                        "510300.SH",
                        "timestamp",
                        LocalDate.ofEpochDay(0),
                        "list_date",
                        "20260930",
                        "found_date",
                        "20260830",
                        "delist_date",
                        "Null");
        var rows = StrategyInputRows.standard("etf_reference", List.of(original));
        var row = rows.getFirst();
        assertEquals("1970-01-01T00:00:00Z", row.get("technical_partition_marker"));
        assertEquals(LocalDate.of(2026, 9, 30), row.get("listing_date"));
        assertEquals(LocalDate.of(2026, 8, 30), row.get("establishment_date"));
        assertNull(row.get("delisting_date"));
        assertFalse(row.containsKey("trade_date"));
        assertFalse(row.containsKey("available_at"));
        var legacy = StrategyInputRows.legacyModel("etf_reference", rows).getFirst();
        assertEquals(LocalDate.ofEpochDay(0), legacy.get("timestamp"));
        assertEquals("20260930", legacy.get("list_date"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        StrategyInputRows.standard(
                                "etf_reference", List.of(Map.of("timestamp", DAY))));
    }

    @Test
    void calendarNoneAndStockDelistingNoneHaveExplicitNullRules() {
        var calendar =
                StrategyInputRows.standard(
                                "calendar",
                                List.of(
                                        Map.of(
                                                "cal_date",
                                                DAY,
                                                "exchange",
                                                "SSE",
                                                "pretrade_date",
                                                "None")))
                        .getFirst();
        assertEquals(DAY, calendar.get("calendar_date"));
        assertNull(calendar.get("previous_trade_date"));
        var stock =
                StrategyInputRows.standard(
                                "stock_metadata",
                                List.of(
                                        Map.of(
                                                "ts_code",
                                                "000001.SZ",
                                                "symbol",
                                                "000001",
                                                "delist_date",
                                                "None")))
                        .getFirst();
        assertEquals("000001.SZ", stock.get("instrument_code"));
        assertEquals("000001", stock.get("local_code"));
        assertNull(stock.get("delisting_date"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        StrategyInputRows.standard(
                                "calendar", List.of(Map.of("pretrade_date", "unknown"))));
    }

    @Test
    void mixedAliasesCannotSilentlyOverwriteAFieldAndV1TablesStayTheSameObject() {
        var mixed =
                new LinkedHashMap<String, Object>(
                        Map.of("ts", DAY, "trade_date", DAY, "symbol", "000001.SZ"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StrategyInputRows.standard("l2_raw", List.of(mixed)));
        Map<String, List<Map<String, Object>>> tables = Map.of("l2_raw", List.of(mixed));
        assertSame(tables, StrategyInputRows.standardTables(StrategyInputSchema.V1, tables));
        assertThrows(
                IllegalArgumentException.class,
                () -> StrategyInputRows.standard("unknown", List.of()));
    }
}
