package com.zoutrankil.market.us.marketdata;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.DatasetDefinition;
import java.time.*;
import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** 沿用正式 us_market_daily 六列；缺值点不补零，来源日期保留 UTC 零点编码。 */
public final class FredMarketDaily {
    private FredMarketDaily() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    public static final Set<String> SERIES=Set.of("NASDAQCOM","SP500","VIXCLS");
    public static final DatasetDefinition DEFINITION=new DatasetDefinition("us_market_daily",1,"fred.market_close","java.market.us","us_market_daily",ObjectKind.TABLE,List.of(
            field("ts_code",StorageType.SYMBOL,"来源序列代码加 .US",false),field("trade_date",StorageType.TIMESTAMP,"FRED 观测业务日期，不按 A 股日历过滤",true),
            field("close",StorageType.DOUBLE,"指数收盘点位，保留来源精度",false),field("source_id",StorageType.SYMBOL,"FRED 实际序列代码",false),
            field("source_url",StorageType.STRING,"公开序列页面，不含 API key",false),field("update_time",StorageType.TIMESTAMP,"本次来源响应取得时间；不是历史当时可用时间",false)),
            List.of("ts_code","trade_date"),List.of("ts_code","trade_date"),"trade_date",Partition.YEAR,true,Set.of(Capability.READ,Capability.WRITE),List.of(),"兼容既有表及去重键；Java 只补确认缺失的键，保留旧行全部字段，并在补缺后核对旧行未变。");
    public record Parsed(List<Map<String,Object>> rows,int observations,int missingValues) {}
    public static Parsed parse(byte[] bytes,String series,Instant observed,LocalDate from,LocalDate through)throws Exception {
        if(!SERIES.contains(series)||through.isBefore(from))throw new IllegalArgumentException("未知指数序列或倒置窗口");var body=JSON.readTree(bytes);var observations=body.path("observations");
        if(!observations.isArray()||!body.path("count").isIntegralNumber()||body.path("count").asInt()!=observations.size()||body.path("offset").asInt(-1)!=0||observations.size()>=100000)throw new IllegalArgumentException("FRED 分页或完整观测数量证据不足");
        var rows=new ArrayList<Map<String,Object>>();var dates=new HashSet<LocalDate>();int missing=0;
        for(var point:observations){var date=LocalDate.parse(point.path("date").asText());if(date.isBefore(from)||date.isAfter(through)||!dates.add(date))throw new IllegalArgumentException("FRED 日期越界或重复");String value=point.path("value").asText();if(value.equals(".")){missing++;continue;}double close=Double.parseDouble(value);if(!Double.isFinite(close)||close<=0)throw new IllegalArgumentException("FRED 指数点位非法");
            rows.add(Map.of("ts_code",series+".US","trade_date",date.atStartOfDay(ZoneOffset.UTC).toInstant().toString(),"close",close,"source_id",series,"source_url","https://fred.stlouisfed.org/series/"+series,"update_time",observed.toString()));}
        if(rows.isEmpty())throw new IllegalArgumentException("FRED 没有可用指数观测");return new Parsed(rows,observations.size(),missing);
    }
    private static Column field(String name,StorageType type,String meaning,boolean date){return new Column(name,name,name,type,false,meaning,type==StorageType.TIMESTAMP?new TemporalContract(date?TemporalKind.BUSINESS_DATE:TemporalKind.INSTANT,"ISO-8601",date?"calendar":"UTC","MICROS",meaning):null);}
}
