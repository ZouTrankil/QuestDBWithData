package com.zoutrankil.data.calendar.port;

import java.time.LocalDate;
import java.util.function.BiConsumer;

/** Streaming bounded SSE calendar evidence for formal northbound aggregate coverage. */
@FunctionalInterface
public interface SseCalendarWindowReadPort {
    void readSseDates(LocalDate from, LocalDate to, BiConsumer<LocalDate, Integer> consumer);
}
