package com.zoutrankil.market.us.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

class FredMarketDailyTest {
    private final String input =
            """
        {"count":2,"offset":0,"observations":[{"date":"2026-01-01","value":"."},{"date":"2026-01-02","value":"12345.67"}]}
        """;

    @Test
    void sourceMissingValuesDoNotBecomeZeroAndIncompletePaginationIsRejected() throws Exception {
        var from = LocalDate.of(2026, 1, 1);
        var through = LocalDate.of(2026, 1, 2);
        var parsed =
                FredMarketDaily.parse(
                        input.getBytes(StandardCharsets.UTF_8),
                        "NASDAQCOM",
                        Instant.EPOCH,
                        from,
                        through);
        assertEquals(1, parsed.missingValues());
        assertEquals(1, parsed.rows().size());
        assertEquals(12345.67, parsed.rows().getFirst().get("close"));
        assertEquals("NASDAQCOM.US", parsed.rows().getFirst().get("ts_code"));
        assertEquals("2026-01-02T00:00:00Z", parsed.rows().getFirst().get("trade_date"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FredMarketDaily.parse(
                                input.replace("\"count\":2", "\"count\":3")
                                        .getBytes(StandardCharsets.UTF_8),
                                "NASDAQCOM",
                                Instant.EPOCH,
                                from,
                                through));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FredMarketDaily.parse(
                                input.getBytes(StandardCharsets.UTF_8),
                                "NASDAQCOM",
                                Instant.EPOCH,
                                from,
                                from));
    }
}
