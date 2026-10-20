package com.zoutrankil.market.btc.marketdata;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** 有公开证据的交易场所停市窗口；只允许完全落在窗口内的缺失小时，不生成价格。 */
public record CryptoTradingClosure(
        String exchange, String instrument, Instant from, Instant until, String evidenceUrl) {
    public CryptoTradingClosure {
        Objects.requireNonNull(from);
        Objects.requireNonNull(until);
        var uri = URI.create(evidenceUrl);
        if (!Set.of("BINANCE", "BYBIT").contains(exchange)
                || !Set.of("SPOT", "USDT_PERPETUAL").contains(instrument)
                || !until.isAfter(from)
                || !"https".equals(uri.getScheme())
                || uri.getUserInfo() != null
                || !(exchange.equals("BINANCE")
                                ? Set.of("www.binance.com")
                                : Set.of("www.bybit.com"))
                        .contains(uri.getHost()))
            throw new IllegalArgumentException("交易所停市窗口或官方证据地址不符");
    }

    public boolean containsHour(String venue, String product, Instant hour) {
        return exchange.equals(venue)
                && instrument.equals(product)
                && !hour.isBefore(from)
                && !hour.plusSeconds(3600).isAfter(until);
    }
}
