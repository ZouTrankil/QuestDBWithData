package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.ExchangeCalendar;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Finite exchange/year slices and exact daily coverage; never infers sessions from weekdays. */
public final class ExchangeCalendarSlices {
    private ExchangeCalendarSlices() {}
    public record Slice(String exchange, LocalDate from, LocalDate to) {
        public Slice {
            if (!Set.of("SSE","SZSE").contains(Objects.requireNonNull(exchange)))
                throw new IllegalArgumentException("Unadmitted calendar exchange");
            Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (from.isAfter(to) || from.getYear()!=to.getYear())
                throw new IllegalArgumentException("One ordered calendar-year slice required");
        }
        public int expectedDays() { return Math.toIntExact(ChronoUnit.DAYS.between(from,to)+1); }
    }
    public static List<Slice> bounded(List<String> exchanges, LocalDate from, LocalDate to) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        if (exchanges.isEmpty() || exchanges.size()>2 || new HashSet<>(exchanges).size()!=exchanges.size())
            throw new IllegalArgumentException("Explicit unique admitted exchanges required");
        long days = ChronoUnit.DAYS.between(from,to)+1;
        if (days<1 || days>3660) throw new IllegalArgumentException("Explicit range must contain 1..3660 days");
        var slices = new ArrayList<Slice>();
        for (String exchange : exchanges) {
            LocalDate start = from;
            while (!start.isAfter(to)) {
                LocalDate yearEnd = LocalDate.of(start.getYear(),12,31);
                LocalDate end = yearEnd.isBefore(to)?yearEnd:to;
                slices.add(new Slice(exchange,start,end));
                start = end.plusDays(1);
            }
        }
        return List.copyOf(slices);
    }
    /** Checkpoint must be supplied by verified coverage, never a bare table MAX(date). */
    public static List<Slice> incremental(List<String> exchanges, LocalDate bootstrapFrom, LocalDate end,
                                          Map<String,LocalDate> verifiedThrough, int revisionDays) {
        if (revisionDays<1 || revisionDays>366) throw new IllegalArgumentException("Revision overlap must be 1..366 days");
        if (exchanges.isEmpty() || exchanges.size()>2 || new HashSet<>(exchanges).size()!=exchanges.size())
            throw new IllegalArgumentException("Explicit unique exchanges required");
        Objects.requireNonNull(bootstrapFrom); Objects.requireNonNull(end); Objects.requireNonNull(verifiedThrough);
        if (bootstrapFrom.isAfter(end)) throw new IllegalArgumentException("Ordered bootstrap bounds required");
        var result = new ArrayList<Slice>();
        for (String exchange : exchanges) {
            LocalDate checkpoint = verifiedThrough.get(exchange);
            if (checkpoint!=null && checkpoint.isAfter(end))
                throw new IllegalArgumentException("Checkpoint exceeds frozen request end; explicit bounded reconciliation required");
            LocalDate start = checkpoint==null?bootstrapFrom:checkpoint.minusDays(revisionDays-1L);
            if (start.isBefore(bootstrapFrom)) start=bootstrapFrom;
            result.addAll(bounded(List.of(exchange),start,end));
        }
        return List.copyOf(result);
    }
    /** Validate before handing any row in this slice to the writer. Includes closed days. */
    public static List<ExchangeCalendar> complete(Slice slice, List<ExchangeCalendar> rows) {
        if (rows.size()!=slice.expectedDays()) throw new IllegalArgumentException("Incomplete daily calendar coverage");
        var dates = new TreeMap<LocalDate,ExchangeCalendar>();
        for (var row : rows) {
            if (!row.exchange().equals(slice.exchange()) || row.calendarDate().isBefore(slice.from())
                    || row.calendarDate().isAfter(slice.to())) throw new IllegalArgumentException("Calendar row outside requested slice");
            if (dates.putIfAbsent(row.calendarDate(),row)!=null) throw new IllegalArgumentException("Duplicate calendar business key");
        }
        // Exact range, row count and uniqueness prove every day is represented.
        return List.copyOf(dates.values());
    }
}
