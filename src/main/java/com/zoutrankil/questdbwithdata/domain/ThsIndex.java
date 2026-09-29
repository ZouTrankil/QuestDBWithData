package com.zoutrankil.questdbwithdata.domain;

import java.time.*;
import java.util.*;

/** Current directory entry, not a historical market-price observation. */
public record ThsIndex(String tsCode,String name,Integer memberCount,String exchange,
                       LocalDate listingDate,String indexType,Instant observedAt) {
    public static final Set<String> EXCHANGES=Set.of("A","HK","US");
    public static final Set<String> TYPES=Set.of("N","I","R","S","ST","TH","BB");
    public ThsIndex {
        if(!validCode(tsCode)) throw new IllegalArgumentException("THS code required, including provider suffix letters");
        if(memberCount!=null && memberCount<0) throw new IllegalArgumentException("Nonnegative THS member count required");
        if(exchange!=null && !EXCHANGES.contains(exchange)) throw new IllegalArgumentException("Unknown THS market");
        if(indexType!=null && !TYPES.contains(indexType)) throw new IllegalArgumentException("Unknown THS index type");
        Objects.requireNonNull(observedAt,"Observation instant required");
        if(observedAt.getNano()%1000!=0) throw new IllegalArgumentException("Microsecond THS observation required");
    }
    public static boolean validCode(String code) { return code!=null && code.matches("[A-Z0-9]{6,12}\\.TI"); }
}
