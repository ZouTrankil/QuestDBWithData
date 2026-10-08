package com.zoutrankil.data.domain;

import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** fund_share daily shares; fundType/market are provider fields and updateTime is frozen technical metadata. */
public record EtfShare(EtfShareKey key, Double fdShare, String fundType, String market, Instant updateTime) {
    public EtfShare {
        Objects.requireNonNull(key, "complete etf_share key required");
        Objects.requireNonNull(market, "provider market required");
        Objects.requireNonNull(updateTime, "frozen observation time required");
        TemporalValues.requirePrecision(updateTime, TemporalValues.Precision.MICROS);
        if (fdShare != null && !Double.isFinite(fdShare))
            throw new IllegalArgumentException("ETF share amount must be finite or null");
        String suffix = key.tsCode().substring(key.tsCode().length() - 2);
        String expectedMarket = suffix.equals("OF") ? "O" : suffix;
        if (!market.equals(expectedMarket))
            throw new IllegalArgumentException("provider market must match the exchange/OTC suffix in ts_code");
        if (fundType != null && fundType.isBlank())
            throw new IllegalArgumentException("fund_type must be null or nonblank");
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
}
