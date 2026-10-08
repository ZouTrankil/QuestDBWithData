package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen source vs captured existing-table output, not a claim of rerunning Python on those inputs. */
class FrozenSourceParityTest {
    @Test void nativeSourcesMatchFrozenExistingOutputsWithZeroNumericTolerance() throws Exception {
        for(String dataset:List.of("daily","etf_daily","stk_factor")) {
            try(var stream=getClass().getResourceAsStream("/com/zoutrankil/batch/parity/"+dataset+".json")) {
                var fixture=Json.MAPPER.readTree(stream);var source=new LinkedHashMap<String,JsonNode>();
                fixture.path("sourcePages").get(0).get(0).fields().forEachRemaining(entry -> source.put(entry.getKey(),entry.getValue()));
                var contract=SourceContract.load(dataset);
                var row=contract.normalize(source,LocalDate.parse(fixture.path("logicalDate").asText()),Set.of(fixture.path("code").asText()));
                var reference=fixture.path("referenceQuery");assertEquals(1,reference.path("dataset").size());
                for(int i=0;i<reference.path("columns").size();i++) {
                    String field=reference.path("columns").get(i).path("name").asText();
                    JsonNode value=reference.path("dataset").get(0).get(i);Object normalized=row.get(field);
                    if(field.equals(contract.timestampColumn())) {
                        assertEquals(Instant.parse(value.asText()),LocalDate.parse(normalized.toString()).atStartOfDay(ZoneOffset.UTC).toInstant());
                    } else if(value.isNull()) assertNull(normalized,field);
                    else if(value.isNumber()) assertEquals(value.doubleValue(),((Number)normalized).doubleValue(),0.0,field);
                    else assertEquals(value.asText(),normalized,field);
                }
            }
        }
    }
}
