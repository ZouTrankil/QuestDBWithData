package com.zoutrankil.runtime;

import com.zoutrankil.data.domain.temporal.MarketClock;
import com.zoutrankil.data.domain.temporal.MarketId;
import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.domain.temporal.MarketTimes;

import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;

/** 各进程读取同一份市场时间配置，保留旧 A 股配置但拒绝与新值冲突。 */
public final class MarketClocks {
    private MarketClocks() {}

    public static MarketTimes load(Environment environment, Clock clock) {
        return new MarketTimes(
                Arrays.stream(MarketId.values())
                        .map(market -> forMarket(environment, market, clock))
                        .toList());
    }

    public static MarketTime forMarket(Environment environment, MarketId market, Clock clock) {
        String key = "app.time.markets." + market.name().toLowerCase(Locale.ROOT);
        ZoneId zone = ZoneId.of(environment.getProperty(key, market.defaultZone().getId()));
        if (market == MarketId.CN) {
            String legacy = environment.getProperty("app.time.business-zone", zone.getId());
            if (!ZoneId.of(legacy).equals(zone))
                throw new IllegalArgumentException("Conflicting A-share business zones");
        }
        return new MarketClock(market, zone, clock);
    }
}
