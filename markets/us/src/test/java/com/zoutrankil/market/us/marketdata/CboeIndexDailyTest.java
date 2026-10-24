package com.zoutrankil.market.us.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

class CboeIndexDailyTest {
    private static final String SHA = "a".repeat(64);
    private static final Instant OBSERVED = Instant.parse("2026-01-07T05:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 2);
    private static final LocalDate THROUGH = LocalDate.of(2026, 1, 6);
    private static final String CSV =
            "DATE,OPEN,HIGH,LOW,CLOSE\n"
                    + "01/02/2026,41.6,13.61,12.76,12.83\n01/06/2026,14,16,12,15\n";

    @Test
    void keepsOfficialCloseAndExplicitProviderWithoutInventingOhlc() {
        var parsed = parse(CSV);
        assertEquals(2, parsed.sourceRows());
        assertEquals(7, parsed.rows().getFirst().size());
        assertEquals(12.83, parsed.rows().getFirst().get("close"));
        assertEquals("2026-01-02T00:00:00Z", parsed.rows().getFirst().get("trade_date"));
        assertEquals("CBOE", parsed.rows().getFirst().get("provider"));
        assertEquals("INDEX_POINTS", parsed.rows().getFirst().get("value_unit"));
        assertFalse(parsed.rows().getFirst().containsKey("open"));
    }

    @Test
    void rejectsMaintenanceMissingCloseAndUncoveredTail() {
        assertThrows(IllegalArgumentException.class, () -> parse("<html>maintenance</html>"));
        assertThrows(IllegalArgumentException.class, () -> parse(CSV.replace(",12.83", ",NaN")));
        assertThrows(IllegalArgumentException.class, () -> parse(CSV.replace(",12.83", ",0")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parse(CSV.replace("01/06/2026", "01/05/2026")));
    }

    @Test
    void rejectsDuplicateUnsortedAndFutureDates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> parse(CSV.replace("01/06/2026", "01/02/2026")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parse(CSV.replace("01/02/2026", "01/07/2026")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parse(CSV.replace("01/06/2026", "01/08/2026")));
    }

    private static CboeIndexDaily.Parsed parse(String csv) {
        return CboeIndexDaily.parse(
                csv.getBytes(StandardCharsets.UTF_8), SHA, OBSERVED, FROM, THROUGH);
    }
}
