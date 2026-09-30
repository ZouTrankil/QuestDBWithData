package com.zoutrankil.data.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/** D002 current company reference row. Observation time is not an upstream change cursor. */
public record StockDetailInfo(String tsCode, Instant observedAt, String symbol, String name,
        String market, String exchange, String listStatus, LocalDate listingDate, String fullname,
        String enname, String cnspell, String area, String industry, String currType,
        LocalDate delistingDate, String isHs, String actName, String actEntType) {
    public StockDetailInfo {
        if(!validCode(tsCode))
            throw new IllegalArgumentException("Explicit mainland stock identity required");
        Objects.requireNonNull(observedAt,"Observation instant required");
        if(observedAt.getNano()%1000!=0) throw new IllegalArgumentException("Observation exceeds storage microseconds");
        if(listStatus!=null && !Set.of("L","D","P").contains(listStatus))
            throw new IllegalArgumentException("Unknown listing status");
        if(listingDate!=null && delistingDate!=null && delistingDate.isBefore(listingDate))
            throw new IllegalArgumentException("Delisting precedes listing");
    }
    public String key() { return tsCode; }
    /** T-prefixed historical Shanghai identity is present in the audited provider-derived table. */
    public static boolean validCode(String code) {
        return code!=null && code.matches("(?:[0-9]{6}\\.(?:SH|SZ|BJ)|T[0-9]{6}\\.SH)");
    }
}
