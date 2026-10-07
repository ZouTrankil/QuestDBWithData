package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.ExchangeCalendar;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Freezes a complete SSE calendar interval; the primary Python connector also selects SSE open days. */
public final class MarginSecsTradingDates {
    public record Window(List<LocalDate> openDates, List<String> encodedCalendarDays, String fingerprint) {
        public Window {
            openDates = List.copyOf(openDates);
            encodedCalendarDays = List.copyOf(encodedCalendarDays);
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("D030 complete calendar fingerprint required");
        }
    }
    private final ExchangeCalendarReadRepository calendar;
    public MarginSecsTradingDates(ExchangeCalendarReadRepository calendar) { this.calendar = Objects.requireNonNull(calendar); }

    public Window read(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (from.isAfter(to) || days > MarginSecsSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("D030 SSE calendar window must be 1..31 calendar days");
        var fields = List.of("exchange", "calendar_date", "is_open", "previous_trade_date");
        var query = new DatasetReadQuery(fields, Map.of("exchange", "SSE"), "calendar_date", from, to.plusDays(1), 100, null);
        var rows = new ArrayList<ExchangeCalendar>();
        while (true) {
            var page = calendar.findPage(query);
            rows.addAll(page.rows());
            if (rows.size() > days) throw new IllegalStateException("D030 SSE calendar has duplicate or extra dates");
            if (!page.hasMore()) break;
            if (page.nextCursor() == null) throw new IllegalStateException("D030 SSE calendar cursor is missing");
            query = query.after(page.nextCursor());
        }
        if (rows.size() != days) throw new IllegalStateException("D030 SSE calendar does not cover every frozen natural date");
        var byDate = new java.util.TreeMap<LocalDate, ExchangeCalendar>();
        for (var row : rows) {
            if (!"SSE".equals(row.exchange()) || row.calendarDate().isBefore(from) || row.calendarDate().isAfter(to)
                    || byDate.putIfAbsent(row.calendarDate(), row) != null)
                throw new IllegalStateException("D030 calendar row is outside range or duplicated");
        }
        var encoded = new ArrayList<String>();
        var open = new ArrayList<LocalDate>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            var row = byDate.get(date);
            if (row == null) throw new IllegalStateException("D030 missing SSE calendar date " + date);
            encoded.add(date.toString().replace("-", "") + ":" + (row.open() ? "1" : "0"));
            if (row.open()) open.add(date);
        }
        return new Window(open, encoded, fingerprint(encoded));
    }

    public static List<LocalDate> validateFrozen(LocalDate from, LocalDate to, List<String> days,
            String expectedFingerprint, List<String> expectedOpenDates) {
        long expectedDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (days == null || days.size() != expectedDays || days.isEmpty()
                || !fingerprint(days).equals(expectedFingerprint))
            throw new IllegalArgumentException("D030 frozen SSE calendar proof is incomplete or altered");
        var open = new ArrayList<LocalDate>();
        var seen = new HashSet<LocalDate>();
        LocalDate cursor = from;
        for (String encoded : days) {
            if (encoded == null || !encoded.matches("[0-9]{8}:[01]"))
                throw new IllegalArgumentException("D030 invalid frozen calendar day");
            LocalDate date = LocalDate.parse(encoded.substring(0, 8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            if (!date.equals(cursor) || !seen.add(date)) throw new IllegalArgumentException("D030 calendar days are not an exact ordered cover");
            if (encoded.endsWith(":1")) open.add(date);
            cursor = cursor.plusDays(1);
        }
        var expected = open.stream().map(d -> d.toString().replace("-", "")).toList();
        if (expected.isEmpty() || !expected.equals(expectedOpenDates))
            throw new IllegalArgumentException("D030 frozen open trade dates differ from calendar proof");
        return List.copyOf(open);
    }

    private static String fingerprint(List<String> days) {
        try {
            byte[] bytes = String.join("\n", days).getBytes(StandardCharsets.UTF_8);
            return FileEvidenceStore.sha256(bytes);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
