package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

class FredMacroCollectorTest {
    @Test
    void keepsOriginalDefaultOrderAndAllowsOneIndependentKnownSeries() {
        assertEquals(
                List.of("NASDAQCOM", "SP500", "VIXCLS"), FredMacroCollector.selectedSeries(null));
        for (String series : List.of("NASDAQCOM", "SP500", "VIXCLS"))
            assertEquals(List.of(series), FredMacroCollector.selectedSeries(series));
    }

    @Test
    void rejectsUnknownEmptyAndCombinedSeriesBeforeAnySourceRequest() {
        for (String series :
                List.of("", " ", "sp500", "UNKNOWN", "SP500,VIXCLS", "VIXCLS&api_key=x"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> FredMacroCollector.selectedSeries(series));
    }
}
