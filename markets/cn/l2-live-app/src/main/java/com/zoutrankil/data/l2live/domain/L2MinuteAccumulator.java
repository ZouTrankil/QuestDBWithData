package com.zoutrankil.data.l2live.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/** 有界、遵循因果顺序的分钟观察计算；报文重放身份由持久化日志管理，而非计算器。 */
public final class L2MinuteAccumulator {
    public static final String CHECKPOINT_VERSION="1";
    public static final Map<String,String> METRIC_TYPES=metricTypes();
    /** 容许已观测到的时钟偏差；这不构成来源数据满足时点可用性的证明。 */
    public static final Duration EVENT_CLOCK_TOLERANCE=Duration.ofSeconds(1);
    private static final int MAX_SYMBOLS=256,MAX_BOOK_HISTORY=128;
    private static final BigDecimal LARGE_AMOUNT=new BigDecimal("500000");
    private final Duration allowedLateness;private final int retainedMinutes;private final Clock clock;
    private final NavigableMap<String,SymbolState> symbols=new TreeMap<>();
    private final Map<String,Long> counters=new TreeMap<>();
    private record OrderKey(Instant eventTs,Instant recvTs,long sequence,String captureId)implements Comparable<OrderKey> {
        @Override public int compareTo(OrderKey other) {
            int c=eventTs.compareTo(other.eventTs);if(c==0)c=recvTs.compareTo(other.recvTs);
            if(c==0)c=Long.compare(sequence,other.sequence);return c==0?captureId.compareTo(other.captureId):c;
        }
    }
    private record Book(OrderKey order,long price,long[] bidPrices,long[] askPrices,long[] bidVolumes,long[] askVolumes){}
    private static final class SymbolState {
        LocalDate day;Instant maxEventTs,lastOutputMinute;
        final NavigableMap<Instant,Bucket> buckets=new TreeMap<>();
        final NavigableMap<Instant,Book> books=new TreeMap<>();
        SymbolState(LocalDate day){this.day=day;}
    }
    private static final class Bucket {
        final Instant minute;Instant lastRecv,lastRecvExchange;OrderKey firstPrice,lastPrice;Long open,high,low,close;
        long revision,eventCount,volume,trades,buyVolume,sellVolume,orderAdds,orderCancels,transCancels,lateEvents;
        BigDecimal amount=BigDecimal.ZERO,buyAmount=BigDecimal.ZERO,sellAmount=BigDecimal.ZERO;
        BigDecimal largeBuy=BigDecimal.ZERO,largeSell=BigDecimal.ZERO,bidAdd=BigDecimal.ZERO,askAdd=BigDecimal.ZERO;
        BigDecimal bidCancel=BigDecimal.ZERO,askCancel=BigDecimal.ZERO;
        Book book;final TreeSet<String> flags=new TreeSet<>();
        Bucket(Instant minute){this.minute=minute;}
        void price(long price,OrderKey key) {
            if(price<=0)return;
            if(firstPrice==null||key.compareTo(firstPrice)<0){firstPrice=key;open=price;}
            if(lastPrice==null||key.compareTo(lastPrice)>0){lastPrice=key;close=price;}
            high=high==null?price:Math.max(high,price);low=low==null?price:Math.min(low,price);
        }
    }
    public L2MinuteAccumulator(Duration allowedLateness,int retainedMinutes,Clock clock) {
        this.allowedLateness=Objects.requireNonNull(allowedLateness);this.clock=Objects.requireNonNull(clock);
        if(allowedLateness.isNegative()||allowedLateness.compareTo(Duration.ofMinutes(15))>0||retainedMinutes<2||retainedMinutes>600
                ||allowedLateness.compareTo(Duration.ofMinutes(retainedMinutes-1))>0)
            throw new IllegalArgumentException("Bounded lateness and minute retention required");
        this.retainedMinutes=retainedMinutes;
    }
    public synchronized Optional<L2MinuteState> accept(L2CleanEvent event) {
        Objects.requireNonNull(event);
        if(!event.isValid()){count("invalid_events");return Optional.empty();}
        Instant available=clock.instant();
        if(available.isBefore(event.capture().recvTs()))throw new IllegalStateException("Availability clock precedes original receive Instant");
        if(event.exchangeTs().isAfter(available.plus(EVENT_CLOCK_TOLERANCE))){count("future_event_time_rejected");return Optional.empty();}
        SymbolState state=symbols.get(event.symbol());
        if(state==null) {
            if(symbols.size()>=MAX_SYMBOLS){count("symbol_capacity_rejected");return Optional.empty();}
            state=new SymbolState(event.tradeDate());symbols.put(event.symbol(),state);
        }
        if(event.tradeDate().isBefore(state.day)){count("previous_day_rejected");return Optional.empty();}
        if(event.tradeDate().isAfter(state.day)) {
            state.day=event.tradeDate();state.buckets.clear();state.books.clear();state.maxEventTs=null;state.lastOutputMinute=null;count("daily_resets");
        }
        if(state.maxEventTs!=null&&event.exchangeTs().isBefore(state.maxEventTs.minus(allowedLateness))){count("beyond_lateness_rejected");return Optional.empty();}
        boolean late=state.maxEventTs!=null&&event.exchangeTs().isBefore(state.maxEventTs);
        if(state.maxEventTs==null||event.exchangeTs().isAfter(state.maxEventTs))state.maxEventTs=event.exchangeTs();
        Instant minute=event.exchangeTs().truncatedTo(ChronoUnit.MINUTES);
        Instant cutoff=state.maxEventTs.truncatedTo(ChronoUnit.MINUTES).minus(retainedMinutes-1,ChronoUnit.MINUTES);
        if(minute.isBefore(cutoff)){count("retention_rejected");return Optional.empty();}
        Bucket bucket=state.buckets.computeIfAbsent(minute,Bucket::new);bucket.eventCount=Math.incrementExact(bucket.eventCount);
        if(bucket.lastRecv==null||!event.capture().recvTs().isBefore(bucket.lastRecv)){bucket.lastRecv=event.capture().recvTs();bucket.lastRecvExchange=event.exchangeTs();}
        String quality=Objects.toString(event.fields().get("quality_flags"),"");if(!quality.isBlank())Collections.addAll(bucket.flags,quality.split("\\|"));
        if(late){bucket.lateEvents=Math.incrementExact(bucket.lateEvents);bucket.flags.add("out_of_order_received");count("accepted_late_events");}
        if(event.capture().duplicate())count("mqtt_duplicate_unverified");
        count("accepted_events");count("sequence_scope_unverified");
        OrderKey key=new OrderKey(event.exchangeTs(),event.capture().recvTs(),event.capture().sequence(),event.capture().captureId());
        Book causal=causalBook(state,bucket,event.exchangeTs());
        switch(event.channel()) {
            case "market"->{Book book=book(event,key);Book old=state.books.get(event.exchangeTs());if(old==null||key.compareTo(old.order)>0)state.books.put(event.exchangeTs(),book);bucket.price(book.price,key);}
            case "trans"->applyTrade(bucket,event,key,causal);
            case "order"->applyOrder(bucket,event,causal);
            default->throw new IllegalArgumentException("Unsupported clean channel");
        }
        var lastBook=state.books.floorEntry(minute.plus(1,ChronoUnit.MINUTES).minusNanos(1));
        if(lastBook!=null&&(bucket.book==null||lastBook.getValue().order.compareTo(bucket.book.order)>0))bucket.book=lastBook.getValue();
        evict(state,cutoff);
        // 被接纳的迟到数据可以修正保留历史，但不能让输出的最新分钟倒退。
        if(state.lastOutputMinute!=null&&minute.isBefore(state.lastOutputMinute)){count("older_minute_not_emitted");return Optional.empty();}
        state.lastOutputMinute=minute;bucket.revision=Math.incrementExact(bucket.revision);
        return Optional.of(new L2MinuteState(event.symbol(),state.day,minute,available,bucket.revision,metrics(state,bucket,available)));
    }
    public synchronized Map<String,Long> diagnosticCounters(){return Collections.unmodifiableMap(new TreeMap<>(counters));}
    private void count(String name){counters.merge(name,1L,Math::addExact);}
    private void evict(SymbolState state,Instant cutoff) {
        while(!state.buckets.isEmpty()&&state.buckets.firstKey().isBefore(cutoff)){state.buckets.pollFirstEntry();count("minute_buckets_evicted");}
        Instant bookCutoff=cutoff.minus(1,ChronoUnit.MINUTES);
        while(!state.books.isEmpty()&&(state.books.firstKey().isBefore(bookCutoff)||state.books.size()>MAX_BOOK_HISTORY)){state.books.pollFirstEntry();count("book_history_evicted");}
    }
    private static Book causalBook(SymbolState state,Bucket bucket,Instant eventTs) {
        var candidate=state.books.floorEntry(eventTs);
        if(candidate!=null)return candidate.getValue();
        return bucket.book!=null&&!bucket.book.order.eventTs.isAfter(eventTs)?bucket.book:null;
    }
    private void applyTrade(Bucket bucket,L2CleanEvent event,OrderKey key,Book causal) {
        long price=number(event,"price_scaled"),quantity=number(event,"volume");BigDecimal amount=money(price).multiply(BigDecimal.valueOf(quantity));
        if("trade".equals(event.fields().get("event_type"))) {
            bucket.price(price,key);bucket.volume=Math.addExact(bucket.volume,quantity);bucket.amount=bucket.amount.add(amount);bucket.trades=Math.incrementExact(bucket.trades);
            if("buy".equals(event.fields().get("aggressor_side"))){bucket.buyVolume=Math.addExact(bucket.buyVolume,quantity);bucket.buyAmount=bucket.buyAmount.add(amount);if(amount.compareTo(LARGE_AMOUNT)>=0)bucket.largeBuy=bucket.largeBuy.add(amount);}
            else if("sell".equals(event.fields().get("aggressor_side"))){bucket.sellVolume=Math.addExact(bucket.sellVolume,quantity);bucket.sellAmount=bucket.sellAmount.add(amount);if(amount.compareTo(LARGE_AMOUNT)>=0)bucket.largeSell=bucket.largeSell.add(amount);}
        } else {
            bucket.transCancels=Math.incrementExact(bucket.transCancels);
            boolean buy=nonzeroId(event.fields().get("buy_order_id")),sell=nonzeroId(event.fields().get("sell_order_id"));
            if(buy==sell){bucket.flags.add("cancel_side_unverified");return;}
            if(price==0){bucket.flags.add("unpriced_cancel");return;}
            if(near(bucket,causal,buy,price)) {if(buy)bucket.bidCancel=bucket.bidCancel.add(amount);else bucket.askCancel=bucket.askCancel.add(amount);}
        }
    }
    private void applyOrder(Bucket bucket,L2CleanEvent event,Book causal) {
        long price=number(event,"price_scaled"),quantity=number(event,"volume");
        boolean buy="B".equals(event.fields().get("order_side")),cancel="cancel".equals(event.fields().get("event_type"));
        if(cancel)bucket.orderCancels=Math.incrementExact(bucket.orderCancels);else bucket.orderAdds=Math.incrementExact(bucket.orderAdds);
        if(price==0){bucket.flags.add("unpriced_order");return;}
        if(!near(bucket,causal,buy,price))return;
        BigDecimal amount=money(price).multiply(BigDecimal.valueOf(quantity));
        if(cancel){if(buy)bucket.bidCancel=bucket.bidCancel.add(amount);else bucket.askCancel=bucket.askCancel.add(amount);}
        else {if(buy)bucket.bidAdd=bucket.bidAdd.add(amount);else bucket.askAdd=bucket.askAdd.add(amount);}
    }
    private boolean near(Bucket bucket,Book book,boolean buy,long price) {
        long reference=book==null?0:buy?book.bidPrices[0]:book.askPrices[0];
        if(reference<=0){bucket.flags.add("missing_causal_book");count("missing_causal_book");return false;}
        BigDecimal threshold=BigDecimal.valueOf(reference).multiply(new BigDecimal(buy?"0.9995":"1.0005"));
        return buy?BigDecimal.valueOf(price).compareTo(threshold)>=0:BigDecimal.valueOf(price).compareTo(threshold)<=0;
    }
    private static boolean nonzeroId(Object value){return value instanceof String s&&!s.isBlank()&&!s.matches("0+");}
    private static long number(L2CleanEvent event,String field){Object value=event.fields().get(field);if(!(value instanceof Long n)||n<0)throw new IllegalArgumentException("Typed nonnegative clean field required: "+field);return n;}
    private static Book book(L2CleanEvent event,OrderKey key) {
        long[] bp=new long[10],ap=new long[10],bv=new long[10],av=new long[10];
        for(int i=0;i<10;i++){bp[i]=number(event,"bid_price_scaled_"+(i+1));ap[i]=number(event,"ask_price_scaled_"+(i+1));bv[i]=number(event,"bid_volume_"+(i+1));av[i]=number(event,"ask_volume_"+(i+1));}
        return new Book(key,number(event,"price_scaled"),bp,ap,bv,av);
    }
    private static Map<String,Object> metrics(SymbolState state,Bucket b,Instant available) {
        var out=new LinkedHashMap<String,Object>();METRIC_TYPES.keySet().forEach(k->out.put(k,null));Book book=b.book;
        out.put("partial",true);out.put("sequence_scope_unverified",true);out.put("event_count",b.eventCount);out.put("accepted_late_event_count",b.lateEvents);
        out.put("trade_count_1m",b.trades);out.put("order_add_count_1m",b.orderAdds);out.put("order_cancel_count_1m",b.orderCancels);out.put("trans_cancel_count_1m",b.transCancels);
        out.put("open_1m",moneyNullable(b.open));out.put("high_1m",moneyNullable(b.high));out.put("low_1m",moneyNullable(b.low));out.put("close_1m",moneyNullable(b.close));out.put("last_price",moneyNullable(b.close));
        out.put("volume_1m",b.volume);out.put("amount_1m",b.amount);out.put("vwap_1m",b.volume>0?ratio(b.amount,BigDecimal.valueOf(b.volume)):null);
        out.put("active_buy_volume_1m",b.buyVolume);out.put("active_sell_volume_1m",b.sellVolume);
        out.put("active_buy_amount_1m",b.buyAmount);out.put("active_sell_amount_1m",b.sellAmount);
        BigDecimal active=b.buyAmount.add(b.sellAmount);out.put("active_buy_ratio",ratio(b.buyAmount,active));out.put("active_sell_ratio",ratio(b.sellAmount,active));
        out.put("large_buy_amount_1m",b.largeBuy);out.put("large_sell_amount_1m",b.largeSell);out.put("large_trade_imbalance",ratio(b.largeBuy.subtract(b.largeSell),b.largeBuy.add(b.largeSell)));
        out.put("near_bid_add_amount",b.bidAdd);out.put("near_ask_add_amount",b.askAdd);out.put("near_bid_cancel_amount",b.bidCancel);out.put("near_ask_cancel_amount",b.askCancel);
        out.put("bid_cancel_add_ratio",ratio(b.bidCancel,b.bidAdd));out.put("ask_cancel_add_ratio",ratio(b.askCancel,b.askAdd));
        if(b.open!=null&&b.close!=null)out.put("price_impact_bps",ratio(BigDecimal.valueOf(b.close-b.open).multiply(BigDecimal.valueOf(10000)),BigDecimal.valueOf(b.open)));
        if(b.lastRecv!=null&&b.lastRecvExchange!=null)out.put("data_delay_ms",millis(Duration.between(b.lastRecvExchange,b.lastRecv)));
        for(int minutes:new int[]{1,3,5}){Bucket previous=state.buckets.get(b.minute.minus(minutes,ChronoUnit.MINUTES));if(previous!=null&&previous.close!=null&&b.close!=null)out.put("ret_"+minutes+"m",(double)b.close/previous.close-1.0);}
        var prior=state.buckets.headMap(b.minute,false).values().stream().map(v->v.amount.doubleValue()).toList();
        if(!prior.isEmpty()){double mean=prior.subList(Math.max(0,prior.size()-5),prior.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);if(mean>0)out.put("vol_ratio_1m",b.amount.doubleValue()/mean);}
        if(prior.size()>=3){var recent=prior.subList(Math.max(0,prior.size()-10),prior.size());double mean=recent.stream().mapToDouble(Double::doubleValue).average().orElseThrow();double std=Math.sqrt(recent.stream().mapToDouble(v->(v-mean)*(v-mean)).average().orElseThrow());out.put("trade_intensity_zscore",std>1e-9?(b.amount.doubleValue()-mean)/std:0.0);}
        if(b.volume>0&&b.close!=null){double vwap=b.amount.doubleValue()/b.volume;if(vwap>0)out.put("vwap_gap_bps",(money(b.close).doubleValue()-vwap)/vwap*10000);}
        if(book!=null) {
            out.put("book_snapshot_ts",book.order.eventTs.toString());out.put("snapshot_age_ms",millis(Duration.between(book.order.eventTs,available)));
            for(int i=0;i<10;i++){out.put("bid_price_"+(i+1),money(book.bidPrices[i]));out.put("ask_price_"+(i+1),money(book.askPrices[i]));out.put("bid_vol_"+(i+1),book.bidVolumes[i]);out.put("ask_vol_"+(i+1),book.askVolumes[i]);}
            long bid1=sum(book.bidVolumes,1),ask1=sum(book.askVolumes,1),bid5=sum(book.bidVolumes,5),ask5=sum(book.askVolumes,5),bid10=sum(book.bidVolumes,10),ask10=sum(book.askVolumes,10);
            for(int n:new int[]{1,5,10}){long bid=n==1?bid1:n==5?bid5:bid10,ask=n==1?ask1:n==5?ask5:ask10;out.put("depth_bid_top"+n,bid);out.put("depth_ask_top"+n,ask);out.put("obi_top"+n,ratio(BigDecimal.valueOf(bid).subtract(BigDecimal.valueOf(ask)),BigDecimal.valueOf(bid).add(BigDecimal.valueOf(ask))));}
            if(book.bidPrices[0]>0&&book.askPrices[0]>0){BigDecimal bid=money(book.bidPrices[0]),ask=money(book.askPrices[0]),mid=bid.add(ask).divide(BigDecimal.valueOf(2));out.put("mid_price",mid);out.put("spread_bps",ratio(ask.subtract(bid).multiply(BigDecimal.valueOf(10000)),mid));
                if(bid1>0||ask1>0){double micro=(ask.doubleValue()*bid1+bid.doubleValue()*ask1)/((double)bid1+ask1);out.put("microprice",micro);out.put("microprice_gap_bps",(micro-mid.doubleValue())/mid.doubleValue()*10000);}}
            if(bid10>0)out.put("bid_wall_score",(double)max(book.bidVolumes)/bid10);if(ask10>0)out.put("ask_wall_score",(double)max(book.askVolumes)/ask10);
            Bucket previous=state.buckets.get(b.minute.minus(1,ChronoUnit.MINUTES));
            if(previous!=null&&previous.book!=null){double old=(double)sum(previous.book.bidVolumes,10)+sum(previous.book.askVolumes,10),now=(double)bid10+ask10;if(old>0){out.put("depth_depletion_score",clip((old-now)/old));out.put("depth_recovery_score",clip((now-old)/old));}}
            double depth=(double)bid10+ask10;if(depth>0){Double bidRatio=ratio(b.bidCancel,b.bidAdd),askRatio=ratio(b.askCancel,b.askAdd);if(bidRatio!=null)out.put("fake_bid_support_score",clip(bidRatio*bid5/depth));if(askRatio!=null)out.put("fake_ask_pressure_score",clip(askRatio*ask5/depth));}
            Object depletion=out.get("depth_depletion_score");Double buyRatio=ratio(b.buyAmount,active),sellRatio=ratio(b.sellAmount,active);
            if(depletion instanceof Double d){if(sellRatio!=null)out.put("queue_consume_bid_score",clip(d*sellRatio));if(buyRatio!=null)out.put("queue_consume_ask_score",clip(d*buyRatio));}
        }
        var flags=new TreeSet<>(b.flags);if(book==null)flags.add("missing_book_snapshot");if(b.orderCancels>0&&b.transCancels>0)flags.add("combined_cancel_scope_unverified");
        out.put("quality_flags",String.join("|",flags));return out;
    }
    private static long sum(long[] values,int n){long total=0;for(int i=0;i<n;i++)total=Math.addExact(total,values[i]);return total;}
    private static long max(long[] values){long maximum=0;for(long value:values)maximum=Math.max(maximum,value);return maximum;}
    private static BigDecimal money(long scaled){return BigDecimal.valueOf(scaled,4);}
    private static BigDecimal moneyNullable(Long scaled){return scaled==null?null:money(scaled);}
    private static Double ratio(BigDecimal numerator,BigDecimal denominator){return denominator.signum()==0?null:numerator.doubleValue()/denominator.doubleValue();}
    private static double clip(double value){return Math.max(0,Math.min(1,value));}
    private static double millis(Duration duration){return duration.getSeconds()*1000.0+duration.getNano()/1_000_000.0;}

