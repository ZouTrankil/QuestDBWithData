package com.zoutrankil.market.us.app;

import com.zoutrankil.runtime.ProjectConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsMarketDataConfigurationTest {
    @Test void loadsIndexedYamlListsThroughTheIndependentProfile() {
        var symbols=UsCoreCollector.configuredSymbols(ProjectConfiguration.load("us-data"));
        assertEquals(16,symbols.size());assertTrue(symbols.containsAll(java.util.List.of("SPY","QQQ","IWM","TLT","GLD","USO","AAPL","NVDA")));
        var page=UsBroadDailyCollector.publicationPage(ProjectConfiguration.load("us-data"));assertEquals(10000,page.rows());assertEquals(8388608,page.bytes());
    }
}
