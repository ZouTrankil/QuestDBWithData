package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.market.btc.archive.application.BinanceArchiveOverlap;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class BinanceArchiveOverlapTest {
    private Map<String, Object> row(double rate, Integer hours, String sha) {
        var row = new LinkedHashMap<String, Object>();
        row.put("funding_time", "2026-10-01T00:00:00Z");
        row.put("exchange", "BINANCE");
        row.put("symbol", "BTCUSDT");
        row.put("instrument", "USDT_PERPETUAL");
        row.put("funding_rate", rate);
        row.put("funding_interval_hours", hours);
        row.put("source_sha256", sha.repeat(64));
        row.put("observed_at", "2026-10-21T00:00:00Z");
        return row;
    }

    @Test
    void monthlyMayFillUnknownIntervalButCannotSilentlyChangeRatesOrClearKnownInterval() {
        var d = BinanceArchiveDefinitions.definition(BinanceArchiveDataset.FUNDING);
        assertTrue(
                BinanceArchiveOverlap.conflicts(
                                d, List.of(row(.01, 8, "b")), List.of(row(.01, null, "a")))
                        .isEmpty());
        assertEquals(
                "funding_rate",
                BinanceArchiveOverlap.conflicts(
                                d, List.of(row(.02, 8, "b")), List.of(row(.01, 8, "a")))
                        .getFirst()
                        .get("field"));
        assertEquals(
                "funding_interval_hours",
                BinanceArchiveOverlap.conflicts(
                                d, List.of(row(.01, null, "b")), List.of(row(.01, 8, "a")))
                        .getFirst()
                        .get("field"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        BinanceArchiveOverlap.conflicts(
                                d,
                                List.of(row(.01, 8, "b")),
                                List.of(row(.01, 8, "a"), row(.01, 8, "a"))));
    }
}
