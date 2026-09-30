package com.zoutrankil.data.service;

import java.time.*;
import java.util.Objects;

/** Python daily_sync source-local ceiling: before 20:30, never request today's still-forming close. */
public final class DailySyncEndDate {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalTime COMPLETION_CEILING = LocalTime.of(20, 30);
    private DailySyncEndDate() {}

    public static LocalDate resolve(LocalDate requested, ZonedDateTime now) {
        Objects.requireNonNull(now);
        var local = now.withZoneSameInstant(ZONE);
        LocalDate ceiling = local.toLocalTime().isBefore(COMPLETION_CEILING)
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
        return requested == null || requested.isAfter(ceiling) ? ceiling : requested;
    }
}
