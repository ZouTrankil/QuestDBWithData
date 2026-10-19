package com.zoutrankil.data.domain.temporal;

import java.util.*;

/** 同一进程可以同时读取多个市场，不能切换一个全局时区来复用业务时钟。 */
public final class MarketTimes {
    private final Map<MarketId,MarketTime> clocks;
    public MarketTimes(Collection<? extends MarketTime> values) {
        var result=new EnumMap<MarketId,MarketTime>(MarketId.class);
        for (var value:values) if (result.putIfAbsent(value.market(),value)!=null)
            throw new IllegalArgumentException("Duplicate market clock: "+value.market());
        if (result.size()!=MarketId.values().length) throw new IllegalArgumentException("All market clocks required");
        clocks=Map.copyOf(result);
    }
    public MarketTime require(MarketId market) { return Objects.requireNonNull(clocks.get(market)); }
}
