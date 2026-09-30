package com.zoutrankil.batch;

import java.io.*;
import java.math.*;
import java.nio.charset.Charset;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;

/**
 * Streaming semantic projection for DFCF daily L2 files. It preserves every source row,
 * including unclassified deal sides, and does not compute research features.
 */
public final class DfcfCsvParser {
    private static final Charset ENCODING=Charset.forName("GB18030");
    private static final List<String> DEAL=List.of("自然日","时间","成交编号","成交价格","成交数量","BS标志","叫买序号","叫卖序号");
    private static final List<String> ORDER=List.of("自然日","时间","交易所委托号","委托类型","委托代码","委托价格","委托数量");
    private static final List<String> SNAPSHOT= snapshotHeaders();
    @FunctionalInterface public interface RowSink<T> { void accept(T row) throws IOException; }
    public enum DealSide { BUY, SELL, BUY_CANCEL, SELL_CANCEL, UNKNOWN }
    public record Deal(String symbol,LocalDate tradeDate,int time,String dealId,String bsFlag,
                       String buyOrderId,String sellOrderId,BigDecimal priceCny,long volume,DealSide side) {}
    public enum OrderKind { ADD_BUY, ADD_SELL, CANCEL_BUY, CANCEL_SELL, UNKNOWN }
    public record Order(String symbol,LocalDate tradeDate,int time,String orderId,String vendorType,
                        String vendorCode,BigDecimal priceCny,long volume,Integer kuakeOrderType,
                        OrderKind kind,boolean submission) {}
    public record BookLevel(BigDecimal bidPriceCny,long bidVolume,BigDecimal askPriceCny,long askVolume) {}
    public record Quote(String symbol,LocalDate tradeDate,int time,int tickTimeDiff,BigDecimal priceCny,
                        long volume,BigDecimal sourceTurnover,long totalVolume,BigDecimal sourceTotalTurnover,
                        long totalBidVolume,long totalAskVolume,BigDecimal weightedBidPriceCny,
                        BigDecimal weightedAskPriceCny,List<BookLevel> levels) {
        public Quote { levels=List.copyOf(levels); if(levels.size()!=10) throw new IllegalArgumentException("Ten source book levels required"); }
    }
    private record ColumnIndex(Map<String,Integer> index,int width) {}
    private DfcfCsvParser() {}

    public static long scanDeals(InputStream input,String symbol,LocalDate date,long maxBytes,RowSink<Deal> sink) throws IOException {
        String code=normalizeSymbol(symbol); return scan(input,DEAL,date,maxBytes,(row,ix) -> {
            LocalDate day=sourceDate(row,ix);int time=sourceTime(row,ix);String bs=field(row,ix,"BS标志").strip().toUpperCase(Locale.ROOT);
            BigDecimal rawPrice=decimal(row,ix,"成交价格");if(rawPrice==null) throw new IOException("Missing DFCF deal price");
            BigDecimal price=rawPrice.movePointLeft(4);long volume=integer(row,ix,"成交数量",true);
            String buy=identifier(row,ix,"叫买序号"),sell=identifier(row,ix,"叫卖序号");
            DealSide side;
            if(rawPrice.signum()>0 && bs.equals("B")) side=DealSide.BUY;
            else if(rawPrice.signum()>0 && bs.equals("S")) side=DealSide.SELL;
            else if(rawPrice.signum()<=0 && positiveIdentifier(buy)) side=DealSide.BUY_CANCEL;
            else if(rawPrice.signum()<=0 && positiveIdentifier(sell)) side=DealSide.SELL_CANCEL;
            else side=DealSide.UNKNOWN;
            return new Deal(code,day,time,identifier(row,ix,"成交编号"),bs,buy,sell,price,volume,side);
        },sink);
    }

