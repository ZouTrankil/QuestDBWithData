package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.calendar.mapper.ExchangeCalendarMapper;
import com.zoutrankil.data.client.dto.TushareTradeCalendarDto;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendarDataset;
import com.zoutrankil.data.domain.stock.basic.table.ExchangeCalendarRow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

class ExchangeCalendarMappingTest {
    private final ExchangeCalendarMapper mapper = new ExchangeCalendarMapper();

    @Test
    void allFourFieldsRoundTripWithoutConvertingDateToMarketTime() {
        var day = mapper.fromSource(new TushareTradeCalendarDto("SSE", "20260929", 1, "20260928"));
        assertEquals(LocalDate.of(2026, 9, 29), day.calendarDate());
        assertTrue(day.open());
        assertEquals(Instant.parse("2026-09-29T00:00:00Z"), mapper.toStorage(day).calDate());
        assertEquals(day, mapper.fromStorage(mapper.toStorage(day)));
        assertEquals(day, mapper.fromValues(mapper.values(day)));
        var closed = mapper.fromSource(new TushareTradeCalendarDto("SZSE", "20260927", 0, null));
        assertFalse(closed.open());
        assertNull(closed.previousTradeDate());
        assertEquals(closed, mapper.fromValues(mapper.values(closed)));
        assertNotEquals(
                day.key(),
                new ExchangeCalendar("SZSE", day.calendarDate(), true, day.previousTradeDate())
                        .key());
    }

    @Test
    void ambiguousDatesFlagsAndIntradayCarriersAreRejected() {
        for (String invalid :
                new String[] {"20260230", "2026-09-29", "20260929T00:00:00", " 20260929"})
            assertThrows(
                    Exception.class,
                    () -> mapper.fromSource(new TushareTradeCalendarDto("SSE", invalid, 1, null)));
        for (Integer flag : new Integer[] {null, -1, 2})
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            mapper.fromSource(
                                    new TushareTradeCalendarDto("SSE", "20260929", flag, null)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mapper.fromSource(
                                new TushareTradeCalendarDto("SSE", "20260929", 1, "20260929")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mapper.fromStorage(
                                new ExchangeCalendarRow(
                                        "SSE",
                                        Instant.parse("2026-09-29T00:00:00.000001Z"),
                                        1,
                                        null)));
    }

    @Tag("retained-parity")
    @Test
    void capturedActualQuestDbSeptemberRowsRespectDeclaredBusinessDateContract() throws Exception {
        var json =
                new ObjectMapper()
                        .readTree(
                                Path.of("artifacts/java-migration/D001/physical-baseline.json")
                                        .toFile());
        var rows = json.at("/sample/rows");
        assertEquals(30, rows.size());
        for (var row : rows) {
            long micros = row.get("date_micros").longValue();
            var physical =
                    new ExchangeCalendarRow(
                            row.get("exchange").textValue(),
                            Instant.ofEpochSecond(
                                    Math.floorDiv(micros, 1_000_000),
                                    Math.floorMod(micros, 1_000_000) * 1000),
                            row.get("is_open").intValue(),
                            row.get("pretrade_date").isNull()
                                    ? null
                                    : row.get("pretrade_date").textValue());
            var mapped = mapper.fromStorage(physical);
            assertEquals(physical, mapper.toStorage(mapped));
            assertEquals(2026, mapped.calendarDate().getYear());
            assertEquals(9, mapped.calendarDate().getMonthValue());
        }
        assertEquals(
                "calendar_date", ExchangeCalendarDataset.DEFINITION.columns().get(1).logicalName());
        assertEquals("cal_date", ExchangeCalendarDataset.DEFINITION.columns().get(1).storageName());
    }
}
