package com.zoutrankil.data.l2live.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One immutable parse result for one raw capture, including rejected wire messages. */
public record L2CleanEvent(RawCapture capture,String symbol,String exchange,String channel,LocalDate tradeDate,
        Instant exchangeTs,String parseStatus,String parseError,Map<String,Object> fields) {
    public L2CleanEvent {
        Objects.requireNonNull(capture);Objects.requireNonNull(tradeDate);Objects.requireNonNull(parseStatus);
        symbol=Objects.requireNonNullElse(symbol,"");exchange=Objects.requireNonNullElse(exchange,"");
        channel=Objects.requireNonNullElse(channel,"");parseError=Objects.requireNonNullElse(parseError,"");
        fields=Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(fields)));
        if(parseError.length()>512)throw new IllegalArgumentException("Bounded parse error required");
        if("ok".equals(parseStatus)&&(exchangeTs==null||!symbol.matches("[0-9]{6}\\.(SH|SZ)")
                ||!java.util.Set.of("trans","order","market").contains(channel)
                ||!exchange.equals(symbol.substring(7))
                ||!tradeDate.equals(exchangeTs.atZone(RawCapture.SHANGHAI).toLocalDate())))
            throw new IllegalArgumentException("Valid clean event requires canonical symbol and explicit event Instant");
    }
    public boolean isValid(){return "ok".equals(parseStatus);}
}
