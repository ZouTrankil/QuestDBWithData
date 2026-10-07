package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDailyCanonicalJsonTest {
    @Test void canonicalEvidenceDoesNotChangeSharedOrdinaryMapOrdering() throws Exception {
        var values = new LinkedHashMap<String, Object>();
        values.put("z", 1);
        values.put("a", 2);
        String ordinary = JobDefinitionJson.mapper().writeValueAsString(values);
        assertEquals("{\"z\":1,\"a\":2}", ordinary);
        for (var type : java.util.List.of(MarketSentimentDailyJobService.class, RegimeFeaturesMonitorDailyJobService.class)) {
            var method = type.getDeclaredMethod("canonicalBytes", Object.class);
            method.setAccessible(true);
            assertEquals("{\"a\":2,\"z\":1}", new String((byte[]) method.invoke(null, values), StandardCharsets.UTF_8));
            assertFalse(JobDefinitionJson.mapper().isEnabled(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
            assertEquals(ordinary, JobDefinitionJson.mapper().writeValueAsString(values));
        }
    }
}