    /** 返回存储无关的深拷贝；十进制累计状态保留字符串精度，Parquet 负责类型化编码。 */
    public synchronized Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();result.put("checkpoint_version",CHECKPOINT_VERSION);result.put("parser_version",AliyunL2WireParser.VERSION);
        result.put("allowed_lateness_nanos",allowedLateness.toNanos());result.put("retained_minutes",retainedMinutes);result.put("diagnostics",new TreeMap<>(counters));
        var states=new ArrayList<Map<String,Object>>();
        for(var entry:symbols.entrySet()){var state=entry.getValue();var data=new LinkedHashMap<String,Object>();data.put("symbol",entry.getKey());data.put("current_day",state.day.toString());data.put("max_event_ts",iso(state.maxEventTs));data.put("last_output_minute",iso(state.lastOutputMinute));data.put("book_history",state.books.values().stream().map(L2MinuteAccumulator::saveBook).toList());data.put("buckets",state.buckets.values().stream().map(L2MinuteAccumulator::saveBucket).toList());states.add(data);}
        result.put("symbols",states);return freezeMap(result);
    }
    /** 先校验配置、计数、身份和全部类型化标量状态，再原子恢复。 */
    public synchronized void restore(Map<String,Object> checkpoint) {
        if(!CHECKPOINT_VERSION.equals(checkpoint.get("checkpoint_version"))||!AliyunL2WireParser.VERSION.equals(checkpoint.get("parser_version"))
                ||exactLong(checkpoint.get("allowed_lateness_nanos"))!=allowedLateness.toNanos()||exactLong(checkpoint.get("retained_minutes"))!=retainedMinutes)
            throw new IllegalArgumentException("Checkpoint version/configuration differs");
        var restored=new TreeMap<String,SymbolState>();List<?> entries=list(checkpoint.get("symbols"),MAX_SYMBOLS);
        for(Object raw:entries){Map<String,Object> data=map(raw);String symbol=text(data.get("symbol"));if(!symbol.equals(AliyunL2WireParser.normalizeSymbol(symbol))||restored.containsKey(symbol))throw new IllegalArgumentException("Unique canonical checkpoint symbol required");
            var state=new SymbolState(LocalDate.parse(text(data.get("current_day"))));state.maxEventTs=instant(data.get("max_event_ts"));state.lastOutputMinute=instant(data.get("last_output_minute"));
            for(Object rawBook:list(data.get("book_history"),MAX_BOOK_HISTORY)){Book book=loadBook(map(rawBook));if(!book.order.eventTs.atZone(RawCapture.SHANGHAI).toLocalDate().equals(state.day)||state.books.put(book.order.eventTs,book)!=null)throw new IllegalArgumentException("Unique current-day book required");}
            for(Object rawBucket:list(data.get("buckets"),retainedMinutes)){Bucket bucket=loadBucket(map(rawBucket));if(!bucket.minute.equals(bucket.minute.truncatedTo(ChronoUnit.MINUTES))||!bucket.minute.atZone(RawCapture.SHANGHAI).toLocalDate().equals(state.day)||state.buckets.put(bucket.minute,bucket)!=null)throw new IllegalArgumentException("Unique current-day minute required");}
            if(state.maxEventTs==null&&!state.buckets.isEmpty()||state.lastOutputMinute!=null&&!state.buckets.containsKey(state.lastOutputMinute))throw new IllegalArgumentException("Checkpoint watermark/output minute missing");
            if(state.maxEventTs!=null){Instant cutoff=state.maxEventTs.truncatedTo(ChronoUnit.MINUTES).minus(retainedMinutes-1,ChronoUnit.MINUTES);if(state.buckets.keySet().stream().anyMatch(t->t.isBefore(cutoff)||t.isAfter(state.maxEventTs)))throw new IllegalArgumentException("Checkpoint buckets exceed retained event frontier");}
            restored.put(symbol,state);
        }
        var restoredCounters=new TreeMap<String,Long>();Map<String,Object> diagnostic=map(checkpoint.get("diagnostics"));if(diagnostic.size()>32)throw new IllegalArgumentException("Bounded diagnostic counters required");
        diagnostic.forEach((key,value)->{if(!key.matches("[a-z_]{1,64}"))throw new IllegalArgumentException("Invalid diagnostic key");restoredCounters.put(key,nonnegative(value));});
        symbols.clear();symbols.putAll(restored);counters.clear();counters.putAll(restoredCounters);
    }
    private static Map<String,Object> saveBucket(Bucket b) {
        var d=new LinkedHashMap<String,Object>();d.put("minute",b.minute.toString());d.put("last_recv",iso(b.lastRecv));d.put("last_recv_exchange",iso(b.lastRecvExchange));d.put("first_price",saveOrder(b.firstPrice));d.put("last_price",saveOrder(b.lastPrice));
        d.put("open",b.open);d.put("high",b.high);d.put("low",b.low);d.put("close",b.close);
        String[] counts={"revision","event_count","volume","trades","buy_volume","sell_volume","order_adds","order_cancels","trans_cancels","late_events"};
        long[] numbers={b.revision,b.eventCount,b.volume,b.trades,b.buyVolume,b.sellVolume,b.orderAdds,b.orderCancels,b.transCancels,b.lateEvents};for(int i=0;i<counts.length;i++)d.put(counts[i],numbers[i]);
        String[] names={"amount","buy_amount","sell_amount","large_buy","large_sell","bid_add","ask_add","bid_cancel","ask_cancel"};
        BigDecimal[] values={b.amount,b.buyAmount,b.sellAmount,b.largeBuy,b.largeSell,b.bidAdd,b.askAdd,b.bidCancel,b.askCancel};for(int i=0;i<names.length;i++)d.put(names[i],values[i].toPlainString());
        d.put("book",b.book==null?null:saveBook(b.book));d.put("quality_flags",new ArrayList<>(b.flags));return d;
    }
    private static Bucket loadBucket(Map<String,Object> d) {
        var b=new Bucket(Instant.parse(text(d.get("minute"))));b.lastRecv=instant(d.get("last_recv"));b.lastRecvExchange=instant(d.get("last_recv_exchange"));b.firstPrice=loadOrderNullable(d.get("first_price"));b.lastPrice=loadOrderNullable(d.get("last_price"));
        b.open=nullableLong(d.get("open"));b.high=nullableLong(d.get("high"));b.low=nullableLong(d.get("low"));b.close=nullableLong(d.get("close"));
        b.revision=nonnegative(d.get("revision"));b.eventCount=nonnegative(d.get("event_count"));b.volume=nonnegative(d.get("volume"));b.trades=nonnegative(d.get("trades"));b.buyVolume=nonnegative(d.get("buy_volume"));b.sellVolume=nonnegative(d.get("sell_volume"));b.orderAdds=nonnegative(d.get("order_adds"));b.orderCancels=nonnegative(d.get("order_cancels"));b.transCancels=nonnegative(d.get("trans_cancels"));b.lateEvents=nonnegative(d.get("late_events"));
        b.amount=decimal(d.get("amount"));b.buyAmount=decimal(d.get("buy_amount"));b.sellAmount=decimal(d.get("sell_amount"));b.largeBuy=decimal(d.get("large_buy"));b.largeSell=decimal(d.get("large_sell"));b.bidAdd=decimal(d.get("bid_add"));b.askAdd=decimal(d.get("ask_add"));b.bidCancel=decimal(d.get("bid_cancel"));b.askCancel=decimal(d.get("ask_cancel"));
        b.book=d.get("book")==null?null:loadBook(map(d.get("book")));for(Object flag:list(d.get("quality_flags"),32)){String value=text(flag);if(!value.matches("[a-z_]{1,64}"))throw new IllegalArgumentException("Invalid quality flag");b.flags.add(value);}return b;
    }
    private static Map<String,Object> saveBook(Book b){var d=new LinkedHashMap<String,Object>();d.put("order",saveOrder(b.order));d.put("price",b.price);d.put("bid_prices",array(b.bidPrices));d.put("ask_prices",array(b.askPrices));d.put("bid_volumes",array(b.bidVolumes));d.put("ask_volumes",array(b.askVolumes));return d;}
    private static Book loadBook(Map<String,Object> d){return new Book(loadOrderNullable(d.get("order")),nonnegative(d.get("price")),loadArray(d.get("bid_prices")),loadArray(d.get("ask_prices")),loadArray(d.get("bid_volumes")),loadArray(d.get("ask_volumes")));}
    private static List<Long> array(long[] values){var out=new ArrayList<Long>();for(long value:values)out.add(value);return out;}
    private static long[] loadArray(Object raw){List<?> entries=list(raw,10);if(entries.size()!=10)throw new IllegalArgumentException("Ten checkpoint book levels required");long[] values=new long[10];for(int i=0;i<10;i++)values[i]=nonnegative(entries.get(i));return values;}
    private static Map<String,Object> saveOrder(OrderKey key){if(key==null)return null;return Map.of("event_ts",key.eventTs.toString(),"recv_ts",key.recvTs.toString(),"sequence",key.sequence,"capture_id",key.captureId);}
    private static OrderKey loadOrderNullable(Object raw){if(raw==null)return null;var d=map(raw);return new OrderKey(Instant.parse(text(d.get("event_ts"))),Instant.parse(text(d.get("recv_ts"))),nonnegative(d.get("sequence")),text(d.get("capture_id")));}
    private static String iso(Instant value){return value==null?null:value.toString();}
    private static Instant instant(Object value){return value==null?null:Instant.parse(text(value));}
    private static String text(Object value){if(!(value instanceof String s)||s.isBlank()||s.length()>256)throw new IllegalArgumentException("Bounded checkpoint text required");return s;}
    private static BigDecimal decimal(Object value){BigDecimal amount=new BigDecimal(text(value));if(amount.signum()<0||amount.precision()>64)throw new IllegalArgumentException("Bounded nonnegative decimal accumulator required");return amount;}
    private static Long nullableLong(Object value){return value==null?null:nonnegative(value);}
    private static long nonnegative(Object value){long number=exactLong(value);if(number<0)throw new IllegalArgumentException("Nonnegative checkpoint integer required");return number;}
    private static long exactLong(Object value){if(!(value instanceof Number number))throw new IllegalArgumentException("Checkpoint integer required");try{return new BigDecimal(number.toString()).longValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Exact checkpoint LONG required",e);}}
    private static List<?> list(Object value,int maximum){if(!(value instanceof List<?> values)||values.size()>maximum)throw new IllegalArgumentException("Bounded checkpoint array required");return values;}
    private static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?> values))throw new IllegalArgumentException("Checkpoint object required");var out=new LinkedHashMap<String,Object>();values.forEach((key,item)->{if(!(key instanceof String s))throw new IllegalArgumentException("Checkpoint string keys required");out.put(s,item);});return out;}
    private static Map<String,Object> freezeMap(Map<String,Object> values){var out=new LinkedHashMap<String,Object>();values.forEach((key,value)->out.put(key,freeze(value)));return Collections.unmodifiableMap(out);}
    private static Object freeze(Object value){if(value instanceof Map<?,?>)return freezeMap(map(value));if(value instanceof List<?> values)return Collections.unmodifiableList(values.stream().map(L2MinuteAccumulator::freeze).toList());return value;}
    private static Map<String,String> metricTypes() {
        var t=new LinkedHashMap<String,String>();
        for(String name:new String[]{"last_price","mid_price","open_1m","high_1m","low_1m","close_1m","ret_1m","ret_3m","ret_5m","vwap_1m","vwap_gap_bps","amount_1m","data_delay_ms","snapshot_age_ms","spread_bps","microprice","microprice_gap_bps","bid_wall_score","ask_wall_score","depth_depletion_score","depth_recovery_score","active_buy_amount_1m","active_sell_amount_1m","active_buy_ratio","active_sell_ratio","large_buy_amount_1m","large_sell_amount_1m","large_trade_imbalance","price_impact_bps","trade_intensity_zscore","vol_ratio_1m","near_bid_add_amount","near_ask_add_amount","near_bid_cancel_amount","near_ask_cancel_amount","bid_cancel_add_ratio","ask_cancel_add_ratio","fake_bid_support_score","fake_ask_pressure_score","queue_consume_bid_score","queue_consume_ask_score"})t.put(name,"DOUBLE");
        for(String name:new String[]{"volume_1m","trade_count_1m","event_count","accepted_late_event_count","order_add_count_1m","order_cancel_count_1m","trans_cancel_count_1m","active_buy_volume_1m","active_sell_volume_1m"})t.put(name,"LONG");
        for(int i=1;i<=10;i++)for(String side:new String[]{"bid","ask"}){t.put(side+"_price_"+i,"DOUBLE");t.put(side+"_vol_"+i,"LONG");}
        for(int n:new int[]{1,5,10}){t.put("depth_bid_top"+n,"LONG");t.put("depth_ask_top"+n,"LONG");t.put("obi_top"+n,"DOUBLE");}
        t.put("quality_flags","STRING");t.put("book_snapshot_ts","STRING");t.put("sequence_scope_unverified","BOOLEAN");t.put("partial","BOOLEAN");return Collections.unmodifiableMap(t);
    }
}
