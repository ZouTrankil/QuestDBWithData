package com.zoutrankil.data.domain.temporal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/** 兼容旧业务时间入口；新应用显式注入所属市场时钟，不使用 JVM 默认时区。 */
public final class BusinessTime {
    private final Clock clock;
    private final ZoneId zone;

    public BusinessTime(Clock clock, ZoneId zone) {
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
    }

    public BusinessTime(MarketTime marketTime) { this(marketTime.clock(),marketTime.zone()); }

    public Instant now() { return clock.instant(); }

    public LocalDate today() { return LocalDate.now(clock.withZone(zone)); }

    /** 业务日实际零点，返回 UTC 时间点。 */
    public Instant startOfDay(LocalDate date) { return date.atStartOfDay(zone).toInstant(); }

    public ZonedDateTime inBusinessZone(Instant instant) { return instant.atZone(zone); }

    /** 旧快照日期标记保持 UTC 零点编码，不能替换成上海零点。 */
    public Instant todaySnapshotMarker() {
        return new TemporalValues.CalendarTimestamp(today()).storageCarrier();
    }
}
