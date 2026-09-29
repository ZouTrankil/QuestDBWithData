package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.questdbwithdata.client.dto.TushareStockDetailDto;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.StockDetailInfoRow;
import com.zoutrankil.questdbwithdata.mapper.StockDetailInfoMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StockDetailInfoMappingTest {
    private final StockDetailInfoMapper mapper=new StockDetailInfoMapper();
    private final Instant observed=Instant.parse("2026-09-29T00:00:00.123456Z");
    private TushareStockDetailDto source(String listed,String delisted) {
        return new TushareStockDetailDto("000003.SZ","000003","test","area","industry","full","English","spell",
                "main","SZSE","CNY","D",listed,delisted,null,null,null);
    }
    @Test void completeSourceAndStorageRoundTripRetainsEveryFieldAndDateType() {
        var row=mapper.fromSource(source("19910703","20020614"),observed);
        assertEquals(row,mapper.fromStorage(mapper.toStorage(row)));
        assertEquals(row,mapper.fromValues(mapper.values(row)));
        assertEquals(18,mapper.values(row).asMap().size());
        assertEquals(LocalDate.of(1991,7,3),row.listingDate());
        assertEquals(List.of("ts_code"),StockDetailInfoDataset.DEFINITION.businessKey());
        assertFalse(StockDetailInfoDataset.DEFINITION.wal());
        assertNull(StockDetailInfoDataset.DEFINITION.designatedTimestamp());
    }
    @Test void strictNewSourceRejectsMalformedDatesAndPrecisionLoss() {
        assertTrue(StockDetailInfo.validCode("T600018.SH"));
        assertFalse(StockDetailInfo.validCode("T600018.SZ"));
        assertFalse(StockDetailInfo.validCode("600018.SH;DROP"));
        for(String invalid:List.of("None","null","20260230","2026-01-01"," 19910703"))
            assertThrows(java.time.format.DateTimeParseException.class,()->mapper.fromSource(source(invalid,null),observed));
        assertThrows(IllegalArgumentException.class,()->mapper.fromSource(source("20000101","19990101"),observed));
        assertThrows(IllegalArgumentException.class,()->mapper.fromSource(source(null,null),observed.plusNanos(1)));
        assertNull(mapper.fromSource(source("",null),observed).listingDate());
    }
    @Test void capturedActualRowsPreserveEpochAndNormalizeOnlyLegacyDateNone() throws Exception {
        var json=new ObjectMapper();var samples=json.readTree(Path.of("artifacts/java-migration/D002/physical-baseline.json").toFile())
                .at("/sample/rows");
        assertEquals(3,samples.size());int legacyNulls=0;
        for(var node:samples) {
            long micros=node.get("update_micros").longValue();
            var instant=Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000),Math.floorMod(micros,1_000_000)*1000L);
            var physical=new StockDetailInfoRow(text(node,"ts_code"),instant,text(node,"symbol"),text(node,"name"),
                    text(node,"market"),text(node,"exchange"),text(node,"list_status"),text(node,"list_date"),
                    text(node,"fullname"),text(node,"enname"),text(node,"cnspell"),text(node,"area"),text(node,"industry"),
                    text(node,"curr_type"),text(node,"delist_date"),text(node,"is_hs"),text(node,"act_name"),text(node,"act_ent_type"));
            var domain=mapper.fromStorage(physical);
            assertEquals(physical.updateTime(),domain.observedAt());
            assertEquals(domain,mapper.fromValues(mapper.values(domain)));
            var normalized=mapper.toStorage(domain);
            if("None".equals(physical.delistDate())) { legacyNulls++;assertNull(normalized.delistDate()); }
            assertEquals(domain,mapper.fromStorage(normalized));
        }
        assertEquals(2,legacyNulls);
    }
    private static String text(JsonNode node,String field) { return node.get(field).isNull()?null:node.get(field).textValue(); }
}
