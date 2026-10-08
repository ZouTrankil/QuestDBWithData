package com.zoutrankil.batch;

import java.io.*;
import java.math.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * DFCF daily L2 projection. The streaming scanners preserve source evidence; the production
 * day projection follows DfcfCsvLevel2Source v1.2 and KuakeL2Parser v1.1.
 */
public final class DfcfCsvParser {
    private static final Charset ENCODING=Charset.forName("GB18030");
    private static final List<String> DEAL=List.of("自然日","时间","成交编号","成交价格","成交数量","BS标志","叫买序号","叫卖序号");
    private static final List<String> ORDER=List.of("自然日","时间","交易所委托号","委托类型","委托代码","委托价格","委托数量");
    private static final List<String> SNAPSHOT= snapshotHeaders();
    @FunctionalInterface public interface RowSink<T> { void accept(T row) throws IOException; }
    public enum DealSide { BUY, SELL, BUY_CANCEL, SELL_CANCEL, UNKNOWN }
    public record Deal(String symbol,LocalDate tradeDate,int time,String dealId,String bsFlag,
                       String buyOrderId,String sellOrderId,BigDecimal priceCny,BigDecimal volume,DealSide side,
                       @com.fasterxml.jackson.annotation.JsonIgnore boolean sourceTimePresent) {
        public Deal(String symbol,LocalDate tradeDate,int time,String dealId,String bsFlag,
                    String buyOrderId,String sellOrderId,BigDecimal priceCny,BigDecimal volume,DealSide side) {
            this(symbol,tradeDate,time,dealId,bsFlag,buyOrderId,sellOrderId,priceCny,volume,side,true);
        }
        public Deal(String symbol,LocalDate tradeDate,int time,String dealId,String bsFlag,
                    String buyOrderId,String sellOrderId,BigDecimal priceCny,long volume,DealSide side) {
            this(symbol,tradeDate,time,dealId,bsFlag,buyOrderId,sellOrderId,priceCny,BigDecimal.valueOf(volume),side);
        }
    }
    public enum OrderKind { ADD_BUY, ADD_SELL, CANCEL_BUY, CANCEL_SELL, UNKNOWN }
    public record Order(String symbol,LocalDate tradeDate,int time,String orderId,String vendorType,
                        String vendorCode,BigDecimal priceCny,BigDecimal volume,Integer kuakeOrderType,
                        OrderKind kind,boolean submission,@com.fasterxml.jackson.annotation.JsonIgnore boolean sourceTimePresent) {
        public Order(String symbol,LocalDate tradeDate,int time,String orderId,String vendorType,
                     String vendorCode,BigDecimal priceCny,BigDecimal volume,Integer kuakeOrderType,OrderKind kind,boolean submission) {
            this(symbol,tradeDate,time,orderId,vendorType,vendorCode,priceCny,volume,kuakeOrderType,kind,submission,true);
        }
        public Order(String symbol,LocalDate tradeDate,int time,String orderId,String vendorType,
                     String vendorCode,BigDecimal priceCny,long volume,Integer kuakeOrderType,OrderKind kind,boolean submission) {
            this(symbol,tradeDate,time,orderId,vendorType,vendorCode,priceCny,BigDecimal.valueOf(volume),kuakeOrderType,kind,submission);
        }
    }
    public record BookLevel(BigDecimal bidPriceCny,BigDecimal bidVolume,BigDecimal askPriceCny,BigDecimal askVolume) {
        public BookLevel(BigDecimal bidPriceCny,long bidVolume,BigDecimal askPriceCny,long askVolume) {
            this(bidPriceCny,BigDecimal.valueOf(bidVolume),askPriceCny,BigDecimal.valueOf(askVolume));
        }
    }
    public record Quote(String symbol,LocalDate tradeDate,int time,int tickTimeDiff,BigDecimal priceCny,
                        BigDecimal volume,BigDecimal sourceTurnover,BigDecimal totalVolume,BigDecimal sourceTotalTurnover,
                        BigDecimal totalBidVolume,BigDecimal totalAskVolume,BigDecimal weightedBidPriceCny,
                        BigDecimal weightedAskPriceCny,List<BookLevel> levels) {
        public Quote { levels=List.copyOf(levels); if(levels.size()!=10) throw new IllegalArgumentException("Ten source book levels required"); }
        public Quote(String symbol,LocalDate tradeDate,int time,int tickTimeDiff,BigDecimal priceCny,
                     long volume,BigDecimal sourceTurnover,long totalVolume,BigDecimal sourceTotalTurnover,
                     long totalBidVolume,long totalAskVolume,BigDecimal weightedBidPriceCny,BigDecimal weightedAskPriceCny,List<BookLevel> levels) {
            this(symbol,tradeDate,time,tickTimeDiff,priceCny,BigDecimal.valueOf(volume),sourceTurnover,BigDecimal.valueOf(totalVolume),
                    sourceTotalTurnover,BigDecimal.valueOf(totalBidVolume),BigDecimal.valueOf(totalAskVolume),weightedBidPriceCny,weightedAskPriceCny,levels);
        }
    }
    /** Prices are CNY. Unknown sides/types are excluded and each table is stably time ordered. */
    public record ProductionDay(String symbol,LocalDate tradeDate,List<Deal> deals,List<Order> orders,
                                List<Quote> quotes,long rawDealRows,long rawOrderRows,long rawQuoteRows) {
        public ProductionDay { deals=List.copyOf(deals);orders=List.copyOf(orders);quotes=List.copyOf(quotes); }
    }
    private record OrderKey(String id,boolean buy) {}
    private record ColumnIndex(Map<String,Integer> index,int width) {}
    private DfcfCsvParser() {}

