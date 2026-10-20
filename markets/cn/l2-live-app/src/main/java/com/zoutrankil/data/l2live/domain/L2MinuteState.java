package com.zoutrankil.data.l2live.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 可用时间在处理时只记录一次；结果是部分分钟观察，不是已完结的分钟行情。 */
public record L2MinuteState(
        String symbol,
        LocalDate tradeDate,
        Instant minuteStart,
        Instant availableAt,
        long revision,
        Map<String, Object> metrics) {
    public L2MinuteState {
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(tradeDate);
        Objects.requireNonNull(minuteStart);
        Objects.requireNonNull(availableAt);
        if (!symbol.matches("[0-9]{6}\\.(SH|SZ)")
                || revision < 1
                || !minuteStart.equals(minuteStart.truncatedTo(ChronoUnit.MINUTES))
                || !tradeDate.equals(minuteStart.atZone(RawCapture.SHANGHAI).toLocalDate()))
            throw new IllegalArgumentException("Canonical event-time minute identity required");
        metrics = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(metrics)));
    }
}
