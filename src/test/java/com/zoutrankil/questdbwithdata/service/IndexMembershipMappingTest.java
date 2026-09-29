package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.client.dto.TushareIndexMembershipDto;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.IndexMemberRow;
import com.zoutrankil.questdbwithdata.mapper.IndexMembershipMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexMembershipMappingTest {
    private final IndexMembershipMapper mapper=new IndexMembershipMapper();
    private static String value(JsonNode row,String name) { return row.path(name).isNull()?null:row.path(name).asText(); }
    private static TushareIndexMembershipDto dto(JsonNode r) {
        return new TushareIndexMembershipDto(value(r,"l1_code"),value(r,"l1_name"),value(r,"l2_code"),value(r,"l2_name"),
                value(r,"l3_code"),value(r,"l3_name"),value(r,"ts_code"),value(r,"name"),value(r,"in_date"),value(r,"out_date"),value(r,"is_new"));
    }
    @Test void realCurrentAndHistoricalRowsHaveSeparatePeriodKeysWithoutInventedWeight() throws Exception {
        Path folder=Path.of("artifacts/java-migration/D005/source-preflight-e26420e4-9eda-41a7-9a91-2329ce57419f");
        var keys=new HashSet<IndexMembership.Key>();var stockPeriods=new HashMap<String,Integer>();int count=0;
        for(String mode:List.of("Y","N")) {
            var proof=JobDefinitionJson.mapper().readTree(folder.resolve(mode+"-response.json").toFile());
            for(var row:proof.path("rows")) {
                var typed=mapper.fromSource(dto(row),"801011.SI","林业Ⅱ",Instant.EPOCH);
                assertTrue(keys.add(typed.key()));count++;stockPeriods.merge(typed.tsCode(),1,Integer::sum);
                assertEquals(typed,mapper.fromStorage(mapper.toStorage(typed)));assertNull(typed.weight());assertNull(typed.constituentCode());
                assertEquals(mode,typed.latestFlag());
            }
        }
        assertEquals(7,count);assertEquals(2,stockPeriods.get("000663.SZ"));
    }
    @Test void actualLegacyRowsNormalizeOnlyExplicitNoneDateSentinel() throws Exception {
        var root=JobDefinitionJson.mapper().readTree(Path.of("artifacts/java-migration/D005/physical-baseline.json").toFile());
        int count=0;
        for(var r:root.path("sample").path("rows")) {
            var raw=new IndexMemberRow(value(r,"index_code"),value(r,"ts_code"),Instant.parse(value(r,"update_time")),
                    value(r,"index_name"),value(r,"con_code"),value(r,"con_name"),value(r,"in_date"),value(r,"out_date"),
                    value(r,"is_new"),r.path("weight").isNull()?null:r.path("weight").doubleValue(),value(r,"level"),
                    value(r,"l1_name"),value(r,"l2_name"),value(r,"l3_name"));
            var typed=mapper.fromStorage(raw);var normalized=mapper.toStorage(typed);
            assertEquals(typed,mapper.fromStorage(normalized));assertEquals(raw.updateTime(),normalized.updateTime());
            assertEquals(raw.inDate(),normalized.inDate());
            if("None".equals(raw.outDate())) assertNull(normalized.outDate());
            else assertEquals(raw.outDate(),normalized.outDate());
            count++;
        }
        assertEquals(100,count);
    }
    @Test void invalidSourceDatesAndOutOfScopeRowsAreRejected() {
        var invalid=new TushareIndexMembershipDto("801010.SI","a","801011.SI","b","850131.SI","c","000663.SZ","n","20260229",null,"Y");
        assertThrows(java.time.format.DateTimeParseException.class,()->mapper.fromSource(invalid,"801011.SI","b",Instant.EPOCH));
        var legacySentinel=new TushareIndexMembershipDto("801010.SI","a","801011.SI","b","850131.SI","c","000663.SZ","n","20220729","None","Y");
        assertThrows(java.time.format.DateTimeParseException.class,()->mapper.fromSource(legacySentinel,"801011.SI","b",Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,()->mapper.fromSource(legacySentinel,"801012.SI","b",Instant.EPOCH));
    }
}
