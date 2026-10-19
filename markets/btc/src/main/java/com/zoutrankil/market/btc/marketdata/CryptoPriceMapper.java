package com.zoutrankil.market.btc.marketdata;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Binance 单归档先验 SHA，再解压；只接受明确的毫秒/微秒与完整连续小时。 */
public final class CryptoPriceMapper {
    private CryptoPriceMapper() {}
    public static List<Map<String,Object>> binance(byte[] zip,String symbol,boolean perpetual,String sha,Instant observed,LocalDate from,LocalDate through)throws Exception {
        byte[] csv;
        try(var input=new ZipInputStream(new ByteArrayInputStream(zip))) {
            var entry=input.getNextEntry();if(entry==null||!entry.getName().endsWith(".csv"))throw new IllegalArgumentException("Binance 归档不是单 CSV");
            csv=input.readNBytes(8*1024*1024+1);if(csv.length>8*1024*1024||input.getNextEntry()!=null)throw new IllegalArgumentException("Binance 归档超过有界 CSV 范围");
        }
        var rows=new ArrayList<Map<String,Object>>();
        for(String line:new String(csv,StandardCharsets.UTF_8).split("\\R")) {
            if(line.isBlank()||line.startsWith("open_time,"))continue;String[] c=line.split(",",-1);if(c.length<11)throw new IllegalArgumentException("Binance OHLCV 字段不足");
            long epoch=Long.parseLong(c[0]);boolean micros=epoch>=100_000_000_000_000L;
            Instant ts=micros?Instant.ofEpochSecond(epoch/1_000_000,(epoch%1_000_000)*1000):Instant.ofEpochMilli(epoch);
            boolean expectedMicros=!perpetual&&!ts.isBefore(Instant.parse("2025-01-01T00:00:00Z"));
            if(micros!=expectedMicros)throw new IllegalArgumentException("Binance 时间单位与现货/合约日期不符");
            rows.add(row(ts,"BINANCE",symbol,perpetual?"USDT_PERPETUAL":"SPOT",60,Arrays.asList(c[1],c[2],c[3],c[4],c[5],c[7]),sha,observed));
        }
        requireContinuous(rows,from,through,60);return rows;
    }
    public static Map<String,Object> row(Instant ts,String exchange,String symbol,String instrument,int minutes,List<String> numbers,String sha,Instant observed) {
        if(!Set.of(60,1440).contains(minutes)||ts.getNano()!=0||ts.getEpochSecond()%(minutes*60L)!=0||!Set.of("BTCUSDT","ETHUSDT").contains(symbol)
                ||!Set.of("BINANCE","BYBIT").contains(exchange)||!Set.of("SPOT","USDT_PERPETUAL").contains(instrument)||numbers.size()!=6)
            throw new IllegalArgumentException("币对、时间边界或频率非法");
        var r=new LinkedHashMap<String,Object>();r.put("ts",ts.toString());r.put("exchange",exchange);r.put("symbol",symbol);r.put("instrument",instrument);r.put("interval_minutes",minutes);
        String[] fields={"open","high","low","close","volume","quote_volume"};
        for(int i=0;i<fields.length;i++) {double value=Double.parseDouble(numbers.get(i));if(!Double.isFinite(value)||value<0||(i<4&&value==0))throw new IllegalArgumentException("币价/成交量非法");r.put(fields[i],value);}
        if((double)r.get("high")<Math.max((double)r.get("open"),(double)r.get("close"))||(double)r.get("low")>Math.min((double)r.get("open"),(double)r.get("close")))throw new IllegalArgumentException("币价 OHLC 关系非法");
        r.put("source_sha256",sha);r.put("observed_at",observed.toString());return r;
    }
    public static void requireContinuous(List<Map<String,Object>> rows,LocalDate from,LocalDate through,int minutes) {
        var sorted=rows.stream().map(r->Instant.parse(r.get("ts").toString())).sorted().toList();
        long start=from.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),end=through.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        if(end<=start||sorted.size()!=(end-start)/(minutes*60L))throw new IllegalArgumentException("加密来源区间有缺口或多余数据");
        for(int i=0;i<sorted.size();i++)if(sorted.get(i).getEpochSecond()!=start+i*minutes*60L)throw new IllegalArgumentException("加密来源缺小时或重复业务键");
    }
    public static List<Map<String,Object>> daily(List<Map<String,Object>> hourly) {
        var days=new TreeMap<LocalDate,List<Map<String,Object>>>();for(var row:hourly)days.computeIfAbsent(Instant.parse(row.get("ts").toString()).atZone(ZoneOffset.UTC).toLocalDate(),k->new ArrayList<>()).add(row);
        var output=new ArrayList<Map<String,Object>>();for(var entry:days.entrySet()) {
            var rows=entry.getValue().stream().sorted(Comparator.comparing(r->r.get("ts").toString())).toList();requireContinuous(rows,entry.getKey(),entry.getKey(),60);
            var first=rows.getFirst();for(var r:rows)for(String key:List.of("exchange","symbol","instrument","source_sha256","observed_at"))if(!Objects.equals(r.get(key),first.get(key)))throw new IllegalArgumentException("不能把不同来源或币对合成日线");
            var r=new LinkedHashMap<>(first);r.put("ts",entry.getKey().atStartOfDay(ZoneOffset.UTC).toInstant().toString());r.put("interval_minutes",1440);
            r.put("high",rows.stream().mapToDouble(x->((Number)x.get("high")).doubleValue()).max().orElseThrow());r.put("low",rows.stream().mapToDouble(x->((Number)x.get("low")).doubleValue()).min().orElseThrow());r.put("close",rows.getLast().get("close"));
            for(String name:List.of("volume","quote_volume"))r.put(name,rows.stream().mapToDouble(x->((Number)x.get(name)).doubleValue()).sum());output.add(r);
        }return output;
    }
}
