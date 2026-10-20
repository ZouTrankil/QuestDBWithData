package com.zoutrankil.data.domain.temporal;

import java.time.ZoneId;

/** 市场身份与默认业务时区；事件时间始终用 UTC，日期不做时差平移。 */
public enum MarketId {
    CN("Asia/Shanghai"),
    US("America/New_York"),
    BTC("UTC");
    private final ZoneId defaultZone;

    MarketId(String zone) {
        defaultZone = ZoneId.of(zone);
    }

    public ZoneId defaultZone() {
        return defaultZone;
    }
}
