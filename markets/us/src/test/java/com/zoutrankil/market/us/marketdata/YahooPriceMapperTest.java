package com.zoutrankil.market.us.marketdata;

import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YahooPriceMapperTest {
    private final String response="""
        {"chart":{"error":null,"result":[{"meta":{"symbol":"X","currency":"USD","exchangeTimezoneName":"America/New_York","instrumentType":"EQUITY"},
        "timestamp":[1767364200,1767623400],"events":{"splits":{"one":{"date":1767623400,"numerator":2,"denominator":1}},"dividends":{"cash":{"date":1767364200,"amount":0.25}}},
        "indicators":{"quote":[{"open":[10,11],"high":[12,13],"low":[9,10],"close":[11,12],"volume":[200,300]}],"adjclose":[{"adjclose":[10,12]}]}}]}}
        """;
    @Test void reversesFutureSplitsForPricesVolumesAndDividendsWithoutApplyingTheSplitTwiceOnItsDate()throws Exception {
        var rows=YahooPriceMapper.daily(response.getBytes(StandardCharsets.UTF_8),"X","X","sha",Instant.EPOCH,LocalDate.of(2026,1,1),LocalDate.of(2026,1,5));
        assertEquals(2,rows.size());var first=rows.getFirst();assertEquals(22.,first.get("close"));assertEquals(100.,first.get("volume"));assertEquals(.5,first.get("dividend"));assertEquals(10.,first.get("adj_close"));
        assertEquals(12.,rows.getLast().get("close"));assertEquals(2.,rows.getLast().get("split_factor"));assertTrue(first.get("raw_price_basis").toString().contains("RECONSTRUCTED"));
        assertThrows(IllegalArgumentException.class,()->YahooPriceMapper.daily(response.replace("[11,12]","[null,12]").getBytes(StandardCharsets.UTF_8),"X","X","sha",Instant.EPOCH,LocalDate.of(2026,1,1),LocalDate.of(2026,1,5)));
    }
}
