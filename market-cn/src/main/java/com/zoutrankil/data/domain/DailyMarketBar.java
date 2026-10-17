package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.stream.Stream;

/** One Tushare `daily` row. The date is a calendar date, not an event instant. */
public record DailyMarketBar(
        String tsCode,
        LocalDate tradeDate,
        Double open,
        Double high,
        Double low,
        Double close,
        Double preClose,
        Double change,
        Double pctChg,
        Double vol,
        Double amount,
        Double ahVol,
        Double ahAmount) {
    public DailyMarketBar {
        if (!StockDetailInfo.validCode(tsCode)) {
            throw new IllegalArgumentException("Explicit Tushare stock code required");
        }
        Objects.requireNonNull(tradeDate, "Trade date required");
        if (Stream.of(open, high, low, close, preClose, change, pctChg, vol, amount, ahVol, ahAmount)
                .filter(Objects::nonNull).anyMatch(value -> !Double.isFinite(value))) {
            throw new IllegalArgumentException("Daily numeric values must be finite or null");
        }
    }

    public Key key() { return new Key(tsCode, tradeDate); }

    public record Key(String tsCode, LocalDate tradeDate) {
        public Key {
            if (!StockDetailInfo.validCode(tsCode)) throw new IllegalArgumentException("Explicit Tushare stock code required");
            Objects.requireNonNull(tradeDate, "Trade date required");
        }
    }
}
