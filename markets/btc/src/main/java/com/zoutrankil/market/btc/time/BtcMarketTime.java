package com.zoutrankil.market.btc.time;

import com.zoutrankil.data.domain.temporal.*;
import java.time.*;

/** 明确声明本模块的市场时钟；交易日是否开市仍由来源日历决定。 */
public final class BtcMarketTime {
    private BtcMarketTime() {}
    public static MarketTime using(Clock clock, ZoneId zone) { return new MarketClock(MarketId.BTC,zone,clock); }
}
