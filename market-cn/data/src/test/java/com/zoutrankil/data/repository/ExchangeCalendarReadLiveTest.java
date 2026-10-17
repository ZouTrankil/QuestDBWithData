package com.zoutrankil.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.calendar.mapper.ExchangeCalendarMapper;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.service.ReadGroupReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Read-only PGWire check of D001's actual four-column business mapping and keyset pages. */
@EnabledIfEnvironmentVariable(named = "QUESTDB_BOUNDED_READ", matches = "1")
class ExchangeCalendarReadLiveTest {
    @Test void registeredReadGroupReturnsTypedCalendarRows() {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> context.addBeanFactoryPostProcessor(factory ->
                ((BeanDefinitionRegistry) factory).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var group = context.getBean(ReadGroupReader.class);
            var query = new DatasetReadQuery(List.of("exchange", "calendar_date", "is_open", "previous_trade_date"),
                    Map.of("exchange", "SSE"), "calendar_date", LocalDate.of(2026, 9, 25),
                    LocalDate.of(2026, 9, 29), 2, null);
            var result = group.read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                    "calendar", "exchange_calendar", 1, query)), Duration.ofSeconds(20)), () -> false);
            assertTrue(result.complete());
            var typed = result.require("calendar").typedPage(DatasetValues.class);
            assertEquals(2, typed.rows().size());
            assertEquals(0, typed.rows().getFirst().get("is_open", Integer.class));
            assertNotNull(typed.nextCursor());
            var projection = new DatasetReadQuery(List.of("exchange", "calendar_date"),
                    query.equalities(), query.rangeColumn(), query.fromInclusive(), query.toExclusive(), 2, null);
            var projected = group.read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                    "dates", "exchange_calendar", 1, projection)), Duration.ofSeconds(20)), () -> false);
            assertTrue(projected.complete());
            var dates = projected.require("dates").typedPage(DatasetValues.class);
            assertEquals(LocalDate.of(2026, 9, 25), dates.rows().getFirst().get("calendar_date", LocalDate.class));
            assertEquals(Set.of("exchange", "calendar_date"), dates.rows().getFirst().asMap().keySet());
        }
    }

    @Test void typedPagesEqualIndependentPhysicalRowsForBothExchanges() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> context.addBeanFactoryPostProcessor(factory ->
                ((BeanDefinitionRegistry) factory).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
        var jdbc = new JdbcTemplate(context.getBean(JdbcTemplate.class).getDataSource());
        jdbc.setQueryTimeout(20);
        var reader = new QuestDbBoundedReader(jdbc);
        var mapper = new ExchangeCalendarMapper();
        var evidence = new ArrayList<Map<String, Object>>();
        for (String exchange : List.of("SSE", "SZSE")) {
            var query = new DatasetReadQuery(List.of("exchange", "calendar_date", "is_open", "previous_trade_date"),
                    Map.of("exchange", exchange), "calendar_date", LocalDate.of(2026, 9, 25),
                    LocalDate.of(2026, 9, 29), 2, null);
            var paged = new ArrayList<ExchangeCalendar>();
            int pages = 0;
            do {
                var page = reader.read(ExchangeCalendarDataset.DEFINITION, query, null, mapper::fromValues);
                paged.addAll(page.rows());
                pages++;
                if (page.nextCursor() == null) break;
                query = query.after(page.nextCursor());
            } while (pages < 4);
            assertEquals(2, pages);
            var physical = jdbc.query("SELECT exchange, cast(cal_date as long) AS micros, is_open, pretrade_date "
                    + "FROM exchange_calendar WHERE exchange=? AND cal_date >= '2026-09-25' "
                    + "AND cal_date < '2026-09-29' ORDER BY exchange, cal_date LIMIT 5", (rs, n) -> {
                var date = TemporalValues.CalendarTimestamp.fromStorageEpoch(rs.getLong("micros"),
                        TemporalValues.EpochUnit.MICROS).date();
                return new ExchangeCalendar(rs.getString("exchange"), date, rs.getInt("is_open") == 1,
                        rs.getString("pretrade_date") == null ? null : TemporalValues.businessDate(
                                rs.getString("pretrade_date"), TemporalValues.DateFormat.BASIC));
            }, exchange);
            assertEquals(4, physical.size());
            assertEquals(physical, paged);
            assertFalse(paged.getFirst().open(), "Observed Friday closure must come from the calendar table");
            assertTrue(paged.getLast().open());
            evidence.add(Map.of("exchange", exchange, "pages", pages, "matchedRows", paged.size(),
                    "rows", paged.stream().map(ExchangeCalendar::toString).toList()));
        }
        Path out = Path.of("artifacts/java-migration/D001/read-live.json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), Map.of(
                "queryRange", "[2026-09-25,2026-09-29)", "target", "exchange_calendar",
                "readOnly", true, "checks", evidence));
        }
    }
}