    public static long scanDeals(InputStream input,String symbol,LocalDate date,long maxBytes,RowSink<Deal> sink) throws IOException {
        return scanDeals(input,symbol,date,maxBytes,sink,false);
    }
    private static long scanDeals(InputStream input,String symbol,LocalDate date,long maxBytes,RowSink<Deal> sink,boolean production) throws IOException {
        String code=normalizeSymbol(symbol); return scan(input,DEAL,date,maxBytes,(row,ix) -> {
            Integer vendorTime=production?productionTime(row,ix):Integer.valueOf(sourceTime(row,ix));
            int time=vendorTime==null?0:vendorTime;String bs=field(row,ix,"BS标志").strip().toUpperCase(Locale.ROOT);
            BigDecimal rawPrice=production?numberOrZero(row,ix,"成交价格",true):decimal(row,ix,"成交价格");if(rawPrice==null) throw new IOException("Missing DFCF deal price");
            BigDecimal price=rawPrice.movePointLeft(4),volume=production?quantity(row,ix,"成交数量",true):BigDecimal.valueOf(integer(row,ix,"成交数量",true));
            String buy=production?productionIdentifier(row,ix,"叫买序号"):identifier(row,ix,"叫买序号"),
                    sell=production?productionIdentifier(row,ix,"叫卖序号"):identifier(row,ix,"叫卖序号");
            DealSide side;
            if(rawPrice.signum()>0 && bs.equals("B")) side=DealSide.BUY;
            else if(rawPrice.signum()>0 && bs.equals("S")) side=DealSide.SELL;
            // Python applies the sell cancellation assignment last when both IDs are positive.
            else if(rawPrice.signum()<=0 && positiveIdentifier(sell)) side=DealSide.SELL_CANCEL;
            else if(rawPrice.signum()<=0 && positiveIdentifier(buy)) side=DealSide.BUY_CANCEL;
            else side=DealSide.UNKNOWN;
            return new Deal(code,date,time,production?productionDealId(row,ix):identifier(row,ix,"成交编号"),bs,buy,sell,price,volume,side,vendorTime!=null);
        },sink);
    }

