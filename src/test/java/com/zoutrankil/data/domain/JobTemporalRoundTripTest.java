package com.zoutrankil.data.domain;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.jupiter.api.Assertions.*;

class JobTemporalRoundTripTest {
    record Times(Instant instant,LocalDate day,Duration timeout,ZoneId zone) {}
    record MonthFields(int z,YearMonth month,int a) {}
    @Test void persistedTemporalValuesRecoverWithoutOptionalModules() throws Exception {
        var json=JobDefinitionJson.mapper();
        var value=new Times(Instant.parse("2026-09-29T06:00:01.123456Z"),LocalDate.of(2026,9,29),
                Duration.ofMinutes(20),ZoneId.of("Asia/Shanghai"));
        assertEquals(value,json.readValue(json.writeValueAsString(value),Times.class));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.readValue("123456",Instant.class));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.readValue("\"2026-02-30\"",LocalDate.class));
    }

    @Test void concurrentDefaultAndCanonicalSerializationKeepIndependentMapOrder() throws Exception {
        var month=YearMonth.of(2026,10);
        var value=new LinkedHashMap<String,Object>();
        value.put("z",7);
        value.put("month",month);
        value.put("a",1);
        var expected=new MonthFields(7,month,1);
        var start=new CountDownLatch(1);
        try (var workers=Executors.newFixedThreadPool(4)) {
            var results=new ArrayList<Future<Void>>();
            for(int worker=0;worker<4;worker++) {
                int offset=worker;
                results.add(workers.submit(() -> {
                    start.await();
                    for(int iteration=0;iteration<50;iteration++) {
                        boolean canonical=(iteration+offset)%2==0;
                        var json=canonical?JobDefinitionJson.canonicalMapper():JobDefinitionJson.mapper();
                        String encoded=json.writeValueAsString(value);
                        assertEquals(canonical
                                ? "{\"a\":1,\"month\":\"2026-10\",\"z\":7}"
                                : "{\"z\":7,\"month\":\"2026-10\",\"a\":1}",encoded);
                        assertEquals(expected,json.readValue(encoded,MonthFields.class));
                    }
                    return null;
                }));
            }
            start.countDown();
            for(var result:results) result.get();
        }
    }
}
