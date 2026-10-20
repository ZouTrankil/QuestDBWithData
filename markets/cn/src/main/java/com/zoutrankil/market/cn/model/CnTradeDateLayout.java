package com.zoutrankil.market.cn.model;

/** 仅用于已核定的六个交易日别名；不处理事件时间、技术占位或待核字段。 */
public enum CnTradeDateLayout {
    LEGACY_V1(1),
    TRADE_DATE_V2(2);

    private final int schemaVersion;

    CnTradeDateLayout(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String physicalDateColumn(String datasetId) {
        String legacy =
                switch (datasetId) {
                    case "etf_adj", "etf_daily", "etf_share", "stk_st_daily", "stk_suspend" ->
                            "timestamp";
                    case "l2_daily_features" -> "ts";
                    default -> throw new IllegalArgumentException("对象尚未核定为可统一的交易日别名：" + datasetId);
                };
        return this == LEGACY_V1 ? legacy : "trade_date";
    }

    public static CnTradeDateLayout fromVersion(int version) {
        return switch (version) {
            case 1 -> LEGACY_V1;
            case 2 -> TRADE_DATE_V2;
            default -> throw new IllegalArgumentException("未支持的交易日存储布局版本：" + version);
        };
    }
}
