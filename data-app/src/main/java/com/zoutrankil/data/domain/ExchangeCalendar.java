package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/** One exchange's calendar day, including closed days; no event-time semantics. */
public record ExchangeCalendar(String exchange, LocalDate calendarDate, boolean open,
                               LocalDate previousTradeDate) {
    public ExchangeCalendar {
        if (!Set.of("SSE", "SZSE").contains(Objects.requireNonNull(exchange)))
            throw new IllegalArgumentException("Calendar owner currently admits SSE and SZSE only");
        Objects.requireNonNull(calendarDate);
        if (previousTradeDate != null && !previousTradeDate.isBefore(calendarDate))
            throw new IllegalArgumentException("Previous trade date must precede calendar date");
    }
    public record Key(String exchange, LocalDate calendarDate) {
        public Key { Objects.requireNonNull(exchange); Objects.requireNonNull(calendarDate); }
    }
    public Key key() { return new Key(exchange, calendarDate); }
}