    public static long scanOrders(InputStream input,String symbol,LocalDate date,long maxBytes,RowSink<Order> sink) throws IOException {
        String code=normalizeSymbol(symbol),exchange=code.substring(code.indexOf('.')+1);
        return scan(input,ORDER,date,maxBytes,(row,ix) -> {
            String vendorType=field(row,ix,"委托类型").strip().toUpperCase(Locale.ROOT);
            String vendorCode=field(row,ix,"委托代码").strip().toUpperCase(Locale.ROOT);
            Integer kuake=null;OrderKind kind=OrderKind.UNKNOWN;boolean submitted=false;
            if(exchange.equals("SH")) {
                if(vendorType.equals("A")&&vendorCode.equals("B")){kuake=0;kind=OrderKind.ADD_BUY;submitted=true;}
                else if(vendorType.equals("A")&&vendorCode.equals("S")){kuake=10;kind=OrderKind.ADD_SELL;submitted=true;}
                else if(vendorType.equals("D")&&vendorCode.equals("B")){kuake=-1;kind=OrderKind.CANCEL_BUY;}
                else if(vendorType.equals("D")&&vendorCode.equals("S")){kuake=-11;kind=OrderKind.CANCEL_SELL;}
            } else {
                int offset=switch(vendorType){case "0"->1;case "1"->2;case "U"->3;default->0;};
                if(offset>0&&vendorCode.equals("B")){kuake=offset;kind=OrderKind.ADD_BUY;submitted=true;}
                else if(offset>0&&vendorCode.equals("S")){kuake=10+offset;kind=OrderKind.ADD_SELL;submitted=true;}
            }
            BigDecimal raw=decimal(row,ix,"委托价格");if(raw==null) throw new IOException("Missing DFCF order price");
            return new Order(code,sourceDate(row,ix),sourceTime(row,ix),identifier(row,ix,"交易所委托号"),vendorType,vendorCode,
                    raw.movePointLeft(4),integer(row,ix,"委托数量",true),kuake,kind,submitted);
        },sink);
    }

    public static long scanQuotes(InputStream input,String symbol,LocalDate date,long maxBytes,RowSink<Quote> sink) throws IOException {
        String code=normalizeSymbol(symbol);int[] previous={-1};
        return scan(input,SNAPSHOT,date,maxBytes,(row,ix) -> {
            int time=sourceTime(row,ix),diff=previous[0]<0?0:Math.max(0,time-previous[0]);previous[0]=time;
            var levels=new ArrayList<BookLevel>(10);
            for(int i=1;i<=10;i++) levels.add(new BookLevel(priceCny(row,ix,"申买价"+i),integerOrZero(row,ix,"申买量"+i),
                    priceCny(row,ix,"申卖价"+i),integerOrZero(row,ix,"申卖量"+i)));
            return new Quote(code,sourceDate(row,ix),time,diff,priceCny(row,ix,"成交价"),integerOrZero(row,ix,"成交量"),
                    decimalOrZero(row,ix,"成交额"),integerOrZero(row,ix,"当日累计成交量"),
                    decimalOrZero(row,ix,"当日成交额"),integerOrZero(row,ix,"叫买总量"),integerOrZero(row,ix,"叫卖总量"),
                    priceCny(row,ix,"加权平均叫买价"),priceCny(row,ix,"加权平均叫卖价"),levels);
        },sink);
    }

