package com.zoutrankil.batch.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.batch.source.SourceCollector;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.batch.support.Json;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared immutable inputs for Batch contract tests. */
public final class NativeSourceFixtures {
    private NativeSourceFixtures() {}

    public static Map<String, JsonNode> row(String dataset, String code) {
        if (dataset.equals("dividend")) {
            var dividend = new LinkedHashMap<String, JsonNode>();
            dividend.put("ts_code", Json.MAPPER.valueToTree(code));
            dividend.put("end_date", Json.MAPPER.nullNode());
            dividend.put("ann_date", Json.MAPPER.valueToTree("20260928"));
            dividend.put("div_proc", Json.MAPPER.valueToTree("预案"));
            dividend.put("stk_div", Json.MAPPER.valueToTree(0.0));
            dividend.put("stk_bo_rate", Json.MAPPER.nullNode());
            dividend.put("stk_co_rate", Json.MAPPER.nullNode());
            dividend.put("cash_div", Json.MAPPER.valueToTree(0.1));
            dividend.put("cash_div_tax", Json.MAPPER.valueToTree(0.1));
            for (String field :
                    List.of(
                            "record_date",
                            "ex_date",
                            "pay_date",
                            "div_listdate",
                            "imp_ann_date",
                            "base_date")) dividend.put(field, Json.MAPPER.nullNode());
            dividend.put("base_share", Json.MAPPER.nullNode());
            return dividend;
        }
        if (dataset.equals("share_float")) {
            return new LinkedHashMap<>(
                    Map.of(
                            "ts_code",
                            Json.MAPPER.valueToTree(code),
                            "ann_date",
                            Json.MAPPER.valueToTree("20260928"),
                            "float_date",
                            Json.MAPPER.valueToTree("20261016"),
                            "float_share",
                            Json.MAPPER.valueToTree(250000.0),
                            "float_ratio",
                            Json.MAPPER.valueToTree(1.25),
                            "holder_name",
                            Json.MAPPER.valueToTree("示例股东"),
                            "share_type",
                            Json.MAPPER.valueToTree("首发原股东限售股份")));
        }
        if (dataset.equals("fina_audit")) {
            return new LinkedHashMap<>(
                    Map.of(
                            "ts_code",
                            Json.MAPPER.valueToTree(code),
                            "ann_date",
                            Json.MAPPER.valueToTree("20260928"),
                            "end_date",
                            Json.MAPPER.valueToTree("20251231"),
                            "audit_result",
                            Json.MAPPER.valueToTree("标准无保留意见"),
                            "audit_fees",
                            Json.MAPPER.valueToTree(1000000.0),
                            "audit_agency",
                            Json.MAPPER.valueToTree("示例会计师事务所"),
                            "audit_sign",
                            Json.MAPPER.valueToTree("测试会计师")));
        }
        if (dataset.equals("fut_mapping")) {
            return new LinkedHashMap<>(
                    Map.of(
                            "ts_code",
                            Json.MAPPER.valueToTree(code),
                            "trade_date",
                            Json.MAPPER.valueToTree("20260928"),
                            "mapping_ts_code",
                            Json.MAPPER.valueToTree("IF2610.CFX")));
        }
        if (dataset.equals("ft_limit")) {
            return new LinkedHashMap<>(
                    Map.of(
                            "ts_code",
                            Json.MAPPER.valueToTree(code),
                            "trade_date",
                            Json.MAPPER.valueToTree("20260928"),
                            "name",
                            Json.MAPPER.valueToTree("IF2610"),
                            "up_limit",
                            Json.MAPPER.valueToTree(4500.0),
                            "down_limit",
                            Json.MAPPER.valueToTree(3500.0),
                            "m_ratio",
                            Json.MAPPER.valueToTree(12.0),
                            "cont",
                            Json.MAPPER.valueToTree("IF"),
                            "exchange",
                            Json.MAPPER.valueToTree("CFFEX")));
        }
        if (dataset.equals("fut_holding")) {
            var holding = new LinkedHashMap<String, JsonNode>();
            holding.put("trade_date", Json.MAPPER.valueToTree("20260928"));
            holding.put("symbol", Json.MAPPER.valueToTree(code));
            holding.put("broker", Json.MAPPER.valueToTree("测试期货"));
            holding.put("vol", Json.MAPPER.valueToTree(100));
            holding.put("vol_chg", Json.MAPPER.valueToTree(-2));
            holding.put("long_hld", Json.MAPPER.valueToTree(80));
            holding.put("long_chg", Json.MAPPER.valueToTree(4));
            holding.put("short_hld", Json.MAPPER.valueToTree(70));
            holding.put("short_chg", Json.MAPPER.valueToTree(-3));
            holding.put("exchange", Json.MAPPER.valueToTree("CFFEX"));
            return holding;
        }
        if (dataset.equals("etf_basic")) {
            var etf = new LinkedHashMap<String, JsonNode>();
            etf.put("ts_code", Json.MAPPER.valueToTree("510300.SH"));
            etf.put("name", Json.MAPPER.valueToTree("沪深300ETF"));
            etf.put("management", Json.MAPPER.valueToTree("示例基金"));
            etf.put("custodian", Json.MAPPER.valueToTree("示例银行"));
            etf.put("fund_type", Json.MAPPER.valueToTree("股票型"));
            for (String field :
                    List.of(
                            "found_date",
                            "due_date",
                            "list_date",
                            "issue_date",
                            "delist_date",
                            "purc_startdate",
                            "redm_startdate")) etf.put(field, Json.MAPPER.valueToTree("20200101"));
            for (String field :
                    List.of(
                            "issue_amount",
                            "m_fee",
                            "c_fee",
                            "duration_year",
                            "p_value",
                            "min_amount",
                            "exp_return")) etf.put(field, Json.MAPPER.valueToTree(1.0));
            etf.put("benchmark", Json.MAPPER.valueToTree("沪深300指数"));
            etf.put("status", Json.MAPPER.valueToTree("L"));
            etf.put("invest_type", Json.MAPPER.valueToTree("被动指数型"));
            etf.put("type", Json.MAPPER.valueToTree("ETF"));
            etf.put("trustee", Json.MAPPER.valueToTree("示例受托人"));
            etf.put("market", Json.MAPPER.valueToTree("E"));
            return etf;
        }
        if (dataset.equals("etf_share")) {
            return new LinkedHashMap<>(
                    Map.of(
                            "ts_code",
                            Json.MAPPER.valueToTree("510300.SH"),
                            "trade_date",
                            Json.MAPPER.valueToTree("20260928"),
                            "fd_share",
                            Json.MAPPER.valueToTree(123456.75)));
        }
        if (dataset.equals("fut_basic")) {
            var basic = new LinkedHashMap<String, JsonNode>();
            basic.put("ts_code", Json.MAPPER.valueToTree("IF2610.CFX"));
            basic.put("symbol", Json.MAPPER.valueToTree("IF2610"));
            basic.put("exchange", Json.MAPPER.valueToTree("CFFEX"));
            basic.put("name", Json.MAPPER.valueToTree("沪深300股指期货2610"));
            basic.put("fut_code", Json.MAPPER.valueToTree("IF"));
            basic.put("multiplier", Json.MAPPER.valueToTree(300.0));
            basic.put("trade_unit", Json.MAPPER.valueToTree("每点300元"));
            basic.put("per_unit", Json.MAPPER.valueToTree(1.0));
            basic.put("quote_unit", Json.MAPPER.valueToTree("指数点"));
            basic.put("list_date", Json.MAPPER.valueToTree("20260717"));
            basic.put("delist_date", Json.MAPPER.valueToTree("20261016"));
            basic.put("d_month", Json.MAPPER.valueToTree("202610"));
            basic.put("last_ddate", Json.MAPPER.valueToTree("20261016"));
            return basic;
        }
        if (dataset.equals("disclosure_date")) {
            var disclosure = new LinkedHashMap<String, JsonNode>();
            disclosure.put("ts_code", Json.MAPPER.valueToTree("000001.SZ"));
            disclosure.put("ann_date", Json.MAPPER.valueToTree("20261015"));
            disclosure.put("end_date", Json.MAPPER.valueToTree("20260930"));
            disclosure.put("pre_date", Json.MAPPER.valueToTree("20261030"));
            disclosure.put("actual_date", Json.MAPPER.nullNode());
            disclosure.put("modify_date", Json.MAPPER.nullNode());
            return disclosure;
        }
        if (dataset.equals("shibor")) {
            var shibor = new LinkedHashMap<String, JsonNode>();
            shibor.put("date", Json.MAPPER.valueToTree("20260928"));
            shibor.put("on", Json.MAPPER.valueToTree(1.25));
            shibor.put("1w", Json.MAPPER.valueToTree(1.5));
            shibor.put("2w", Json.MAPPER.valueToTree(1.6));
            shibor.put("1m", Json.MAPPER.valueToTree(1.7));
            shibor.put("3m", Json.MAPPER.valueToTree(1.8));
            shibor.put("6m", Json.MAPPER.valueToTree(1.9));
            shibor.put("9m", Json.MAPPER.valueToTree(2.0));
            shibor.put("1y", Json.MAPPER.valueToTree(2.1));
            return shibor;
        }
        if (dataset.equals("shibor_lpr"))
            return new LinkedHashMap<>(
                    Map.of(
                            "date",
                            Json.MAPPER.valueToTree("20260928"),
                            "1y",
                            Json.MAPPER.valueToTree(3.0),
                            "5y",
                            Json.MAPPER.valueToTree(3.5)));
        if (dataset.equals("hibor")) {
            var hibor = new LinkedHashMap<String, JsonNode>();
            hibor.put("date", Json.MAPPER.valueToTree("20260928"));
            for (String field : List.of("on", "1w", "2w", "1m", "2m", "3m", "6m", "12m"))
                hibor.put(field, Json.MAPPER.valueToTree(2.75));
            return hibor;
        }
        if (dataset.equals("us_tbr")) {
            var rates = new LinkedHashMap<String, JsonNode>();
            rates.put("date", Json.MAPPER.valueToTree("20260928"));
            for (String field :
                    List.of(
                            "w4_bd", "w4_ce", "w8_bd", "w8_ce", "w13_bd", "w13_ce", "w17_bd",
                            "w17_ce", "w26_bd", "w26_ce", "w52_bd", "w52_ce"))
                rates.put(field, Json.MAPPER.valueToTree(4.25));
            return rates;
        }
        if (dataset.equals("cn_cpi")) {
            var cpi = new LinkedHashMap<String, JsonNode>();
            cpi.put("month", Json.MAPPER.valueToTree("202609"));
            for (String field :
                    List.of(
                            "nt_val",
                            "nt_yoy",
                            "nt_mom",
                            "nt_accu",
                            "town_val",
                            "town_yoy",
                            "town_mom",
                            "town_accu",
                            "cnt_val",
                            "cnt_yoy",
                            "cnt_mom",
                            "cnt_accu")) cpi.put(field, Json.MAPPER.valueToTree(102.25));
            return cpi;
        }
        if (Set.of("cn_ppi", "cn_pmi", "cn_m").contains(dataset)) {
            var macro = new LinkedHashMap<String, JsonNode>();
            macro.put("month", Json.MAPPER.valueToTree("202609"));
            for (var column : SourceContract.load(dataset).businessColumns())
                if (column.type().equals("DOUBLE"))
                    macro.put(column.source(), Json.MAPPER.valueToTree(12.25));
            return macro;
        }
        if (dataset.equals("cn_gdp")) {
            var gdp = new LinkedHashMap<String, JsonNode>();
            gdp.put("quarter", Json.MAPPER.valueToTree("2026Q2"));
            for (String field :
                    List.of("gdp", "gdp_yoy", "pi", "pi_yoy", "si", "si_yoy", "ti", "ti_yoy"))
                gdp.put(field, Json.MAPPER.valueToTree(12.25));
            return gdp;
        }
        if (dataset.equals("fina_mainbz")) {
            var main = new LinkedHashMap<String, JsonNode>();
            main.put("ts_code", Json.MAPPER.valueToTree(code));
            main.put("end_date", Json.MAPPER.valueToTree("20260630"));
            main.put("bz_item", Json.MAPPER.valueToTree("测试主营"));
            main.put("bz_sales", Json.MAPPER.valueToTree(12.25));
            main.put("bz_profit", Json.MAPPER.nullNode());
            main.put("bz_cost", Json.MAPPER.valueToTree(7.5));
            main.put("curr_type", Json.MAPPER.valueToTree("CNY"));
            main.put("update_flag", Json.MAPPER.valueToTree("1"));
            main.put("bz_code", Json.MAPPER.valueToTree("P"));
            return main;
        }
        if (dataset.equals("exchange_calendar"))
            return new LinkedHashMap<>(
                    Map.of(
                            "exchange",
                            Json.MAPPER.valueToTree(code),
                            "cal_date",
                            Json.MAPPER.valueToTree("20260928"),
                            "is_open",
                            Json.MAPPER.valueToTree(1),
                            "pretrade_date",
                            Json.MAPPER.valueToTree("20260925")));
        if (dataset.equals("stk_suspend"))
            return new LinkedHashMap<>(
                    Map.of(
                            "ts_code",
                            Json.MAPPER.valueToTree(code),
                            "trade_date",
                            Json.MAPPER.valueToTree("20260928"),
                            "suspend_type",
                            Json.MAPPER.valueToTree("S"),
                            "suspend_timing",
                            Json.MAPPER.nullNode()));
        if (dataset.equals("stk_st_daily"))
            return new LinkedHashMap<>(
                    Map.of(
                            "ts_code",
                            Json.MAPPER.valueToTree(code),
                            "name",
                            Json.MAPPER.valueToTree("ST示例"),
                            "start_date",
                            Json.MAPPER.valueToTree("20260901"),
                            "end_date",
                            Json.MAPPER.nullNode(),
                            "ann_date",
                            Json.MAPPER.valueToTree("20260901"),
                            "change_reason",
                            Json.MAPPER.valueToTree("风险警示")));
        var contract = SourceContract.load(dataset);
        var row = new LinkedHashMap<String, JsonNode>();
        for (var column : contract.businessColumns())
            row.put(
                    column.source(),
                    Json.MAPPER.valueToTree(
                            switch (column.type()) {
                                case "LONG", "INT" -> 123L;
                                case "STRING" -> "测试证券";
                                case "SYMBOL" -> code;
                                case "TIMESTAMP", "TIMESTAMP_NS" -> "20260928";
                                default -> 12.25;
                            }));
        if (Set.of("daily", "daily_basic", "etf_daily").contains(dataset))
            row.put(
                    switch (dataset) {
                        case "daily" -> "ah_vol";
                        case "daily_basic" -> "pe_ttm";
                        default -> "pre_close";
                    },
                    Json.MAPPER.nullNode());
        return row;
    }

