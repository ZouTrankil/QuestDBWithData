package com.zoutrankil.data.domain.temporal;

import java.time.*;

/** 按市场计算业务日期；接口不判断周末是否开市，开市仍查版本化日历。 */
public interface MarketTime {
    MarketId market();
    default MarketTime requireMarket(MarketId expected) {
        if (market()!=expected) throw new IllegalArgumentException("Wrong market clock: "+market()+" expected "+expected);
        return this;
    }
    ZoneId zone();
    Clock clock();
    default Instant now() { return clock().instant(); }
    default LocalDate today() { return businessDate(now()); }
    default LocalDate businessDate(Instant instant) { return instant.atZone(zone()).toLocalDate(); }
    default Instant startOfDay(LocalDate day) { return day.atStartOfDay(zone()).toInstant(); }
    default Instant endOfDay(LocalDate day) { return startOfDay(day.plusDays(1)); }
    default ZonedDateTime inBusinessZone(Instant instant) { return instant.atZone(zone()); }

    /** 夏令时缺失或重复的本地时刻不能猜；重复时由来源明确给偏移量。 */
    default Instant toInstant(LocalDateTime local) {
        var offsets=zone().getRules().getValidOffsets(local);
        if (offsets.size()!=1) throw new IllegalArgumentException("Ambiguous or nonexistent local time: "+local+" "+zone());
        return local.toInstant(offsets.getFirst());
    }
    default Instant toInstant(LocalDateTime local, ZoneOffset offset) {
        if (!zone().getRules().getValidOffsets(local).contains(offset))
            throw new IllegalArgumentException("Offset does not match market time: "+local+" "+offset);
        return local.toInstant(offset);
    }
}
