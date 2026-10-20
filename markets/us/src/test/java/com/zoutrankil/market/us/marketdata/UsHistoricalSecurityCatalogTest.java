package com.zoutrankil.market.us.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class UsHistoricalSecurityCatalogTest {
    private byte[] zip(String body) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("supported_tickers.csv"));
            zip.write(
                    ("ticker,exchange,assetType,priceCurrency,startDate,endDate\n" + body)
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private final LocalDate from = LocalDate.of(2006, 1, 1), through = LocalDate.of(2026, 10, 16);

    @Test
    void preservesRetiredAndOtcWindowsButDoesNotPretendMetadataIsCollectedPrices()
            throws Exception {
        byte[] raw =
                zip(
                        "AAPL,NASDAQ,Stock,USD,1980-12-12,2026-10-16\nLEHMQ,PINK,Stock,USD,1980-01-01,2008-09-15\nOLD,NYSE,Stock,USD,1990-01-01,2004-01-01\nETF,NYSE ARCA,ETF,USD,2010-01-01,2026-10-16\nA:BE,PINK,Stock,USD,2010-01-01,2026-10-16\nMISS,OTCQX,Stock,USD,,2026-10-16\nGB,LSE,Stock,GBP,2010-01-01,2026-10-16\nEUR,NYSE,Stock,EUR,2010-01-01,2026-10-16\nNEW,NYSE,Stock,USD,2027-01-01,2027-02-01\n");
        var rows = UsHistoricalSecurityCatalog.rows(raw, "sha", Instant.EPOCH, from, through);
        assertEquals(8, rows.size());
        var bySymbol = new HashMap<String, Map<String, Object>>();
        for (var row : rows) bySymbol.put(row.get("symbol").toString(), row);
        assertEquals(true, bySymbol.get("LEHMQ").get("daily_candidate"));
        assertEquals("2008-09-15T00:00:00Z", bySymbol.get("LEHMQ").get("source_end_date"));
        assertEquals("BEFORE_REQUESTED_HISTORY", bySymbol.get("OLD").get("scope_reason"));
        assertEquals("ETF_SEPARATE_SCOPE", bySymbol.get("ETF").get("scope_reason"));
        assertEquals("SYMBOL_MAPPING_REQUIRED", bySymbol.get("A:BE").get("scope_reason"));
        assertNull(bySymbol.get("A:BE").get("yahoo_symbol"));
        assertNull(bySymbol.get("MISS").get("source_start_date"));
        assertEquals("NON_USD_MAPPING_REQUIRED", bySymbol.get("EUR").get("scope_reason"));
        assertEquals("AFTER_CUTOFF", bySymbol.get("NEW").get("scope_reason"));
        assertEquals(
                2,
                rows.stream().filter(r -> Boolean.TRUE.equals(r.get("daily_candidate"))).count());
    }

    @Test
    void distinctTickerLifecyclesArePreservedAndReversedDatesAreRejected() throws Exception {
        var duplicated =
                zip(
                        "A,NASDAQ,Stock,USD,2000-01-01,2008-01-01\nA,NASDAQ,Stock,USD,2020-01-01,2026-10-16\n");
        var rows =
                UsHistoricalSecurityCatalog.rows(duplicated, "sha", Instant.EPOCH, from, through);
        assertEquals(2, rows.size());
        assertEquals(1L, rows.getFirst().get("source_row"));
        assertEquals(2L, rows.getLast().get("source_row"));
        assertNotEquals(
                rows.getFirst().get("source_start_date"), rows.getLast().get("source_start_date"));
        var reversed = zip("A,NASDAQ,Stock,USD,2026-10-16,2000-01-01\n");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsHistoricalSecurityCatalog.rows(
                                reversed, "sha", Instant.EPOCH, from, through));
    }
}
