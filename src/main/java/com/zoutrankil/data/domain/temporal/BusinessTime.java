package com.zoutrankil.data.domain.temporal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/** Business calendar operations; independent of the JVM default timezone. */
public final class BusinessTime {
    private final Clock clock;
    private final ZoneId zone;

    public BusinessTime(Clock clock, ZoneId zone) {
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
    }

    public Instant now() { return clock.instant(); }

    public LocalDate today() { return LocalDate.now(clock.withZone(zone)); }

    /** Actual start of a business day, represented as an absolute instant. */
    public Instant startOfDay(LocalDate date) { return date.atStartOfDay(zone).toInstant(); }

    public ZonedDateTime inBusinessZone(Instant instant) { return instant.atZone(zone); }

    /** Date marker for the existing snapshot schema, NOT the actual start of a business day. */
    public Instant todaySnapshotMarker() {
        return new TemporalValues.CalendarTimestamp(today()).storageCarrier();
    }
}
