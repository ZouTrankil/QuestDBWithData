package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/** Complete ChinaBond curve-point identity; trade_date is a business date. */
public record CnBondYieldCurveKey(LocalDate tradeDate,String curveCode,String tenor)
        implements Comparable<CnBondYieldCurveKey> {
    public CnBondYieldCurveKey {
        Objects.requireNonNull(tradeDate);
        if(!Set.of("gov","aaa_mtn","aaa_bank").contains(curveCode)
                ||!Set.of("3M","6M","1Y","3Y","5Y","7Y","10Y","30Y").contains(tenor))
            throw new IllegalArgumentException("Known ChinaBond curve and tenor required");
    }
    @Override public int compareTo(CnBondYieldCurveKey other) {
        int result=tradeDate.compareTo(other.tradeDate);if(result!=0)return result;
        result=curveCode.compareTo(other.curveCode);return result!=0?result:tenor.compareTo(other.tenor);
    }
}