    public static SourceCollector.Request request(String dataset, Set<String> codes) {
        if (dataset.equals("exchange_calendar") && codes.isEmpty()) codes = Set.of("SSE");
        if (Set.of("disclosure_date", "ths_index", "etf_share").contains(dataset)) codes = Set.of();
        if (dataset.equals("fut_holding") && codes.isEmpty()) codes = Set.of("IF");
        if (dataset.equals("fut_basic") && codes.isEmpty()) codes = Set.of("CFFEX");
        if (Set.of("fut_daily", "fut_settle", "ft_limit").contains(dataset) && codes.isEmpty())
            codes = Set.of("IF2610.CFX");
        if (dataset.equals("fut_mapping") && codes.isEmpty()) codes = Set.of("IF.CFX");
        var date =
                dataset.equals("fina_mainbz") || dataset.equals("cn_gdp")
                        ? LocalDate.of(2026, 6, 30)
                        : dataset.equals("disclosure_date")
                                ? LocalDate.of(2026, 9, 30)
                                : SourceContract.MONTHLY_AGGREGATES.contains(dataset)
                                        ? LocalDate.of(2026, 9, 1)
                                        : LocalDate.of(2026, 9, 28);
        return new SourceCollector.Request(
                dataset,
                date,
                codes,
                "frozen-universe-v1",
                codes.size() == 1 ? codes.iterator().next() : null);
    }
}
