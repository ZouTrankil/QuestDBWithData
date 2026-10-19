package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Python factor_sync's default calendar lookback; row limits remain enforced by each reader. */
public final class EtfHistoryWindow {
    public static final int DEFAULT_DAYS=550;
    public static final int MAX_DAYS=550;
    private EtfHistoryWindow() {}
    public static LocalDate defaultStart(LocalDate date) { return Objects.requireNonNull(date).minusDays(DEFAULT_DAYS); }
    public static void requireBounded(LocalDate from,LocalDate through) {
        Objects.requireNonNull(from); Objects.requireNonNull(through);
        if(from.isAfter(through) || ChronoUnit.DAYS.between(from,through)>MAX_DAYS)
            throw new IllegalArgumentException("ETF history must precede output by at most "+MAX_DAYS+" calendar days");
    }
}