    private static <T> long scan(InputStream input,List<String> required,LocalDate date,long maxBytes,
                                 RowMapper<T> mapper,RowSink<T> sink) throws IOException {
        if(maxBytes<1) throw new IllegalArgumentException("Positive CSV byte bound required");
        var bounded=new BoundedInputStream(Objects.requireNonNull(input),maxBytes);
        try(var reader=new PushbackReader(new BufferedReader(new InputStreamReader(bounded,ENCODING),64*1024),1)) {
            List<String> header=DfcfCsvInspector.readRecord(reader);
            if(header==null) throw new IOException("DFCF CSV is empty");
            if(!header.isEmpty()&&header.getFirst().startsWith("\uFEFF")) header.set(0,header.getFirst().substring(1));
            if(new HashSet<>(header).size()!=header.size()) throw new IOException("DFCF CSV contains duplicate headers");
            var missing=required.stream().filter(c->!header.contains(c)).toList();
            if(!missing.isEmpty()) throw new IOException("DFCF CSV missing required fields: "+missing);
            var indexes=new HashMap<String,Integer>();for(String field:header) indexes.put(field,header.indexOf(field));
            var columns=new ColumnIndex(Map.copyOf(indexes),header.size());long count=0;
            for(List<String> row;(row=DfcfCsvInspector.readRecord(reader))!=null;) {
                if(row.size()!=columns.width()) throw new IOException("DFCF row width differs from header");
                LocalDate actual=sourceDate(row,columns);
                if(!actual.equals(date)) throw new IOException("DFCF row date mismatch: "+actual+" != "+date);
                sink.accept(mapper.map(row,columns));count++;
            }
            return count;
        }
    }
    @FunctionalInterface private interface RowMapper<T> { T map(List<String> row,ColumnIndex indexes) throws IOException; }
    private static String field(List<String> row,ColumnIndex ix,String name) { return row.get(ix.index().get(name)); }
    private static LocalDate sourceDate(List<String> row,ColumnIndex ix) throws IOException {
        String text=field(row,ix,"自然日").strip();
        try { if(!text.matches("\\d{8}")) throw new DateTimeException("format");return LocalDate.parse(text.substring(0,4)+"-"+text.substring(4,6)+"-"+text.substring(6,8)); }
        catch(DateTimeException error) { throw new IOException("Invalid DFCF natural day",error); }
    }
    private static int sourceTime(List<String> row,ColumnIndex ix) throws IOException {
        try { int value=Math.toIntExact(new BigInteger(field(row,ix,"时间").strip()).longValueExact());if(value<0||value>235959999) throw new NumberFormatException();return value; }
        catch(RuntimeException error) { throw new IOException("Invalid DFCF time",error); }
    }
    private static BigDecimal decimal(List<String> row,ColumnIndex ix,String name) throws IOException {
        String text=field(row,ix,name).strip();if(text.isEmpty()) return null;
        try { BigDecimal value=new BigDecimal(text);if(!Double.isFinite(value.doubleValue())) throw new NumberFormatException();return value; }
        catch(NumberFormatException error) { throw new IOException("Invalid DFCF number in "+name,error); }
    }
    private static BigDecimal decimalOrZero(List<String> row,ColumnIndex ix,String name) throws IOException {
        BigDecimal value=decimal(row,ix,name);return value==null?BigDecimal.ZERO:value;
    }
    private static BigDecimal priceCny(List<String> row,ColumnIndex ix,String name) throws IOException {
        BigDecimal raw=decimal(row,ix,name);return raw==null?null:raw.movePointLeft(4);
    }
    private static long integer(List<String> row,ColumnIndex ix,String name,boolean required) throws IOException {
        String value=field(row,ix,name).strip();if(value.isEmpty()) { if(required) throw new IOException("Missing DFCF integer in "+name);return 0; }
        try { return new BigDecimal(value).longValueExact(); }
        catch(ArithmeticException|NumberFormatException error) { throw new IOException("Invalid DFCF integer in "+name,error); }
    }
    private static long integerOrZero(List<String> row,ColumnIndex ix,String name) throws IOException { return integer(row,ix,name,false); }
    private static String identifier(List<String> row,ColumnIndex ix,String name) throws IOException {
        String value=field(row,ix,name).strip();if(value.isEmpty()) return "";
        try { return new BigDecimal(value).toBigIntegerExact().toString(); }
        catch(ArithmeticException|NumberFormatException error) { throw new IOException("Invalid DFCF identifier in "+name,error); }
    }
    private static boolean positiveIdentifier(String value) { try { return !value.isBlank()&&new BigInteger(value).signum()>0; } catch(NumberFormatException e) { return false; } }
    private static String normalizeSymbol(String symbol) {
        String text=Objects.requireNonNull(symbol).strip().toUpperCase(Locale.ROOT);String[] parts=text.split("\\.",-1);
        if(parts.length!=2||!parts[0].matches("\\d{1,6}")||!Set.of("SH","SZ","BJ").contains(parts[1])) throw new IllegalArgumentException("Invalid mainland equity symbol");
        String code="0".repeat(6-parts[0].length())+parts[0];
        // Match the existing DFCF source's code-prefix normalization for mislabeled ETFs/funds.
        String exchange=code.startsWith("5")||code.startsWith("6")||code.startsWith("9")||code.startsWith("11")?"SH":
                code.startsWith("00")||code.startsWith("001")||code.startsWith("002")||code.startsWith("003")||code.startsWith("12")||code.startsWith("15")||code.startsWith("16")||code.startsWith("30")?"SZ":
                        code.startsWith("4")||code.startsWith("8")?"BJ":parts[1];
        return code+"."+exchange;
    }
    private static List<String> snapshotHeaders() {
        var result=new ArrayList<>(List.of("自然日","时间","成交价","成交量","成交额","当日累计成交量","当日成交额","叫买总量","叫卖总量","加权平均叫买价","加权平均叫卖价"));
        for(int i=1;i<=10;i++) result.add("申买价"+i);
        for(int i=1;i<=10;i++) result.add("申卖价"+i);
        for(int i=1;i<=10;i++) result.add("申买量"+i);
        for(int i=1;i<=10;i++) result.add("申卖量"+i);
        return List.copyOf(result);
    }
    private static final class BoundedInputStream extends FilterInputStream {
        private final long max;private long count;
        BoundedInputStream(InputStream in,long max){super(in);this.max=max;}
        private void add(long amount) throws IOException {count+=amount;if(count>max)throw new IOException("DFCF CSV exceeds configured expanded-byte bound");}
        @Override public int read() throws IOException {int value=super.read();if(value>=0)add(1);return value;}
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {int n=super.read(bytes,offset,length);if(n>0)add(n);return n;}
    }
}
