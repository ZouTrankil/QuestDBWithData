package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.client.dto.TushareThsIndexDto;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.ThsIndexRow;
import com.zoutrankil.questdbwithdata.mapper.ThsIndexMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ThsIndexMappingTest {
    @Test void allActualBaselineRowsRoundTripIncludingLetterSuffixedCodesAndMicroseconds() throws Exception {
        var json=JobDefinitionJson.mapper();var rows=json.readTree(Path.of("artifacts/java-migration/D004/physical-baseline.json").toFile())
                .path("catalog").path("rows");assertEquals(2517,rows.size());
        var mapper=new ThsIndexMapper();var codes=new HashSet<String>();
        for(var row:rows) {
            var physical=new ThsIndexRow(text(row,"ts_code"),text(row,"name"),row.path("count").isNull()?null:row.path("count").intValue(),
                    text(row,"exchange"),text(row,"list_date"),text(row,"type"),Instant.parse(row.path("update_time").asText()));
            var domain=mapper.fromStorage(physical);assertTrue(codes.add(domain.tsCode()));
            assertEquals(domain,mapper.fromValues(mapper.values(domain)));assertEquals(physical,mapper.toStorage(domain));
        }
        assertTrue(codes.contains("700012R.TI"));assertTrue(codes.contains("700050B.TI"));
        assertEquals(List.of("ts_code"),ThsIndexDataset.DEFINITION.businessKey());
        assertEquals(List.of("ts_code","update_time"),ThsIndexDataset.DEFINITION.dedupKey());
    }
    @Test void sourceDatesCountsAndObservationPrecisionAreExplicit() {
        var mapper=new ThsIndexMapper();var instant=Instant.parse("2026-09-29T01:02:03.123456Z");
        var mapped=mapper.fromSource(new TushareThsIndexDto("700012R.TI","sample",2,"A","20240229","BB"),instant);
        assertEquals(LocalDate.of(2024,2,29),mapped.listingDate());assertEquals(instant,mapped.observedAt());
        assertNull(mapper.fromSource(new TushareThsIndexDto("700012R.TI",null,null,null,"",null),instant).listingDate());
        assertThrows(RuntimeException.class,()->mapper.fromSource(new TushareThsIndexDto("700012R.TI",null,1,"A","20260229","BB"),instant));
        assertThrows(IllegalArgumentException.class,()->mapper.fromSource(new TushareThsIndexDto("700012R.TI",null,-1,"A",null,"BB"),instant));
        assertThrows(IllegalArgumentException.class,()->mapper.fromSource(new TushareThsIndexDto("700012R.TI",null,1,"A",null,"unknown"),instant));
        assertThrows(IllegalArgumentException.class,()->mapper.fromSource(new TushareThsIndexDto("700012R.TI",null,1,"A",null,"BB"),instant.plusNanos(1)));
    }
    private static String text(com.fasterxml.jackson.databind.JsonNode node,String name) {
        return node.path(name).isNull()?null:node.path(name).asText();
    }
}
