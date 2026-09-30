package com.zoutrankil.batch;

import java.util.*;

/** Rules frozen from back-monitor data_quality_rules.yaml; nulls are preserved, never filled with zero. */
public final class SourceQuality {
    private SourceQuality() {}
    public static List<String> failures(String dataset,List<Map<String,Object>> rows) {
        if(rows.isEmpty()) return SourceContract.load(dataset).emptyAllowed()?List.of():List.of("empty-required-source");
        var failures=new LinkedHashSet<String>();
        List<String> required=switch(dataset) {
            case "fina_mainbz" -> List.of("ts_code","end_date","bz_item","bz_code");
            case "fina_audit" -> List.of("ts_code","ann_date","end_date");
            case "dividend" -> List.of("ts_code","end_date","ann_date");
            case "share_float" -> List.of("ts_code","ann_date","float_date");
            case "shibor" -> List.of("timestamp");
            case "shibor_lpr" -> List.of("date");
            case "hibor" -> List.of("timestamp");
            case "us_tbr" -> List.of("date");
            case "cn_cpi" -> List.of("month","nt_yoy");
            case "cn_ppi" -> List.of("month","ppi_yoy");
            case "cn_pmi" -> List.of("month","pmi010000");
            case "cn_m" -> List.of("month","m2","m2_yoy");
            case "cn_gdp" -> List.of("quarter","report_date","gdp_yoy");
            case "fut_daily", "fut_settle" -> List.of("ts_code","trade_date");
            case "fut_mapping" -> List.of("ts_code","trade_date","mapping_ts_code");
            case "fut_holding" -> List.of("trade_date","symbol","broker");
            case "fut_basic" -> List.of("ts_code","symbol","exchange","fut_code");
            case "etf_basic" -> List.of("ts_code","name","market");
            case "disclosure_date" -> List.of("ts_code","ann_date","end_date");
            case "ths_index" -> List.of("ts_code");
            case "etf_share" -> List.of("ts_code","timestamp","fd_share");
            case "ft_limit" -> List.of("trade_date","ts_code");
            case "exchange_calendar" -> List.of("exchange","cal_date","is_open");
            case "moneyflow_hsgt" -> List.of("trade_date");
            case "cn_bond_yield_curve" -> List.of("trade_date","curve_name","curve_code","tenor","yield_value","source");
            case "cyq_perf", "index_daily_market" -> List.of("ts_code","trade_date");
            case "index_daily_basic" -> List.of("ts_code","trade_date");
            case "etf_portfolio" -> List.of("ts_code","ann_date","end_date","symbol");
            case "stk_suspend" -> List.of("ts_code","timestamp","is_suspended");
            case "stk_st_daily" -> List.of("ts_code","timestamp","is_st");
            case "stk_factor" -> List.of("ts_code","trade_date","open","high","low","close","vol");
            case "daily" -> List.of("ts_code","trade_date","open","high","low","close","pre_close","vol","amount");
            case "daily_basic" -> List.of("ts_code","trade_date","turnover_rate","pe","pb");
            case "moneyflow", "etf_factor", "margin_detail" -> List.of("ts_code","trade_date");
            case "stk_limit" -> List.of("ts_code","trade_date");
            case "etf_adj" -> List.of("ts_code","timestamp");
            case "etf_daily" -> List.of("ts_code","timestamp");
            default -> throw new IllegalArgumentException("Unregistered quality owner");
        };
        double nullThreshold=dataset.equals("daily_basic")?0.05:0.01;
        for(String field:required) {
            long nulls=rows.stream().filter(row -> row.get(field)==null).count();
            if((double)nulls/rows.size()>nullThreshold) failures.add("completeness:"+field);
        }
        for(var row:rows) {
            if(dataset.equals("exchange_calendar") && !Double.valueOf(0).equals(number(row,"is_open"))
                    && !Double.valueOf(1).equals(number(row,"is_open"))) failures.add("domain:is_open");
            if(dataset.equals("fina_mainbz") && !Set.of("P","D").contains(row.get("bz_code"))) failures.add("domain:bz_code");
            if(dataset.equals("shibor") && List.of("on","1w","2w","1m","3m","6m","9m","1y").stream().noneMatch(field -> row.get(field)!=null))
                failures.add("completeness:shibor-rates");
            if(dataset.equals("shibor_lpr") && List.of("1y","5y").stream().noneMatch(field -> row.get(field)!=null))
                failures.add("completeness:shibor_lpr-rates");
            if(dataset.equals("hibor") && List.of("on","1w","2w","1m","2m","3m","6m","12m").stream().noneMatch(field -> row.get(field)!=null))
                failures.add("completeness:hibor-rates");
            if(dataset.equals("us_tbr") && List.of("w4_bd","w4_ce","w8_bd","w8_ce","w13_bd","w13_ce","w17_bd","w17_ce","w26_bd","w26_ce","w52_bd","w52_ce").stream().noneMatch(field -> row.get(field)!=null))
                failures.add("completeness:us_tbr-rates");
            if(dataset.equals("stk_st_daily") && !Long.valueOf(1).equals(row.get("is_st"))) failures.add("domain:is_st");
            if(dataset.equals("daily") || dataset.equals("etf_daily") || dataset.equals("stk_factor")) {
                if(number(row,"close")!=null && number(row,"close")<=0) failures.add("domain:close");
                if(!dataset.equals("stk_factor") && number(row,"amount")!=null && number(row,"amount")<0) failures.add("domain:amount");
            }
            if(dataset.equals("daily") || dataset.equals("stk_factor")) {
                if(number(row,"vol")!=null && number(row,"vol")<0) failures.add("domain:vol");
                for(String field:List.of("open","close","low"))
                    if(number(row,"high")!=null && number(row,field)!=null && number(row,"high")<number(row,field))
                        failures.add("domain:high");
            }
            if(dataset.equals("fut_daily")) {
                if(number(row,"vol")!=null && number(row,"vol")<0) failures.add("domain:vol");
                if(number(row,"amount")!=null && number(row,"amount")<0) failures.add("domain:amount");
                for(String field:List.of("open","close","low"))
                    if(number(row,"high")!=null && number(row,field)!=null && number(row,"high")<number(row,field)) failures.add("domain:high");
            }
            if(dataset.equals("ft_limit")) {
                if(number(row,"up_limit")!=null && number(row,"up_limit")<0) failures.add("domain:up_limit");
                if(number(row,"down_limit")!=null && number(row,"down_limit")<0) failures.add("domain:down_limit");
                if(number(row,"up_limit")!=null && number(row,"down_limit")!=null && number(row,"up_limit")<number(row,"down_limit")) failures.add("domain:limit-order");
                if(number(row,"m_ratio")!=null && number(row,"m_ratio")<0) failures.add("domain:m_ratio");
            }
            if(dataset.equals("fut_holding")) for(String field:List.of("vol","vol_chg","long_hld","long_chg","short_hld","short_chg"))
                if(number(row,field)!=null && number(row,field)<0 && !field.endsWith("_chg")) failures.add("domain:"+field);
            if(dataset.equals("fut_basic")) {
                for(String field:List.of("multiplier","per_unit"))
                    if(number(row,field)!=null && number(row,field)<=0) failures.add("domain:"+field);
                if("CFFEX".equals(row.get("exchange"))) {
                    String product=Objects.toString(row.get("fut_code"),"");Double expected=Map.of("IF",300d,"IH",300d,"IC",200d,"IM",200d).get(product);
                    if(expected!=null && number(row,"multiplier")!=null && !expected.equals(number(row,"multiplier")))
                        failures.add("domain:equity-index-multiplier");
                }
                for(String field:List.of("list_date","delist_date","last_ddate")) {
                    Object value=row.get(field);
                    if(value!=null&&!value.toString().isBlank()&&!value.toString().matches("[0-9]{8}")) failures.add("format:"+field);
                }
            }
            if(dataset.equals("etf_basic")) {
                if(!Objects.equals(row.get("market"),"E")) failures.add("scope:market");
                if(row.get("status")!=null&&!Set.of("D","I","L").contains(row.get("status"))) failures.add("domain:status");
                for(String field:List.of("found_date","due_date","list_date","issue_date","delist_date","purc_startdate","redm_startdate")) {
                    Object value=row.get(field);
                    if(value!=null&&!value.toString().isBlank()&&!value.toString().matches("[0-9]{8}")) failures.add("format:"+field);
                }
                for(String field:List.of("issue_amount","m_fee","c_fee","min_amount"))
                    if(number(row,field)!=null&&number(row,field)<0) failures.add("domain:"+field);
                if(number(row,"p_value")!=null&&number(row,"p_value")<=0) failures.add("domain:p_value");
            }
            if(dataset.equals("etf_share") && number(row,"fd_share")!=null && number(row,"fd_share")<0)
                failures.add("domain:fd_share");
            if(dataset.equals("disclosure_date")) {
                if(!Objects.toString(row.get("end_date"),"").matches("[0-9]{8}")) failures.add("format:end_date");
                for(String field:List.of("pre_date","actual_date","modify_date")) {
                    Object value=row.get(field);
                    if(value!=null&&!value.toString().isBlank()&&!value.toString().matches("[0-9]{8}")) failures.add("format:"+field);
                }
            }
            if(dataset.equals("daily_basic") && number(row,"turnover_rate")!=null
                    && (number(row,"turnover_rate")<0 || number(row,"turnover_rate")>100)) failures.add("domain:turnover_rate");
            if(dataset.equals("share_float") && number(row,"float_ratio")!=null
                    && (number(row,"float_ratio")<0 || number(row,"float_ratio")>100)) failures.add("domain:float_ratio");
            if(dataset.equals("share_float") && number(row,"float_share")!=null && number(row,"float_share")<0)
                failures.add("domain:float_share");
        }
        return List.copyOf(failures);
    }
    private static Double number(Map<String,Object> row,String field) {
        Object value=row.get(field);return value==null?null:((Number)value).doubleValue();
    }
}
