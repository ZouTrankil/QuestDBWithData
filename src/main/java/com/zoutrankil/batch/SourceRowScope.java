package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

enum SourceRowScope {
    ENTITY, EXCHANGE, FUTURES_BASIC, DISCLOSURE, ETF_BASIC, AGGREGATE, FUTURES_HOLDING,
    MAIN_BUSINESS, SUSPENSION;

    void validate(SourceContract contract, Map<String,JsonNode> row, Map<String,Object> normalized,
                  LocalDate date, Set<String> expectedCodes) {
        switch (this) {
            case EXCHANGE -> {
                String exchange=(String)normalized.get("exchange");
                if(!contract.validCode(exchange) || !expectedCodes.contains(exchange)) throw new IllegalArgumentException("Exchange outside frozen expected universe");
                Object open=normalized.get("is_open");
                if(!Long.valueOf(0).equals(open) && !Long.valueOf(1).equals(open)) throw new IllegalArgumentException("Calendar open flag must be 0 or 1");
                Object previous=normalized.get("pretrade_date");
                if(previous!=null && !LocalDate.parse(previous.toString(),DateTimeFormatter.BASIC_ISO_DATE).isBefore(date))
                    throw new IllegalArgumentException("Previous trading day must precede calendar date");
            }
            case FUTURES_BASIC -> {
                String code=(String)normalized.get("ts_code"),exchange=(String)normalized.get("exchange");
                if(code==null||!code.matches("[A-Z]{1,3}[0-9]{3,4}\\.(CFX|SHF|DCE|CZC|INE|GFEX)")||!expectedCodes.contains(exchange)) throw new IllegalArgumentException("Futures contract outside frozen exchange snapshot");
            }
            case DISCLOSURE -> {
                String code=(String)normalized.get("ts_code"),period=(String)normalized.get("end_date");
                if(code==null||!code.matches("[0-9]{6}\\.(SH|SZ|BJ)")||period==null||!period.equals(date.format(DateTimeFormatter.BASIC_ISO_DATE)))
                    throw new IllegalArgumentException("Disclosure row escaped its quarter-end scope");
                for(String field:List.of("ann_date","pre_date","actual_date","modify_date")) {
                    JsonNode value=row.get(field);
                    if(value!=null&&!value.isNull()&&(!value.isTextual()||!value.asText().isBlank()&&!value.asText().matches("[0-9]{8}")))
                        throw new IllegalArgumentException("Invalid disclosure date field "+field);
                }
            }
            case ETF_BASIC -> {
                String code=(String)normalized.get("ts_code"),market=(String)normalized.get("market");
                if(code==null||!code.matches("[0-9]{6}\\.(SH|SZ)")||!expectedCodes.contains(market))
                    throw new IllegalArgumentException("ETF outside frozen market snapshot");
            }
            case AGGREGATE -> {
                if(!expectedCodes.isEmpty()) throw new IllegalArgumentException("Aggregate source cannot claim security coverage");
            }
            case FUTURES_HOLDING -> {
                String symbol=(String)normalized.get("symbol");
                if(!contract.validCode(symbol)||!expectedCodes.contains(symbol)) throw new IllegalArgumentException("Futures holding outside frozen product scope");
            }
            case ENTITY, MAIN_BUSINESS, SUSPENSION -> {
                String code=(String)normalized.get("ts_code");
                if(!contract.validCode(code) || !expectedCodes.contains(code))
                    throw new IllegalArgumentException("Entity outside frozen expected universe");
                if(this == MAIN_BUSINESS && !Set.of("P","D").contains(normalized.get("bz_code")))
                    throw new IllegalArgumentException("Unsupported main-business segment category");
                if(this == SUSPENSION && !Long.valueOf(1).equals(normalized.get("is_suspended")))
                    throw new IllegalArgumentException("Suspension snapshot only contains positive flags");
            }
        }
    }
}
