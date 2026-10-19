package com.zoutrankil.data.service;

import com.zoutrankil.data.config.TushareProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockDailyRatePolicyTest {
    @Test
    void stockDailyEndpointsNeverExceedTheirAuditedCeilingsOrSharedBudget() {
        var properties = new TushareProperties();
        properties.setEndpointPerMinute(500);

        var limits = properties.effectiveEndpointLimits();
        assertEquals(450, limits.get("daily"));
        assertEquals(20, limits.get("daily_basic"));
        assertEquals(25, limits.get("stk_factor_pro"));

        properties.setEndpointPerMinute(10);
        limits = properties.effectiveEndpointLimits();
        assertEquals(10, limits.get("daily"));
        assertEquals(10, limits.get("daily_basic"));
        assertEquals(10, limits.get("stk_factor_pro"));
    }

    @Test
    void endpointOverridesCanOnlyLowerTheAuditedCeilings() {
        var properties = new TushareProperties();
        properties.setEndpointPerMinute(500);
        properties.setEndpointLimits(Map.of("daily", 30, "daily_basic", 5, "stk_factor_pro", 15));

        var limits = properties.effectiveEndpointLimits();
        assertEquals(30, limits.get("daily"));
        assertEquals(5, limits.get("daily_basic"));
        assertEquals(15, limits.get("stk_factor_pro"));
    }
}
