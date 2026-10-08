package com.zoutrankil.data.service;
import com.zoutrankil.data.config.TushareProperties;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class CalendarRatePolicyTest {
    @Test void calendarCannotExceedSourceCeilingOrStricterConfiguredLimit() {
        var p=new TushareProperties();
        assertEquals(10,p.effectiveEndpointLimits().get("trade_cal"));
        p.setEndpointPerMinute(200);p.setEndpointLimits(Map.of("trade_cal",500));
        assertEquals(20,p.effectiveEndpointLimits().get("trade_cal"));
        p.setEndpointLimits(Map.of("trade_cal",5));
        assertEquals(5,p.effectiveEndpointLimits().get("trade_cal"));
    }
}
