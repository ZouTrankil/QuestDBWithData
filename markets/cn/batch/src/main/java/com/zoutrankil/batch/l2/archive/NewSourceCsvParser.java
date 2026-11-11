package com.zoutrankil.batch.l2.archive;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 新来源 CSV 解析；沪市使用处理后委托，深市使用原始委托。 */
public final class NewSourceCsvParser {
    interface Sink { void accept(Map<String,String> row) throws Exception; }
    private static List<String> expected(String name) throws IOException {
        if(name.equals("逐笔成交.csv")) return List.of("SecuCode","TradingDay","DealTime","DealID","BuyID","SellID","Price","Volume","Side","Channel","BizIndex");
        if(name.equals("逐笔委托原始.csv")) return List.of("SecuCode","TradingDay","OrderTime","OrderID","DBOrderID","OrderType","Price","Volume","Channel","BizIndex");
        if(name.equals("逐笔委托.csv")) return List.of("SecuCode","TradingDay","OrderTime","OrderID","DBOrderID","OrderType","Price","Volume","LastPrice","Channel","BizIndex");
        if(name.equals("行情快照.csv")) {
            var h=new ArrayList<>(List.of("SecuCode","TradingDay","TickTime","Price","Volume","Turnover"));
            for(String prefix:List.of("AskPrice","AskVolume","BidPrice","BidVolume","AskOrder","BidOrder")) for(int i=1;i<=10;i++) h.add(prefix+i);
            h.addAll(List.of("TotalBidVolume","TotalAskVolume","TotalVolume","TotalTurnover","DealNum","TotalDealNum","WeightAskPrice","WeightBidPrice","TickTimeDiff"));
            return List.copyOf(h);
        }
        throw new IOException("unexpected source file");
    }
    private static List<String> chineseExpected(String name) throws IOException {
if(name.equals("逐笔成交.csv")) return List.of("交易所代码","自然日","时间","成交编号","叫买序号","叫卖序号","成交价格","成交数量","方向","行情频道号","流水号");
if(name.equals("逐笔委托原始.csv")) return List.of("交易所代码","自然日","订单时间","交易所委托号","委托编号","委托类型","委托价格","委托数量","行情频道号","流水号");
if(name.equals("逐笔委托.csv")) return List.of("交易所代码","自然日","订单时间","交易所委托号","委托编号","委托类型","委托价格","委托数量","前成交价格","行情频道号","流水号");
if(name.equals("行情快照.csv")) return List.of("交易所代码","自然日","时间","成交价","成交量","成交额","申卖价1","申卖价2","申卖价3","申卖价4","申卖价5","申卖价6","申卖价7","申卖价8","申卖价9","申卖价10","申卖量1","申卖量2","申卖量3","申卖量4","申卖量5","申卖量6","申卖量7","申卖量8","申卖量9","申卖量10","申买价1","申买价2","申买价3","申买价4","申买价5","申买价6","申买价7","申买价8","申买价9","申买价10","申买量1","申买量2","申买量3","申买量4","申买量5","申买量6","申买量7","申买量8","申买量9","申买量10","委卖笔数1","委卖笔数2","委卖笔数3","委卖笔数4","委卖笔数5","委卖笔数6","委卖笔数7","委卖笔数8","委卖笔数9","委卖笔数10","委买笔数1","委买笔数2","委买笔数3","委买笔数4","委买笔数5","委买笔数6","委买笔数7","委买笔数8","委买笔数9","委买笔数10","未成交委买总量","未成交委卖总量","成交总量","成交总金额","间隔内成交笔数","累计成交笔数","卖方加权平均委卖价","买方加权平均委买价","快照间隔");
throw new IOException("unexpected source file");
    }
    private static void validateHeader(List<String> header,Path file) throws IOException {
        if(header!=null && !header.isEmpty() && header.getFirst().startsWith("\uFEFF")) header.set(0,header.getFirst().substring(1));
        if(header != null && header.equals(chineseExpected(file.getFileName().toString()))) { header.clear(); header.addAll(expected(file.getFileName().toString())); }
        if(header==null || !header.equals(expected(file.getFileName().toString()))) throw new IOException("source schema drift");
    }
    private static void read(Path file, String symbol, String day, Set<String> channels, Sink sink) throws Exception {
        long size=Files.size(file); var modified=Files.getLastModifiedTime(file);
        if (size > 536870912L) throw new IOException("source too large");
        try (var r=new DfcfCsvInspector.BufferedPushbackReader(Files.newBufferedReader(file,StandardCharsets.UTF_8))) {
            var header=DfcfCsvInspector.readRecord(r);
            validateHeader(header,file);
            List<String> values;
            while ((values=DfcfCsvInspector.readRecord(r))!=null) {
                if(values.size()!=header.size()) throw new IOException("row width mismatch");
                var row=new HashMap<String,String>();
                for(int i=0;i<header.size();i++) row.put(header.get(i),values.get(i));
                if(!symbol.equals(row.get("SecuCode")) || !day.equals(row.get("TradingDay"))) throw new IOException("source identity mismatch");
                String channel=req(row,"Channel");
                // -99 是待证明归属的合成行，只能在处理后委托中出现。
                if(channel.equals("-99") && !file.getFileName().toString().equals("逐笔委托.csv")) throw new IOException("unanchored channel");
                if(!channel.equals("-99")) channels.add(channel);
                if(channels.size()>1) throw new IOException("multiple channels need explicit identity rules");
                sink.accept(row);
            }
        }
        if(Files.size(file)!=size || !Files.getLastModifiedTime(file).equals(modified)) throw new IOException("source changed");
    }
    private static String req(Map<String,String> r,String key) throws IOException {
        String v=r.get(key); if(v==null || v.isBlank()) throw new IOException("missing "+key); return v;
    }
    private static BigDecimal n(Map<String,String> r,String key) throws IOException {
        try { var v=new BigDecimal(req(r,key)); if(!Double.isFinite(v.doubleValue())) throw new NumberFormatException(); return v; }
        catch(NumberFormatException e) { throw new IOException("bad numeric "+key,e); }
    }
    private static BigDecimal price(Map<String,String> r,String key) throws IOException { return n(r,key).movePointLeft(2); }
    private static int time(Map<String,String> r,String key) throws IOException {
        try { int t=n(r,key).intValueExact(); if(t<0 || t>235959999 || (t/100000)%100>59 || (t/1000)%100>59) throw new ArithmeticException(); return t; }
        catch(ArithmeticException e) { throw new IOException("bad time",e); }
    }
    private static Path symbolDirectory(Path root,String code,String exchange) throws IOException {
        if(!code.matches("\\d{6}") || !Set.of("SH","SZ").contains(exchange)) throw new IOException("invalid source identity");
        Path base=root.toRealPath(), dir=base.resolve(code);
        if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS) || !dir.toRealPath().getParent().equals(base)) throw new IOException("unsafe source directory");
        return dir;
    }
    public static DfcfCsvParser.ProductionDay load(Path root,String code,String exchange,String day) throws Exception {
        String canonical=code+"."+exchange; LocalDate date=LocalDate.parse(day,DateTimeFormatter.BASIC_ISO_DATE); Path dir=symbolDirectory(root,code,exchange);
        var deals=new ArrayList<DfcfCsvParser.Deal>(); var orders=new ArrayList<DfcfCsvParser.Order>(); var quotes=new ArrayList<DfcfCsvParser.Quote>(); var channels=new HashSet<String>(); var buyIds=new HashSet<String>(); var sellIds=new HashSet<String>();
        // 供应商文档与样本方向不一致；这里采用用户确认的 0 买、1 卖推断。
        read(dir.resolve("逐笔成交.csv"),code,day,channels,r->{
            String side=req(r,"Side"); var s=switch(side) { case "0" -> DfcfCsvParser.DealSide.BUY; case "1" -> DfcfCsvParser.DealSide.SELL; case "-1" -> DfcfCsvParser.DealSide.BUY_CANCEL; case "-11" -> DfcfCsvParser.DealSide.SELL_CANCEL; default -> throw new IOException("unknown side"); };
            var p=price(r,"Price"); String buy=req(r,"BuyID"),sell=req(r,"SellID");
            if(!buy.equals("0") && !buy.equals("-9")) buyIds.add(buy);
            if(!sell.equals("0") && !sell.equals("-9")) sellIds.add(sell);
            if((s==DfcfCsvParser.DealSide.BUY || s==DfcfCsvParser.DealSide.SELL) && p.signum()<=0) throw new IOException("nonpositive trade");
            if((s==DfcfCsvParser.DealSide.BUY_CANCEL && (p.signum()>0 || Long.parseLong(buy)<=0 || Long.parseLong(sell)>0)) || (s==DfcfCsvParser.DealSide.SELL_CANCEL && (p.signum()>0 || Long.parseLong(sell)<=0 || Long.parseLong(buy)>0))) throw new IOException("cancel identity mismatch");
            // 负成交量是来源异常，不能进入正式特征计算。
            var volume=n(r,"Volume");
            if(volume.signum()<0) throw new IOException("negative deal volume");
            deals.add(new DfcfCsvParser.Deal(canonical,date,time(r,"DealTime"),req(r,"DealID"),s==DfcfCsvParser.DealSide.BUY?"B":s==DfcfCsvParser.DealSide.SELL?"S":"",buy,sell,p,volume,s));
        });
        // 沪市处理后委托包含新增订单和数量修订；深市保留原始委托以免重复合成撤单。
        read(dir.resolve(exchange.equals("SH")?"逐笔委托.csv":"逐笔委托原始.csv"),code,day,channels,r->{
            int typ=n(r,"OrderType").intValueExact(); boolean buy; boolean cancel=false; String vendorType;
            if(exchange.equals("SH")) { if(typ!=0 && typ!=10 && typ!=-1 && typ!=-11) throw new IOException("unknown SH order"); buy=typ==0 || typ==-1; cancel=typ<0; vendorType=cancel?"D":"A"; }
            else { if(typ!=1 && typ!=2 && typ!=3 && typ!=11 && typ!=12 && typ!=13) throw new IOException("unknown SZ order"); buy=typ<10; int base=buy?typ:typ-10; vendorType=base==1?"0":base==2?"1":"U"; }
            if(req(r,"Channel").equals("-99") && (!exchange.equals("SH") || cancel || channels.size()!=1 || !(buy?buyIds:sellIds).contains(req(r,"OrderID")))) throw new IOException("synthetic order channel not anchored");
            var kind=cancel?(buy?DfcfCsvParser.OrderKind.CANCEL_BUY:DfcfCsvParser.OrderKind.CANCEL_SELL):(buy?DfcfCsvParser.OrderKind.ADD_BUY:DfcfCsvParser.OrderKind.ADD_SELL);
            orders.add(new DfcfCsvParser.Order(canonical,date,time(r,"OrderTime"),req(r,"OrderID"),vendorType,buy?"B":"S",price(r,"Price"),n(r,"Volume"),typ,kind,!cancel));
        });
        int[] previous={-1};
        // 行情快照没有频道列，不参与订单频道校验。
        readQuotes(dir.resolve("行情快照.csv"),code,day,r->{
            int t=time(r,"TickTime"), diff=previous[0]<0?0:Math.max(0,t-previous[0]); previous[0]=t;
            var levels=new ArrayList<DfcfCsvParser.BookLevel>();
            for(int i=1;i<=10;i++) levels.add(new DfcfCsvParser.BookLevel(price(r,"BidPrice"+i),n(r,"BidVolume"+i),price(r,"AskPrice"+i),n(r,"AskVolume"+i)));
            quotes.add(new DfcfCsvParser.Quote(canonical,date,t,diff,price(r,"Price"),n(r,"Volume"),n(r,"Turnover"),n(r,"TotalVolume"),n(r,"TotalTurnover"),n(r,"TotalBidVolume"),n(r,"TotalAskVolume"),price(r,"WeightBidPrice"),price(r,"WeightAskPrice"),levels));
        });
        return DfcfCsvParser.normalizeProductionDay(canonical,date,deals,orders,quotes);
    }
    private static void readQuotes(Path file,String symbol,String day,Sink sink) throws Exception {
        long size=Files.size(file); var modified=Files.getLastModifiedTime(file);
        if(size>536870912L) throw new IOException("source too large");
        try(var reader=new DfcfCsvInspector.BufferedPushbackReader(Files.newBufferedReader(file,StandardCharsets.UTF_8))) {
            var header=DfcfCsvInspector.readRecord(reader);
            validateHeader(header,file);
            List<String> values;
            while((values=DfcfCsvInspector.readRecord(reader))!=null) {
                if(values.size()!=header.size()) throw new IOException("row width mismatch");
                var row=new HashMap<String,String>(); for(int i=0;i<header.size();i++) row.put(header.get(i),values.get(i));
                if(!symbol.equals(row.get("SecuCode")) || !day.equals(row.get("TradingDay"))) throw new IOException("source identity mismatch");
                sink.accept(row);
            }
        }
        if(Files.size(file)!=size || !Files.getLastModifiedTime(file).equals(modified)) throw new IOException("source changed");
    }
    public static Map<String,String> fingerprint(Path dir) throws Exception {
        var result=new TreeMap<String,String>();
        for(String name:List.of("行情快照.csv","逐笔成交.csv","逐笔委托.csv","逐笔委托原始.csv")) {
            Path p=dir.resolve(name);
            if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing regular source "+name);
            try(var reader=new DfcfCsvInspector.BufferedPushbackReader(Files.newBufferedReader(p,StandardCharsets.UTF_8))) { validateHeader(DfcfCsvInspector.readRecord(reader),p); }
            var digest=MessageDigest.getInstance("SHA-256"); long size=Files.size(p); var modified=Files.getLastModifiedTime(p);
            try(var in=Files.newInputStream(p)) { byte[] buf=new byte[65536]; for(int n;(n=in.read(buf))!=-1;) digest.update(buf,0,n); }
            if(Files.size(p)!=size || !Files.getLastModifiedTime(p).equals(modified)) throw new IOException("source changed while hashing");
            result.put(name,HexFormat.of().formatHex(digest.digest()));
        }
        return result;
    }
}
