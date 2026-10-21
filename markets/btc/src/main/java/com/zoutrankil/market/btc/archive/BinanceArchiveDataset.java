package com.zoutrankil.market.btc.archive;

import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;

/** 原生日线与旧聚合日线分开；六种来源各有稳定的任务标识。 */
public enum BinanceArchiveDataset {
    SPOT_1D("spot_1d", "spot", "klines", "1d"),
    SPOT_1H("spot_1h", "spot", "klines", "1h"),
    PERP_1H("perp_1h", "futures/um", "klines", "1h"),
    FUNDING("funding", "futures/um", "fundingRate", ""),
    MARK_1H("mark_1h", "futures/um", "markPriceKlines", "1h"),
    INDEX_1H("index_1h", "futures/um", "indexPriceKlines", "1h");
    private final String id, market, kind, interval;

    BinanceArchiveDataset(String id, String market, String kind, String interval) {
        this.id = id;
        this.market = market;
        this.kind = kind;
        this.interval = interval;
    }

    public String id() {
        return id;
    }

    public String interval() {
        return interval;
    }

    public boolean spot() {
        return market.equals("spot");
    }

    public boolean traded() {
        return kind.equals("klines");
    }

    public URI monthly(YearMonth month) {
        return uri("monthly", month.toString());
    }

    public URI daily(LocalDate day) {
        if (this == FUNDING) throw new IllegalArgumentException("资金费率日包能力尚未核实");
        return uri("daily", day.toString());
    }

    private URI uri(String frequency, String period) {
        String suffix = interval.isEmpty() ? "" : interval + "/";
        String filename =
                "BTCUSDT-" + (interval.isEmpty() ? kind : interval) + "-" + period + ".zip";
        return URI.create(
                "https://data.binance.vision/data/"
                        + market
                        + "/"
                        + frequency
                        + "/"
                        + kind
                        + "/BTCUSDT/"
                        + suffix
                        + filename);
    }

    public static BinanceArchiveDataset parse(String id) {
        for (var value : values()) if (value.id.equals(id)) return value;
        throw new IllegalArgumentException("未知 BTC 数据集：" + id);
    }
}
