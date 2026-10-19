package com.zoutrankil.market.us.source;

import com.zoutrankil.batch.source.*;
import com.zoutrankil.batch.support.Json;
import com.zoutrankil.data.domain.temporal.MarketId;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsSourcePolicyProviderTest {
    @Test void ratesKeepTheirSourceDateNullsDecimalsAndRevisionKeys() throws Exception {
        var contract=SourceContract.load("us_tbr");var day=LocalDate.parse("2026-10-09");
        var source=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        contract.columns().forEach(c->source.put(c.source(),Json.MAPPER.nullNode()));
        source.put("date",Json.MAPPER.valueToTree("20261009"));
        source.put("w4_bd",Json.MAPPER.valueToTree("4.125"));source.put("w4_ce",Json.MAPPER.valueToTree(4.13));
        var row=contract.normalize(source,day,Set.of());
        assertEquals("2026-10-09",row.get("date"));assertEquals(4.125,row.get("w4_bd"));assertNull(row.get("w8_bd"));
        assertEquals(row,contract.validateNormalized(row,day,Set.of()));
        assertEquals("[\"2026-10-09\"]",contract.key(row));
        assertEquals(MarketId.US,SourceStrategyRegistry.provider("us_tbr").market());
        assertTrue(SourceStrategyRegistry.provider("us_tbr").usesSourceDates("us_tbr"));
        assertEquals(List.of(),SourceQuality.failures("us_tbr",List.of(row)));
        assertThrows(IllegalArgumentException.class,()->contract.normalize(source,day.plusDays(1),Set.of()));
    }
    @Test void providerOnlyDeclaresUsSourcesWithoutInventingBtcJobs() {
        assertEquals(Set.of("us_tbr"),new UsSourcePolicyProvider().strategies().keySet());
        assertTrue(SourceStrategyRegistry.registeredJobs().contains("source_us_tbr"));
        assertTrue(new UsSourcePolicyProvider().applicationJobs().isEmpty());
        assertFalse(SourceContract.SUPPORTED.contains("btc_daily"));
    }
    @Test void sourceDatePlanningDoesNotDropDatesBecauseAnUnrelatedMarketIsClosed() {
        assertEquals(List.of(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-02")),
                SourceDatePartitions.plan(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-02"),2));
        assertThrows(IllegalArgumentException.class,()->SourceDatePartitions.plan(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-03"),2));
    }
}
