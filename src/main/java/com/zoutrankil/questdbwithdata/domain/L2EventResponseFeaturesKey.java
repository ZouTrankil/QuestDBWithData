package com.zoutrankil.questdbwithdata.domain;

import java.time.Instant;
import java.util.Objects;

/** Full D088 UPSERT identity: stock symbol, UTC event minute and event category. */
public record L2EventResponseFeaturesKey(String symbol, Instant minute, String eventType) {
    public L2EventResponseFeaturesKey {
        if (symbol == null || !symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
            throw new IllegalArgumentException("Canonical D088 stock symbol required");
        Objects.requireNonNull(minute, "D088 minute instant required");
        if (minute.getNano() != 0 || Math.floorMod(minute.getEpochSecond(), 60) != 0)
            throw new IllegalArgumentException("D088 key must be aligned to a whole minute");
        if (eventType == null || !L2EventResponseFeatureField.EVENT_TYPE_ALLOWED.contains(eventType))
            throw new IllegalArgumentException("Unknown D088 event type");
    }
}
