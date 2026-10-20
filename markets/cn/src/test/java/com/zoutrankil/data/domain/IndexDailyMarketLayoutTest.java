package com.zoutrankil.data.domain;

import com.zoutrankil.data.domain.index.IndexDailyMarketDataset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IndexDailyMarketLayoutTest {
    @Test void formalV2MatchesTradeDateWhileLegacyIsolatedV1KeepsOriginalLayout() {
        var formal = IndexDailyMarketDataset.DEFINITION;
        var legacy = IndexDailyMarketDataset.legacyIsolatedDefinition("java_d019_index_daily_market_contract");
        assertEquals(2, formal.schemaVersion());
        assertEquals("trade_date", formal.designatedTimestamp());
        assertEquals(List.of("ts_code", "trade_date"), formal.dedupKey());
        assertEquals("trade_date", formal.columns().get(1).storageName());
        assertEquals(1, legacy.schemaVersion());
        assertEquals("timestamp", legacy.designatedTimestamp());
        assertEquals(List.of("ts_code", "timestamp"), legacy.dedupKey());
        assertEquals("timestamp", legacy.columns().get(1).storageName());
        assertEquals(formal.businessKey(), legacy.businessKey());
        assertEquals(formal.columns().get(1).temporal(), legacy.columns().get(1).temporal());
        assertThrows(IllegalStateException.class,
                () -> IndexDailyMarketDataset.legacyIsolatedDefinition("index_daily_market"));
    }
}
