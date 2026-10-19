package com.zoutrankil.market.us.marketdata;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;

/** Yahoo 报价本身经过拆股调整；用本响应全部后续拆股还原原币价及股数，并明确记录重建基准。 */
public final class YahooPriceMapper {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ZoneId NY=ZoneId.of("America/New_York");
    private YahooPriceMapper() {}
    public static List<Map<String,Object>> daily(byte[] bytes,String symbol,String yahooSymbol,String sha,Instant observed,LocalDate from,LocalDate through)throws Exception {
        var rows=dailyWindow(bytes,symbol,yahooSymbol,sha,observed,from,through);
        if(rows.isEmpty())throw new IllegalArgumentException("Yahoo 请求区间没有可用日线");return rows;
    }
    /** 完整响应已经验收后按年份投影；空年份不造行，后续拆股仍取完整响应。 */
    public static List<Map<String,Object>> dailyWindow(byte[] bytes,String symbol,String yahooSymbol,String sha,Instant observed,LocalDate from,LocalDate through)throws Exception {
        if(through.isBefore(from))throw new IllegalArgumentException("Yahoo 日期窗口倒置");
        var chart=JSON.readTree(bytes).path("chart");if(!chart.path("error").isNull()||chart.path("result").size()!=1)throw new IllegalArgumentException("Yahoo 未返回唯一成功序列");
        var result=chart.path("result").get(0);var meta=result.path("meta");
        if(!meta.path("symbol").asText().equals(yahooSymbol)||!meta.path("currency").asText().equals("USD")||!meta.path("exchangeTimezoneName").asText().equals("America/New_York")
                ||!Set.of("EQUITY","ETF").contains(meta.path("instrumentType").asText()))throw new IllegalArgumentException("Yahoo 代码、币种、市场时区或证券类型不符");
        var splits=new TreeMap<LocalDate,Double>();var dividends=new HashMap<LocalDate,Double>();
        for(var event:result.path("events").path("splits")) {
            var day=day(event.path("date").asLong());double numerator=positive(event.path("numerator")),denominator=positive(event.path("denominator"));
            if(splits.put(day,numerator/denominator)!=null)throw new IllegalArgumentException("Yahoo 拆股日期重复");
        }
        for(var event:result.path("events").path("dividends"))if(dividends.put(day(event.path("date").asLong()),number(event.path("amount")))!=null)throw new IllegalArgumentException("Yahoo 分红日期重复");
        var stamps=result.path("timestamp");var quote=result.path("indicators").path("quote");var adjusted=result.path("indicators").path("adjclose");
        if(!stamps.isArray()||quote.size()!=1||adjusted.size()!=1)throw new IllegalArgumentException("Yahoo 行情数组不足");
        var q=quote.get(0);var adj=adjusted.get(0).path("adjclose");for(String field:List.of("open","high","low","close","volume"))if(q.path(field).size()!=stamps.size())throw new IllegalArgumentException("Yahoo 字段数组长度不一致");
        if(adj.size()!=stamps.size())throw new IllegalArgumentException("Yahoo 调整收盘价数组长度不一致");
        var days=new HashSet<LocalDate>();var output=new ArrayList<Map<String,Object>>();
        for(int i=0;i<stamps.size();i++) {
            var date=day(stamps.get(i).asLong());if(date.isBefore(from)||date.isAfter(through))continue;if(!days.add(date))throw new IllegalArgumentException("Yahoo 纽约日期重复");
            double factor=1;for(double split:splits.tailMap(date,false).values())factor*=split;if(!Double.isFinite(factor)||factor<=0)throw new IllegalArgumentException("Yahoo 累计拆股因子非法");
            double close=positive(q.path("close").get(i)),adjClose=positive(adj.get(i)),ratio=adjClose/close;
            var row=new LinkedHashMap<String,Object>();row.put("trade_date",date.atStartOfDay(ZoneOffset.UTC).toInstant().toString());row.put("symbol",symbol);row.put("provider","YAHOO");row.put("venue","US_COMPOSITE");
            for(String field:List.of("open","high","low","close")){double value=positive(q.path(field).get(i));row.put(field,value*factor);row.put("adj_"+field,value*ratio);}
            double volume=number(q.path("volume").get(i));if(volume<0)throw new IllegalArgumentException("Yahoo 成交股数为负");row.put("volume",volume/factor);row.put("adj_volume",volume);
            row.put("dividend",dividends.getOrDefault(date,0.)*factor);row.put("split_factor",splits.getOrDefault(date,1.));row.put("currency","USD");row.put("raw_price_basis","RECONSTRUCTED_FROM_YAHOO_SPLIT_ADJUSTED");
            row.put("source_sha256",sha);row.put("observed_at",observed.toString());output.add(row);
        }
        return output.stream().sorted(Comparator.comparing(r->r.get("trade_date").toString())).toList();
    }
    private static LocalDate day(long seconds){return Instant.ofEpochSecond(seconds).atZone(NY).toLocalDate();}
    private static double positive(JsonNode value){double n=number(value);if(n<=0)throw new IllegalArgumentException("Yahoo 价格或拆股因子必须为正");return n;}
    private static double number(JsonNode value){if(value==null||!value.isNumber()||!Double.isFinite(value.doubleValue()))throw new IllegalArgumentException("Yahoo 数值缺失或非法，不能补零");return value.doubleValue();}
}
