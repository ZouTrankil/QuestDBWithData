package com.zoutrankil.market.cn.model;

import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.NumericContract;

import java.math.BigDecimal;
import java.util.List;

/** 来源端点逐项登记；同名 amount 的成交额和持仓数量不共用契约。 */
public final class CnNumericContracts {
    private CnNumericContracts() {}

    public static List<Column> attach(String endpoint, List<Column> columns) {
        return columns.stream()
                .map(
                        column -> {
                            var numeric = contract(endpoint, column.sourceName());
                            return numeric == null ? column : column.withNumeric(numeric);
                        })
                .toList();
    }

    private static NumericContract contract(String endpoint, String field) {
        return switch (endpoint) {
            case "daily", "fund_daily" -> daily(endpoint, field);
            case "daily_basic" -> basic(field);
            case "fund_portfolio" -> portfolio(field);
            default -> throw new IllegalArgumentException("未登记的数值单位来源：" + endpoint);
        };
    }

    private static NumericContract daily(String endpoint, String field) {
        String evidence =
                "https://tushare.pro/document/2?doc_id="
                        + (endpoint.equals("daily") ? "27" : "127");
        return switch (field) {
            case "open", "high", "low", "close" ->
                    value("元", "元", "1", "CNY", "来源证券的一股或一份", "未复权成交价格", evidence);
            case "pre_close" ->
                    value(
                            "元",
                            "元",
                            "1",
                            "CNY",
                            "来源证券的一股或一份",
                            endpoint.equals("daily")
                                    ? "来源除权昨收价，不能当成上一行原始 close"
                                    : "来源昨收盘价，不自行认定除权或复权口径",
                            evidence);
            case "change" ->
                    value("元", "元", "1", "CNY", "来源证券的一股或一份", "来源涨跌额原值，不按读取到的相邻行重算", evidence);
            case "pct_chg" ->
                    value(
                            "百分数",
                            "小数比例",
                            "0.01",
                            "NONE",
                            endpoint.equals("daily") ? "来源除权昨收价" : "来源涨跌幅的昨收基准，具体除权规则待核",
                            "来源涨跌幅，不替换成 close 的相邻行收益率",
                            evidence);
            case "vol", "ah_vol" -> value("手", "手", "1", "NONE", "不适用", "不适用；不在此假设每手股数", evidence);
            case "amount", "ah_amount" ->
                    value("千元", "元", "1000", "CNY", "不适用", "不适用；成交金额不是持股数量", evidence);
            default -> null;
        };
    }

    private static NumericContract basic(String field) {
        String evidence = "https://tushare.pro/document/2?doc_id=32";
        return switch (field) {
            case "total_mv", "circ_mv" ->
                    value("万元", "元", "10000", "CNY", "不适用", "来源当日市值；总市值与流通市值各保留原含义", evidence);
            case "total_share", "float_share", "free_share" ->
                    value("万股", "股", "10000", "NONE", "不适用", "总股本、流通股本或自由流通股本，按来源列区分", evidence);
            case "turnover_rate" ->
                    value(
                            "百分数",
                            "小数比例",
                            "0.01",
                            "NONE",
                            "无限售流通股数",
                            "不适用；分子为成交量，按来源一致的数量单位计算",
                            evidence);
            case "turnover_rate_f" ->
                    value(
                            "百分数",
                            "小数比例",
                            "0.01",
                            "NONE",
                            "自由流通股数",
                            "不适用；不与 turnover_rate 合并",
                            evidence);
            case "dv_ratio", "dv_ttm" ->
                    value(
                            "百分数",
                            "小数比例",
                            "0.01",
                            "NONE",
                            "来源股息率价格基准；具体公式仍以来源为准",
                            "保留来源去年或 TTM 派现口径",
                            evidence);
            case "volume_ratio", "pe", "pe_ttm", "pb", "ps", "ps_ttm" ->
                    value(
                            "倍数",
                            "倍数",
                            "1",
                            "NONE",
                            switch (field) {
                                case "volume_ratio" -> "来源 MA 成交量，窗口以来源为准";
                                case "pe" -> "来源净利润";
                                case "pe_ttm" -> "来源 TTM 净利润";
                                case "pb" -> "净资产扣除其他权益工具";
                                case "ps" -> "最新年报营业收入";
                                default -> "TTM 营业收入";
                            },
                            "保留来源公式；不是百分数",
                            evidence);
            case "close" -> value("元", "元", "1", "CNY", "一股", "来源当日收盘价；不附加复权", evidence);
            default -> null;
        };
    }

    private static NumericContract portfolio(String field) {
        String evidence = "https://tushare.pro/document/2?doc_id=121";
        return switch (field) {
            case "amount" -> value("股", "股", "1", "NONE", "不适用", "持有股票数量，不是成交金额", evidence);
            case "mkv" -> value("元", "元", "1", "CNY", "不适用", "持有股票市值，不是基金总市值", evidence);
            case "stk_mkv_ratio", "stk_float_ratio" ->
                    new NumericContract(
                            "UNVERIFIED",
                            "小数比例",
                            null,
                            "NONE",
                            field.equals("stk_mkv_ratio")
                                    ? "来源称占股票市值比，精确分母待核"
                                    : "来源称占流通股本比例，精确分母待核",
                            "来源说明未明确百分数或小数尺度，保留原值",
                            "null 保持缺失；零保持零；不填值或缩放",
                            evidence);
            default -> null;
        };
    }

    private static NumericContract value(
            String unit,
            String baseUnit,
            String multiplier,
            String currency,
            String denominator,
            String priceBasis,
            String evidence) {
        return new NumericContract(
                unit,
                baseUnit,
                new BigDecimal(multiplier),
                currency,
                denominator,
                priceBasis,
                "null 保持缺失；零保持零；不填值或自动换算",
                evidence);
    }
}
