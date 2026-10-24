package com.zoutrankil.data.margin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.domain.stock.margin.MarginAll;
import com.zoutrankil.data.domain.stock.margin.MarginAllKey;
import com.zoutrankil.data.repository.FileEvidenceStore;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

class MarginAllCalendarTest {
    static final LocalDate DAY = LocalDate.of(2026, 9, 18);

    @Test
    void freezesThreeMarketsAndTheirDistinctClosedDays() {
        ExchangeCalendarReadPort port =
                q -> {
                    var exchange = (String) q.equalities().get("exchange");
                    var rows =
                            List.of(
                                    new ExchangeCalendar(exchange, DAY, true, DAY.minusDays(1)),
                                    new ExchangeCalendar(exchange, DAY.plusDays(1), false, DAY));
                    return new DatasetReadPage<>(
                            "exchange_calendar", 1, null, Instant.EPOCH, rows, null);
                };
        var window = new MarginAllCalendar(port).read(DAY, DAY.plusDays(1));
        assertEquals(List.of("20260918:7", "20260919:0"), window.encoded());
        assertEquals(MarginAllCalendar.EXCHANGES, window.openExchanges().get(DAY));
        assertEquals(Set.of(), window.openExchanges().get(DAY.plusDays(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MarginAllCalendar.validate(
                                DAY,
                                DAY.plusDays(1),
                                List.of("20260918:7", "20260919:1"),
                                window.fingerprint()));
        assertThrows(
                UnsupportedOperationException.class, () -> window.openExchanges().get(DAY).clear());
    }

    @Test
    void openDateNeedsThreeUniqueRowsAndClosedDateNeedsAnEmptyResponse() {
        var encoded = List.of("20260918:7", "20260919:0");
        String hash =
                FileEvidenceStore.sha256(
                        (String.join("\n", encoded) + "\n").getBytes(StandardCharsets.UTF_8));
        var calendar = MarginAllCalendar.validate(DAY, DAY.plusDays(1), encoded, hash);
        var rows = new ArrayList<MarginAll>();
        for (String exchange : MarginAllCalendar.EXCHANGES)
            rows.add(new MarginAll(new MarginAllKey(DAY, exchange), 1d, 1d, 1d, 1d, 1d, 1d, 1d));
        MarginAllCalendar.requireCoverage(calendar, DAY, rows);
        MarginAllCalendar.requireCoverage(calendar, DAY.plusDays(1), List.of());
        assertThrows(
                IllegalStateException.class,
                () -> MarginAllCalendar.requireCoverage(calendar, DAY, List.of()));
        assertThrows(
                IllegalStateException.class,
                () -> MarginAllCalendar.requireCoverage(calendar, DAY, rows.subList(0, 2)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        MarginAllCalendar.requireCoverage(
                                calendar,
                                DAY,
                                List.of(rows.getFirst(), rows.getFirst(), rows.getLast())));
        assertThrows(
                IllegalStateException.class,
                () -> MarginAllCalendar.requireCoverage(calendar, DAY.plusDays(1), rows));
    }

    @Test
    void missingOneExchangeCannotBeTreatedAsClosed() {
        ExchangeCalendarReadPort port =
                q -> {
                    String exchange = (String) q.equalities().get("exchange");
                    return new DatasetReadPage<>(
                            "exchange_calendar",
                            1,
                            null,
                            Instant.EPOCH,
                            exchange.equals("SZSE")
                                    ? List.of()
                                    : List.of(
                                            new ExchangeCalendar(
                                                    exchange, DAY, true, DAY.minusDays(1))),
                            null);
                };
        assertThrows(IllegalStateException.class, () -> new MarginAllCalendar(port).read(DAY, DAY));
    }

    @Test
    void fullYearFitsFrozenParameterBudgetAndThreeRowsPerDay() {
        var from = LocalDate.of(2024, 1, 1);
        var to = from.plusDays(365);
        var encoded = new ArrayList<String>();
        for (var d = from; !d.isAfter(to); d = d.plusDays(1))
            encoded.add(d.format(DateTimeFormatter.BASIC_ISO_DATE) + ":7");
        String text = String.join(",", encoded);
        String hash =
                FileEvidenceStore.sha256(
                        (String.join("\n", encoded) + "\n").getBytes(StandardCharsets.UTF_8));
        var frozen = MarginAllCalendar.validate(from, to, encoded, hash);
        assertEquals(366, frozen.openExchanges().size());
        assertTrue(text.length() <= 4096);
        var d = MarginAllSyncJobOwner.FORMAL_DEFINITION;
        assertEquals(1098, d.budget().maxRows());
        assertEquals(366, MarginAllSyncJobOwner.DEFINITION.budget().maxRows());
        var p = new LinkedHashMap<String, Object>();
        p.put("targetId", "static-v2-" + "a".repeat(64));
        p.put("physicalTargetId", "static-v2-" + "b".repeat(64));
        p.put("targetRowsBefore", 0);
        p.put("targetFingerprint", "c".repeat(64));
        p.put("calendarPolicy", MarginAllCalendar.POLICY);
        p.put("calendarDays", text);
        p.put("calendarFingerprint", hash);
        var request = d.freeze(SyncJobDefinition.Mode.BACKFILL, p, from, to, to);
        MarginAllSyncAdapter.validateRequest(request);
    }
}
