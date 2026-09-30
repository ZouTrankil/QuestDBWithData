package com.zoutrankil.data.domain;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class JobTemporalRoundTripTest {
    record Times(Instant instant,LocalDate day,Duration timeout,ZoneId zone) {}
    @Test void persistedTemporalValuesRecoverWithoutOptionalModules() throws Exception {
        var json=JobDefinitionJson.mapper();
        var value=new Times(Instant.parse("2026-09-29T06:00:01.123456Z"),LocalDate.of(2026,9,29),
                Duration.ofMinutes(20),ZoneId.of("Asia/Shanghai"));
        assertEquals(value,json.readValue(json.writeValueAsString(value),Times.class));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.readValue("123456",Instant.class));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.readValue("\"2026-02-30\"",LocalDate.class));
    }
}
