package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.client.dto.TushareTradeCalendarDto;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.ExchangeCalendarRow;
import com.zoutrankil.questdbwithdata.mapper.ExchangeCalendarMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeCalendarMappingTest {
    private final ExchangeCalendarMapper mapper = new ExchangeCalendarMapper();
    @Test void allFourFieldsRoundTripWithoutConvertingDateToMarketTime() {
        var day = mapper.fromSource(new TushareTradeCalendarDto("SSE","20260929",1,"20260928"));
        assertEquals(LocalDate.of(2026,9,29),day.calendarDate());
        assertTrue(day.open());
        assertEquals(Instant.parse("2026-09-29T00:00:00Z"),mapper.toStorage(day).calDate());
        assertEquals(day,mapper.fromStorage(mapper.toStorage(day)));
        assertEquals(day,mapper.fromValues(mapper.values(day)));
        var closed = mapper.fromSource(new TushareTradeCalendarDto("SZSE","20260927",0,null));
        assertFalse(closed.open()); assertNull(closed.previousTradeDate());
        assertEquals(closed,mapper.fromValues(mapper.values(closed)));
        assertNotEquals(day.key(),new ExchangeCalendar("SZSE",day.calendarDate(),true,day.previousTradeDate()).key());
    }
    @Test void ambiguousDatesFlagsAndIntradayCarriersAreRejected() {
        for (String invalid : new String[]{"20260230","2026-09-29","20260929T00:00:00"," 20260929"})
            assertThrows(Exception.class,()->mapper.fromSource(new TushareTradeCalendarDto("SSE",invalid,1,null)));
        for (Integer flag : new Integer[]{null,-1,2})
            assertThrows(IllegalArgumentException.class,()->mapper.fromSource(new TushareTradeCalendarDto("SSE","20260929",flag,null)));
        assertThrows(IllegalArgumentException.class,()->mapper.fromSource(new TushareTradeCalendarDto("SSE","20260929",1,"20260929")));
        assertThrows(IllegalArgumentException.class,()->mapper.fromStorage(new ExchangeCalendarRow(
                "SSE",Instant.parse("2026-09-29T00:00:00.000001Z"),1,null)));
    }
    @Test void capturedActualQuestDbSeptemberRowsRespectDeclaredBusinessDateContract() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Path.of(
                "artifacts/java-migration/D001/physical-baseline.json").toFile());
        var rows = json.at("/sample/rows");
        assertEquals(30,rows.size());
        for (var row : rows) {
            long micros = row.get("date_micros").longValue();
            var physical = new ExchangeCalendarRow(row.get("exchange").textValue(),
                    Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000),Math.floorMod(micros,1_000_000)*1000),
                    row.get("is_open").intValue(),row.get("pretrade_date").isNull()?null:row.get("pretrade_date").textValue());
            var mapped = mapper.fromStorage(physical);
            assertEquals(physical,mapper.toStorage(mapped));
            assertEquals(2026,mapped.calendarDate().getYear());
            assertEquals(9,mapped.calendarDate().getMonthValue());
        }
        assertEquals("calendar_date",ExchangeCalendarDataset.DEFINITION.columns().get(1).logicalName());
        assertEquals("cal_date",ExchangeCalendarDataset.DEFINITION.columns().get(1).storageName());
    }
}