    public static long scanOrders(InputStream input,String symbol,LocalDate date,long maxBytes,RowSink<Order> sink) throws IOException {
        return scanOrders(input,symbol,date,maxBytes,sink,false);
    }
    private static long scanOrders(InputStream input,String symbol,LocalDate date,long maxBytes,RowSink<Order> sink,boolean production) throws IOException {
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
            BigDecimal raw=number(row,ix,"委托价格",production);if(raw==null&&!production) throw new IOException("Missing DFCF order price");
            Integer vendorTime=production?productionTime(row,ix):Integer.valueOf(sourceTime(row,ix));
            return new Order(code,date,vendorTime==null?0:vendorTime,production?productionIdentifier(row,ix,"交易所委托号"):identifier(row,ix,"交易所委托号"),vendorType,vendorCode,
                    raw==null?null:raw.movePointLeft(4),production?quantity(row,ix,"委托数量",true):BigDecimal.valueOf(integer(row,ix,"委托数量",true)),kuake,kind,submitted,vendorTime!=null);
        },sink);
    }

    public static long scanQuotes(InputStream input,String symbol,LocalDate date,long maxBytes,RowSink<Quote> sink) throws IOException {
        return scanQuotes(input,symbol,date,maxBytes,sink,false);
    }
    private static long scanQuotes(InputStream input,String symbol,LocalDate date,long maxBytes,RowSink<Quote> sink,boolean production) throws IOException {
        String code=normalizeSymbol(symbol);Integer[] previous={null};
        return scan(input,SNAPSHOT,date,maxBytes,(row,ix) -> {
            Integer vendorTime=production?productionTime(row,ix):Integer.valueOf(sourceTime(row,ix));
            int time=vendorTime==null?0:vendorTime,diff=previous[0]==null||vendorTime==null?0:Math.max(0,time-previous[0]);previous[0]=vendorTime;
            var levels=new ArrayList<BookLevel>(10);
            for(int i=1;i<=10;i++) levels.add(new BookLevel(price(row,ix,"申买价"+i,production),quantity(row,ix,"申买量"+i,production),
                    price(row,ix,"申卖价"+i,production),quantity(row,ix,"申卖量"+i,production)));
            return new Quote(code,date,time,diff,price(row,ix,"成交价",production),quantity(row,ix,"成交量",production),
                    numberOrZero(row,ix,"成交额",production),quantity(row,ix,"当日累计成交量",production),
                    numberOrZero(row,ix,"当日成交额",production),quantity(row,ix,"叫买总量",production),quantity(row,ix,"叫卖总量",production),
                    price(row,ix,"加权平均叫买价",production),price(row,ix,"加权平均叫卖价",production),levels);
        },sink);
    }

    /** Read one extracted symbol directory into the same production tables used by Python features. */
    public static ProductionDay readProductionDay(Path symbolDirectory,String symbol,LocalDate date,
                                                  long maxBytesPerFile) throws IOException {
        var deals=new ArrayList<Deal>();var orders=new ArrayList<Order>();var quotes=new ArrayList<Quote>();
        Path deal=symbolDirectory.resolve("逐笔成交.csv"),order=symbolDirectory.resolve("逐笔委托.csv"),
                quote=symbolDirectory.resolve("行情.csv");
        // Numeric coercion and fractional quantities follow pandas in production; structure, dates
        // and integral identifiers remain explicit gates. Raw scanners retain integral int64 quantities.
        if(Files.isRegularFile(deal)) scanDeals(Files.newInputStream(deal),symbol,date,maxBytesPerFile,deals::add,true);
        if(Files.isRegularFile(order)) scanOrders(Files.newInputStream(order),symbol,date,maxBytesPerFile,orders::add,true);
        if(Files.isRegularFile(quote)) scanQuotes(Files.newInputStream(quote),symbol,date,maxBytesPerFile,quotes::add,true);
        return normalizeProductionDay(symbol,date,deals,orders,quotes);
    }

    /**
     * Keep the raw scanners available to archive audit callers while sharing one production mapping.
     * SZ cancels come from DEAL; price is the latest earlier-or-equal positive submitted price for
     * the same order identifier and side. A missing match retains the source cancellation price.
     */
    public static ProductionDay normalizeProductionDay(String symbol,LocalDate date,List<Deal> rawDeals,
                                                       List<Order> rawOrders,List<Quote> rawQuotes) {
        String canonical=normalizeSymbol(symbol);
        var deals=new ArrayList<>(rawDeals.stream().filter(row->row.side()!=DealSide.UNKNOWN).toList());
        var orders=new ArrayList<>(rawOrders.stream().filter(row->row.kind()!=OrderKind.UNKNOWN).toList());
        var quotes=new ArrayList<>(rawQuotes);
        // The adapter only synthesizes cancellation rows when the raw ORDER table exists and has rows.
        if(canonical.endsWith(".SZ")&&!rawOrders.isEmpty()) {
            var submitted=new HashMap<OrderKey,NavigableMap<Integer,BigDecimal>>();
            for(Order row:orders) if(row.submission()&&row.sourceTimePresent()&&row.priceCny()!=null&&row.priceCny().signum()>0) {
                boolean buy=row.kind()==OrderKind.ADD_BUY;
                submitted.computeIfAbsent(new OrderKey(row.orderId(),buy),ignored->new TreeMap<>())
                        .put(row.time(),row.priceCny());
            }
            // Append source-order rows first: stable ties in the Python concatenation have this order.
            for(Deal row:deals) if(row.side()==DealSide.BUY_CANCEL||row.side()==DealSide.SELL_CANCEL) {
                boolean buy=row.side()==DealSide.BUY_CANCEL;
                String id=buy?row.buyOrderId():row.sellOrderId();BigDecimal price=row.priceCny();
                var history=submitted.get(new OrderKey(id,buy));
                var matched=history==null||!row.sourceTimePresent()?null:history.floorEntry(row.time());
                if(matched!=null)price=matched.getValue();
                orders.add(new Order(canonical,row.tradeDate(),row.time(),id,"DEAL_CANCEL",buy?"B":"S",
                        price,row.volume(),buy?-1:-11,buy?OrderKind.CANCEL_BUY:OrderKind.CANCEL_SELL,false,row.sourceTimePresent()));
            }
        }
        deals.sort(Comparator.comparingLong(row->timeSortKey(row.time())));
        orders.sort(Comparator.comparingLong(row->timeSortKey(row.time())));
        quotes.sort(Comparator.comparingLong(row->timeSortKey(row.time())));
        return new ProductionDay(canonical,date,deals,orders,quotes,rawDeals.size(),rawOrders.size(),rawQuotes.size());
    }

    /** Match pandas HHMMSSmmm normalization; invalid timestamps become null and sort last. */
    public static LocalDateTime timestamp(LocalDate date,int time) {
        int hour=time/10_000_000,minute=(time/100_000)%100,second=(time/1_000)%100,millis=time%1_000;
        if(time<0||hour>23||minute>59||second>61)return null;
        return date.atStartOfDay().plusHours(hour).plusMinutes(minute).plusSeconds(second).plusNanos(millis*1_000_000L);
    }
    private static long timeSortKey(int time) {
        int hour=time/10_000_000,minute=(time/100_000)%100,second=(time/1_000)%100,millis=time%1_000;
        if(time<0||hour>23||minute>59||second>61)return Long.MAX_VALUE;
        return ((hour*60L+minute)*60+second)*1_000+millis;
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
        try {
            if(text.length()!=8)throw new DateTimeException("format");
            int value=0;
            for(int i=0;i<8;i++) {
                char digit=text.charAt(i);if(digit<'0'||digit>'9')throw new DateTimeException("format");
                value=value*10+digit-'0';
            }
            return LocalDate.of(value/10_000,(value/100)%100,value%100);
        }
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
    private static long integer(List<String> row,ColumnIndex ix,String name,boolean required) throws IOException {
        String value=field(row,ix,name).strip();if(value.isEmpty()) { if(required) throw new IOException("Missing DFCF integer in "+name);return 0; }
        try { return new BigDecimal(value).longValueExact(); }
        catch(ArithmeticException|NumberFormatException error) { throw new IOException("Invalid DFCF integer in "+name,error); }
    }
    private static long integerOrZero(List<String> row,ColumnIndex ix,String name) throws IOException { return integer(row,ix,name,false); }
    private static BigDecimal number(List<String> row,ColumnIndex ix,String name,boolean coerce) throws IOException {
        if(!coerce)return decimal(row,ix,name);
        String text=field(row,ix,name).strip();if(text.isEmpty())return null;
        // Explicit source admission gate: pandas accepts infinities, but neither the numeric
        // feature contract nor formal storage admits them. They must never silently become zero.
        if(sourceInfinity(text))throw new IOException("Nonfinite DFCF numeric value is not admitted in "+name);
        try {var value=new BigDecimal(text);if(!Double.isFinite(value.doubleValue()))throw new IOException("Nonfinite DFCF numeric value is not admitted in "+name);return value;}
        catch(NumberFormatException error){return null;}
    }
    /** Same ASCII case-insensitive literals as the former (?i)[+-]?(inf|infinity) gate. */
    private static boolean sourceInfinity(String text) {
        int start=text.charAt(0)=='+'||text.charAt(0)=='-'?1:0,length=text.length()-start;
        if(length!=3&&length!=8)return false;
        if((text.charAt(start)|32)!='i'||(text.charAt(start+1)|32)!='n'||(text.charAt(start+2)|32)!='f')return false;
        return length==3||(text.charAt(start+3)|32)=='i'&&(text.charAt(start+4)|32)=='n'&&
                (text.charAt(start+5)|32)=='i'&&(text.charAt(start+6)|32)=='t'&&(text.charAt(start+7)|32)=='y';
    }
    private static BigDecimal numberOrZero(List<String> row,ColumnIndex ix,String name,boolean coerce) throws IOException {
        var value=number(row,ix,name,coerce);return value==null?BigDecimal.ZERO:value;
    }
    private static BigDecimal price(List<String> row,ColumnIndex ix,String name,boolean coerce) throws IOException {
        var value=number(row,ix,name,coerce);return value==null?null:value.movePointLeft(4);
    }
    private static BigDecimal quantity(List<String> row,ColumnIndex ix,String name,boolean coerce) throws IOException {
        if(!coerce)return BigDecimal.valueOf(integerOrZero(row,ix,name));
        return numberOrZero(row,ix,name,true);
    }
    private static String productionIdentifier(List<String> row,ColumnIndex ix,String name) throws IOException {
        var value=number(row,ix,name,true);if(value==null)return "0";
        try{return Long.toString(value.longValueExact());}
        catch(ArithmeticException error){throw new IOException("DFCF identifier must remain an integral 64-bit value: "+name,error);}
    }
    private static Integer productionTime(List<String> row,ColumnIndex ix) throws IOException {
        var value=number(row,ix,"时间",true);if(value==null)return null;
        try {int time=value.intValueExact();if(time<0||time>235959999)throw new ArithmeticException();return time;}
        catch(ArithmeticException error){throw new IOException("Invalid DFCF time",error);}
    }
    private static String productionDealId(List<String> row,ColumnIndex ix) {
        String value=field(row,ix,"成交编号").strip();return value.isEmpty()?"nan":value;
    }
    private static String identifier(List<String> row,ColumnIndex ix,String name) throws IOException {
        String value=field(row,ix,name).strip();if(value.isEmpty()) return "";
        try { return new BigDecimal(value).toBigIntegerExact().toString(); }
        catch(ArithmeticException|NumberFormatException error) { throw new IOException("Invalid DFCF identifier in "+name,error); }
    }
    private static boolean positiveIdentifier(String value) { try { return !value.isBlank()&&new BigInteger(value).signum()>0; } catch(NumberFormatException e) { return false; } }
    /** Canonical code-prefix exchange mapping shared with DfcfCsvLevel2Source, including mislabeled ETFs. */
    public static String normalizeSymbol(String symbol) {
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
