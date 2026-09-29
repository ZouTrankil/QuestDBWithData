package com.zoutrankil.questdbwithdata.domain;

import java.time.Instant;
import java.util.Objects;

/** Physical key; timestamp is the fixed epoch carrier, not a business version. */
public record EtfBasicKey(String tsCode, Instant timestamp) {
    public EtfBasicKey {
        if (tsCode == null || !tsCode.matches("[0-9]{1,12}\\.[A-Z]{2,3}"))
            throw new IllegalArgumentException("ETF fund code with exchange suffix required");
        Objects.requireNonNull(timestamp, "technical timestamp required");
        if (!Instant.EPOCH.equals(timestamp))
            throw new IllegalArgumentException("etf_basic timestamp must remain the fixed 1970 technical carrier");
    }
}
