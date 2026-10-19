package com.zoutrankil.data.l2live.domain;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 无副作用、带版本的报文解码器；不根据订阅证券推测供应商序列号的有效范围。 */
public final class AliyunL2WireParser {
    public static final String VERSION="v2";
    /** 原始采集允许保留更大报文；可进入供应商文本解析的大小另行设限。 */
    public static final int MAX_WIRE_PAYLOAD_BYTES=64*1024;
    public static final Map<String,String> FIELD_TYPES=fieldTypes();
    private static final Set<String> CHANNELS=Set.of("trans","order","market");
    private static final Set<String> BOND_PREFIXES=Set.of("110","111","113","118","123","127","128");
    private static final class Rejected extends RuntimeException {
        final String status;Rejected(String status,String message){super(message);this.status=status;}
    }
    public L2CleanEvent parse(RawCapture capture) {
        var values=new LinkedHashMap<String,Object>();FIELD_TYPES.keySet().forEach(k->values.put(k,null));
        values.put("parser_version",VERSION);values.put("sequence_scope_unverified",true);
        String symbol="",exchange="",channel="";LocalDate date=capture.captureDate();
        try {
            byte[] payload=capture.payloadBytes();
            if(payload.length>MAX_WIRE_PAYLOAD_BYTES)reject("oversized_wire_payload","Wire payload exceeds 64 KiB; original bytes remain retained in the raw journal");
            String[] topic=capture.topic().split("/",-1);
            if(topic.length!=2||!CHANNELS.contains(topic[0])||!topic[1].matches("[0-9]{6}"))reject("invalid_topic","Expected channel/six-digit-code");
            channel=topic[0];symbol=normalizeSymbol(topic[1]);exchange=symbol.substring(7);
            String wire=decode(payload);String[] fields=wire.strip().split(",",-1);
            for(int i=0;i<fields.length;i++){fields[i]=fields[i].strip();if(fields[i].indexOf('"')>=0||fields[i].indexOf('\n')>=0||fields[i].indexOf('\r')>=0)reject("invalid_payload","Quoted or multiline wire fields are unsupported");}
            int required=channel.equals("trans")?8:channel.equals("order")?9:56;
            values.put("wire_field_count",fields.length);
            if(fields.length!=required)reject("bad_field_count","Expected "+required+" fields, received "+fields.length);
            if(!normalizeSymbol(fields[0]).equals(symbol))reject("symbol_mismatch","Payload symbol differs from subscribed topic");
            boolean inferred=!channel.equals("order");values.put("wire_date_inferred",inferred);
            if(!inferred)date=wireDate(fields[1]);
            if(date.isAfter(capture.captureDate()))reject("invalid_date","Exchange date is after the receive date");
            Instant eventTime=eventTime(date,fields[channel.equals("market")?1:2]);
            var quality=new TreeSet<String>();quality.add("sequence_scope_unverified");
            if(inferred)quality.add("wire_date_inferred");
            if(capture.duplicate())quality.add("mqtt_duplicate_unverified");
            if(eventTime.isAfter(capture.recvTs()))quality.add("exchange_clock_ahead_of_receive");
            values.put("session_phase",sessionPhase(eventTime));
            switch(channel) {
                case "trans"->trade(fields,values,quality);
                case "order"->order(fields,values,exchange,quality);
                case "market"->market(fields,values,quality);
                default->throw new AssertionError();
            }
            values.put("quality_flags",String.join("|",quality));
            return new L2CleanEvent(capture,symbol,exchange,channel,date,eventTime,"ok","",values);
        } catch(Rejected e) {
            return new L2CleanEvent(capture,symbol,exchange,channel,date,null,e.status,e.getMessage(),values);
        } catch(CharacterCodingException e) {
            return new L2CleanEvent(capture,symbol,exchange,channel,date,null,"invalid_utf8","Payload is not strict UTF-8",values);
        }
    }
    public static String normalizeSymbol(String input) {
        String text=input.strip().toUpperCase(Locale.ROOT);
        if(!text.matches("[0-9]{6}(\\.(SH|SZ))?"))reject("invalid_symbol","Canonical SH/SZ symbol required");
        String code=text.substring(0,6);
        if(BOND_PREFIXES.contains(code.substring(0,3))||code.startsWith("920")||code.startsWith("4")||code.startsWith("8"))
            reject("unsupported_exchange","Only supported non-bond Shanghai/Shenzhen securities are admitted");
        String exchange=code.startsWith("5")||code.startsWith("6")||code.startsWith("9")?"SH":
                code.startsWith("00")||code.startsWith("12")||code.startsWith("15")||code.startsWith("16")||code.startsWith("30")?"SZ":"";
        if(exchange.isEmpty()||text.length()>6&&!text.substring(7).equals(exchange))reject("invalid_symbol","Security suffix differs from canonical market");
        return code+"."+exchange;
    }
    private static void trade(String[] f,Map<String,Object> out,Set<String> flags) {
        String side=f[1].toUpperCase(Locale.ROOT);
        if(!Set.of("B","S","C").contains(side))reject("invalid_enum","Unknown trade side");
        out.put("bs_flag",side);out.put("event_type",side.equals("C")?"cancel":"trade");
        out.put("aggressor_side",side.equals("B")?"buy":side.equals("S")?"sell":"cancel");
        out.put("sub_seq",integer(f[3],"sub_seq"));price(out,"price",f[4]);
        long quantity=integer(f[5],"volume");out.put("volume",quantity);
        if(!side.equals("C")&&(quantity==0||((Long)out.get("price_scaled"))==0))reject("invalid_numeric","Trade requires positive price and size");
        out.put("sell_order_id",identifier(f[6],"sell_order_id"));out.put("buy_order_id",identifier(f[7],"buy_order_id"));
        out.put("amount",((BigDecimal)out.get("price")).multiply(BigDecimal.valueOf(quantity)));
        if(side.equals("C")&&((Long)out.get("price_scaled"))==0)flags.add("unpriced_cancel");
    }
    private static void order(String[] f,Map<String,Object> out,String exchange,Set<String> flags) {
        out.put("channel_no",identifier(f[3],"channel_no"));out.put("order_id",identifier(f[4],"order_id"));
        String type=f[5].toUpperCase(Locale.ROOT),side=f[6].toUpperCase(Locale.ROOT);
        if(!Set.of("B","S").contains(side)||!(exchange.equals("SH")?Set.of("A","D"):Set.of("0","1","U")).contains(type))
            reject("invalid_enum","Unknown exchange order type or side");
        out.put("order_type",type);out.put("order_side",side);out.put("event_type",exchange.equals("SH")&&type.equals("D")?"cancel":"add");
        int code=exchange.equals("SH")?(type.equals("D")?-1:0):(type.equals("0")?1:type.equals("1")?2:3);
        if(side.equals("S"))code+=code<0?-10:10;out.put("kuake_order_type",code);
        out.put("volume_semantics",exchange.equals("SH")?"remaining_volume_after_match":"original_order_volume");
        price(out,"price",f[7]);long quantity=integer(f[8],"volume");out.put("volume",quantity);
        out.put("amount",((BigDecimal)out.get("price")).multiply(BigDecimal.valueOf(quantity)));
        if(((Long)out.get("price_scaled"))==0)flags.add("unpriced_order");
    }
    private static void market(String[] f,Map<String,Object> out,Set<String> flags) {
        out.put("event_type","snapshot");price(out,"price",f[2]);
        out.put("total_volume",integer(f[3],"total_volume"));out.put("total_amount",integer(f[4],"total_amount"));
        out.put("trade_count",integer(f[5],"trade_count"));
        for(int i=0;i<4;i++)price(out,new String[]{"high","low","open","pre_close"}[i],f[6+i]);
        for(int i=1;i<=10;i++) {
            levelPrice(out,"ask",i,f[9+i]);out.put("ask_volume_"+i,integer(f[19+i],"ask_volume_"+i));
            levelPrice(out,"bid",i,f[29+i]);out.put("bid_volume_"+i,integer(f[39+i],"bid_volume_"+i));
        }
        validateDepthSums(out,"bid");validateDepthSums(out,"ask");
        price(out,"weighted_ask_price",f[50]);price(out,"weighted_bid_price",f[51]);
        out.put("total_ask_volume",integer(f[52],"total_ask_volume"));out.put("total_bid_volume",integer(f[53],"total_bid_volume"));
        price(out,"limit_up",f[54]);price(out,"limit_down",f[55]);
        long bid=(Long)out.get("bid_price_scaled_1"),ask=(Long)out.get("ask_price_scaled_1");
        if(bid>0&&ask>0) {
            var b=(BigDecimal)out.get("bid_price_1");var a=(BigDecimal)out.get("ask_price_1");
            out.put("spread",a.subtract(b));out.put("mid_price",a.add(b).divide(BigDecimal.valueOf(2)));
            if(bid>ask)flags.add("crossed_book");
        } else flags.add("incomplete_top_of_book");
        long bv=(Long)out.get("bid_volume_1"),av=(Long)out.get("ask_volume_1");
        try {long depth=Math.addExact(bv,av);out.put("depth_top1",depth);if(depth>0)out.put("obi_top1",((double)bv-av)/depth);}
        catch(ArithmeticException overflow){reject("invalid_numeric","Top-of-book size sum exceeds LONG");}
    }
    private static void validateDepthSums(Map<String,Object> out,String side) {
        long cumulative=0;
        for(int i=1;i<=10;i++) {
            try {cumulative=Math.addExact(cumulative,(Long)out.get(side+"_volume_"+i));}
            catch(ArithmeticException overflow){reject("invalid_numeric",side+" top"+(i<=5?5:10)+" size sum exceeds LONG");}
        }
    }
    private static void levelPrice(Map<String,Object> out,String side,int level,String raw) {
        long value=integer(raw,side+"_price_"+level);out.put(side+"_price_scaled_"+level,value);
        out.put(side+"_price_"+level,BigDecimal.valueOf(value,4));
    }
    private static void price(Map<String,Object> out,String name,String raw) {
        long value=integer(raw,name);out.put(name+"_scaled",value);out.put(name,BigDecimal.valueOf(value,4));
    }
    private static long integer(String value,String field) {
        if(!value.matches("[0-9]{1,19}"))reject("invalid_numeric","Unsigned integral "+field+" required");
        try{return Long.parseLong(value);}catch(NumberFormatException e){reject("invalid_numeric",field+" exceeds LONG");return 0;}
    }
    private static String identifier(String value,String field) {
        if(!value.matches("[0-9]{1,32}"))reject("invalid_numeric","Exact unsigned integer "+field+" required");return value;
    }
    private static LocalDate wireDate(String text) {
        try {return text.matches("[0-9]{8}")?LocalDate.parse(text,DateTimeFormatter.BASIC_ISO_DATE):LocalDate.parse(text);}
        catch(java.time.DateTimeException e){reject("invalid_date","Invalid exchange business date");return null;}
    }
    private static Instant eventTime(LocalDate date,String text) {
        if(!text.matches("[0-9]{8,9}"))reject("invalid_time","Exchange clock must be HHMMSSmmm with optional leading hour zero");
        String clock="0".repeat(9-text.length())+text;
        try {return date.atTime(Integer.parseInt(clock.substring(0,2)),Integer.parseInt(clock.substring(2,4)),Integer.parseInt(clock.substring(4,6)),Integer.parseInt(clock.substring(6))*1_000_000).atZone(RawCapture.SHANGHAI).toInstant();}
        catch(java.time.DateTimeException e){reject("invalid_time","Invalid exchange clock");return null;}
    }
    private static String decode(byte[] bytes)throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static String sessionPhase(Instant eventTime) {
        LocalTime t=eventTime.atZone(RawCapture.SHANGHAI).toLocalTime();
        if(t.isBefore(LocalTime.of(9,0)))return "outside_session";
        if(t.isBefore(LocalTime.of(9,15)))return "service_open_0900_0915";
        if(t.isBefore(LocalTime.of(9,25)))return "opening_auction_0915_0925";
        if(t.isBefore(LocalTime.of(9,30)))return "pre_open_buffer_0925_0930";
        if(!t.isAfter(LocalTime.of(11,30)))return "continuous_morning_0930_1130";
        if(t.isBefore(LocalTime.of(13,0)))return "lunch_break_1130_1300";
        if(t.isBefore(LocalTime.of(14,57)))return "continuous_afternoon_1300_1457";
        return !t.isAfter(LocalTime.of(15,0))?"closing_auction_1457_1500":"after_close";
    }
    private static void reject(String status,String message){throw new Rejected(status,message);}
    private static Map<String,String> fieldTypes() {
        var types=new LinkedHashMap<String,String>();
        for(String name:ListHolder.STRINGS)types.put(name,"STRING");
        for(String name:new String[]{"volume","sub_seq","total_volume","total_amount","trade_count","total_ask_volume","total_bid_volume","depth_top1"})types.put(name,"LONG");
        for(String name:new String[]{"price","high","low","open","pre_close","weighted_ask_price","weighted_bid_price","limit_up","limit_down"}){types.put(name,"DOUBLE");types.put(name+"_scaled","LONG");}
        for(String name:new String[]{"amount","spread","mid_price","obi_top1"})types.put(name,"DOUBLE");
        for(int i=1;i<=10;i++)for(String side:new String[]{"bid","ask"}){types.put(side+"_price_scaled_"+i,"LONG");types.put(side+"_price_"+i,"DOUBLE");types.put(side+"_volume_"+i,"LONG");}
        types.put("kuake_order_type","INT");types.put("wire_field_count","INT");types.put("sequence_scope_unverified","BOOLEAN");types.put("wire_date_inferred","BOOLEAN");
        return Collections.unmodifiableMap(types);
    }
    private static final class ListHolder {
        static final String[] STRINGS={"parser_version","session_phase","event_type","aggressor_side","bs_flag","order_type","order_side","volume_semantics","channel_no","order_id","sell_order_id","buy_order_id","quality_flags"};
    }
}
