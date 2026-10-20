package com.zoutrankil.market.cn.time;

import com.zoutrankil.data.domain.temporal.MarketClock;
import com.zoutrankil.data.domain.temporal.MarketId;
import com.zoutrankil.data.domain.temporal.MarketTime;

import java.time.Clock;
import java.time.ZoneId;

/** 明确声明本模块的市场时钟；交易日是否开市仍由来源日历决定。 */
public final class CnMarketTime {
    private CnMarketTime() {}

    public static MarketTime system() {
        return MarketClock.system(MarketId.CN);
    }

    public static MarketTime using(Clock clock, ZoneId zone) {
        return new MarketClock(MarketId.CN, zone, clock);
    }
}
