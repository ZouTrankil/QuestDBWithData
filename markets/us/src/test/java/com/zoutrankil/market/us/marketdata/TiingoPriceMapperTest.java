package com.zoutrankil.market.us.marketdata;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TiingoPriceMapperTest {
    private static final Instant OBSERVED=Instant.parse("2026-10-19T16:33:00Z");
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    @Test void preservesUnadjustedPricesAndCorporateActionInsteadOfReplacingThemWithAdjustedPrices()throws Exception {
        var rows=TiingoPriceMapper.daily(bytes("""
                [{"date":"2006-01-03T00:00:00Z","open":72.332,"high":74.75,"low":72.25,"close":74.75,"volume":28829800,
                  "adjOpen":2.16,"adjHigh":2.23,"adjLow":2.15,"adjClose":2.23,"adjVolume":807235207,"divCash":0.15,"splitFactor":4}]
                """),"AAPL","a".repeat(64),OBSERVED,LocalDate.of(2026,10,16));
        var row=rows.getFirst();assertEquals(74.75,row.get("close"));assertEquals(2.23,row.get("adj_close"));
        assertEquals(0.15,row.get("dividend"));assertEquals(4.,row.get("split_factor"));assertEquals(28829800.,row.get("volume"));
        assertEquals(new HashSet<>(UsMarketDatasets.DAILY.storageColumns()),row.keySet());
    }
    @Test void rejectsResponseAtTheObservedSilentTruncationBoundary() {
        String source="["+String.join(",",Collections.nCopies(10000,"{}"))+"]";
        assertThrows(IllegalArgumentException.class,()->TiingoPriceMapper.minute(bytes(source),"SPY","a",OBSERVED,LocalDate.of(2026,1,1),LocalDate.of(2026,10,16)));
    }
    @Test void usesNewYorkTradingDateAndAggregatesOnlyObservedMinutes()throws Exception {
        var rows=TiingoPriceMapper.minute(bytes("""
                [{"date":"2026-01-02T14:30:00Z","open":10,"high":12,"low":9,"close":11,"volume":3},
                 {"date":"2026-01-02T14:32:00Z","open":11,"high":13,"low":10,"close":12,"volume":4}]
                """),"SPY","a",OBSERVED,LocalDate.of(2026,1,2),LocalDate.of(2026,1,2));
        var five=TiingoPriceMapper.fiveMinute(rows).getFirst();assertEquals("2026-01-02T14:30:00Z",five.get("ts"));
        assertEquals("2026-01-02T00:00:00Z",five.get("trade_date"));assertEquals(10.,five.get("open"));assertEquals(12.,five.get("close"));
        assertEquals(13.,five.get("high"));assertEquals(9.,five.get("low"));assertEquals(7.,five.get("volume"));assertEquals("IEX",five.get("venue"));
    }
    @Test void unknownVenueVolumeRemainsNullAfterAggregation()throws Exception {
        var rows=TiingoPriceMapper.minute(bytes("[{\"date\":\"2026-07-01T13:30:00Z\",\"open\":1,\"high\":1,\"low\":1,\"close\":1}]"),"QQQ","a",OBSERVED,LocalDate.of(2026,7,1),LocalDate.of(2026,7,1));
        assertNull(TiingoPriceMapper.fiveMinute(rows).getFirst().get("volume"));
    }
    @Test void rejectsDuplicateDatesAndDoesNotConvertMalformedNumbersToZero() {
        assertThrows(IllegalArgumentException.class,()->TiingoPriceMapper.daily(bytes("[{\"date\":\"2026-01-02T00:00:00Z\",\"open\":\"bad\"}]"),"SPY","a",OBSERVED,LocalDate.of(2026,1,2)));
    }
}
