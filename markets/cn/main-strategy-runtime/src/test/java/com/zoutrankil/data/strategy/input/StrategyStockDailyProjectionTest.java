package com.zoutrankil.data.strategy.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.NumericContract;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class StrategyStockDailyProjectionTest {
    @Test
    void generatedQueryPreservesTheOriginalProjectionLeftJoinBoundariesAndOrder() {
        String original =
                """
                SELECT b.trade_date,b.ts_code,b.close AS price,b.open AS open_price,b.close AS raw_close,
                       b.amount,b.up_limit,b.down_limit,b.is_suspended,b.is_st,
                       d.pb,d.turnover_rate,d.total_mv,d.ts_code AS basic_present
                FROM mv_backtest_daily b LEFT JOIN daily_basic d ON b.trade_date=d.trade_date AND b.ts_code=d.ts_code
                WHERE b.trade_date>=cast(? AS TIMESTAMP) AND b.trade_date<cast(? AS TIMESTAMP)
                ORDER BY b.trade_date,b.ts_code
                """;
        assertEquals(
                original.replaceAll("\\s+", ""),
                StrategyStockDailyProjection.querySql().replaceAll("\\s+", ""));
    }

    @Test
    void evidenceTracesAliasesAndDifferentMoneyScalesToTheActualSourceColumns() {
        var evidence = StrategyStockDailyProjection.evidence();
        var fields = (List<?>) evidence.get("fields");
        assertEquals(14, fields.size());
        assertEquals(
                14,
                fields.stream()
                        .map(item -> ((Map<?, ?>) item).get("outputField"))
                        .distinct()
                        .count());
        assertEquals("close", field("price").get("sourcePhysicalField"));
        assertEquals("close", field("raw_close").get("sourcePhysicalField"));
        assertEquals("open", field("open_price").get("sourcePhysicalField"));
        assertEquals("mv_backtest_daily", field("amount").get("sourceDataset"));
        assertEquals("daily_basic", field("total_mv").get("sourceDataset"));
        assertEquals(
                new BigDecimal("1000"),
                ((NumericContract) field("amount").get("numeric")).multiplierToBaseUnit());
        assertEquals(
                new BigDecimal("10000"),
                ((NumericContract) field("total_mv").get("numeric")).multiplierToBaseUnit());
        assertEquals(
                new BigDecimal("0.01"),
                ((NumericContract) field("turnover_rate").get("numeric")).multiplierToBaseUnit());
        assertEquals("instrument_code", field("ts_code").get("logicalField"));
        assertEquals(true, field("basic_present").get("nullableAfterJoin"));
        assertFalse(field("basic_present").containsKey("numeric"));
        assertEquals(false, evidence.get("valuesConverted"));
        assertEquals(false, evidence.get("modelAcceptanceVerified"));
    }

    @Test
    void metadataDoesNotScalePricesAmountsOrTurnoverAtTheModelBoundary() {
        var raw =
                List.<Map<String, Object>>of(
                        Map.of(
                                "trade_date",
                                LocalDate.of(2026, 10, 16),
                                "ts_code",
                                "000001.SZ",
                                "price",
                                12.34,
                                "raw_close",
                                12.34,
                                "open_price",
                                12.0,
                                "amount",
                                2.5,
                                "turnover_rate",
                                3.0,
                                "total_mv",
                                10.0));
        var standard = StrategyInputRows.standard("stock_daily", raw);
        assertEquals(2.5, standard.getFirst().get("amount"));
        assertEquals(3.0, standard.getFirst().get("turnover_rate"));
        assertEquals(10.0, standard.getFirst().get("total_mv"));
        assertEquals(raw, StrategyInputRows.legacyModel("stock_daily", standard));
        assertTrue(((NumericContract) field("price").get("numeric")).priceBasis().contains("未复权"));
    }

    private static Map<?, ?> field(String name) {
        return ((List<?>) StrategyStockDailyProjection.evidence().get("fields"))
                .stream()
                        .map(item -> (Map<?, ?>) item)
                        .filter(item -> name.equals(item.get("outputField")))
                        .findFirst()
                        .orElseThrow();
    }
}
