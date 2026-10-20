package com.zoutrankil.data.domain.temporal;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** 时钟只提供真实时刻，市场时区只负责日期和会话边界。 */
public record MarketClock(MarketId market, ZoneId zone, Clock clock) implements MarketTime {
    public MarketClock {
        Objects.requireNonNull(market);
        Objects.requireNonNull(zone);
        Objects.requireNonNull(clock);
        // 美股必须保留纽约的夏令时规则；A 股也不接受配置成 UTC 后再手动加八小时。
        if (market != MarketId.BTC && !zone.equals(market.defaultZone()))
            throw new IllegalArgumentException(
                    "Market zone must be " + market.defaultZone() + ": " + market);
        clock = clock.withZone(ZoneOffset.UTC);
    }

    public static MarketClock system(MarketId market) {
        return new MarketClock(market, market.defaultZone(), Clock.systemUTC());
    }
}
