package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provider row projections selected explicitly by the source registry. */
enum SourceRowPreparation {
    UNCHANGED, ETF_SHARE, ETF_BASIC, FUTURES_BASIC, QUARTER, DIVIDEND, FUTURES_HOLDING,
    FUTURES_MAPPING, INDEX_MARKET, BOND_YIELD, SUSPENSION;

    List<Map<String,JsonNode>> prepare(SourceContract contract, List<Map<String,JsonNode>> rows,
            LocalDate date, Set<String> expectedCodes, String providerCode) {
        return switch (this) {
            case UNCHANGED -> rows;
            case ETF_SHARE -> rows.stream().map(row -> {
                var copy=new LinkedHashMap<>(row);
                copy.putIfAbsent("fund_type",Json.MAPPER.nullNode());
                copy.putIfAbsent("market",Json.MAPPER.nullNode());
                return Collections.unmodifiableMap(copy);
            }).toList();
            case ETF_BASIC -> rows.stream().map(row -> {
                String market=requiredText(row,"market");
                if(!expectedCodes.contains(market)) throw new IllegalArgumentException("ETF master response escaped its market scope");
                return Collections.unmodifiableMap(new LinkedHashMap<>(row));
            }).toList();
            case FUTURES_BASIC -> rows.stream().map(row -> {
                String exchange=requiredText(row,"exchange");
                if(!expectedCodes.contains(exchange)) throw new IllegalArgumentException("Futures master response escaped its exchange scope");
                var copy=new LinkedHashMap<>(row);
                copy.putIfAbsent("trade_time_desc",Json.MAPPER.nullNode());
                return Collections.unmodifiableMap(copy);
            }).toList();
            case QUARTER -> rows.stream().map(row -> {
                var copy=new LinkedHashMap<>(row);var quarter=copy.get("quarter");
                if(quarter==null||!quarter.isTextual()||!quarter.asText().matches("[0-9]{4}Q[1-4]")) throw new IllegalArgumentException("Invalid GDP quarter");
                int year=Integer.parseInt(quarter.asText().substring(0,4)),q=Character.digit(quarter.asText().charAt(5),10);
                LocalDate reportDate=YearMonth.of(year,q*3).atEndOfMonth();
                if(!reportDate.equals(date)) throw new IllegalArgumentException("GDP provider returned a different logical quarter");
                copy.put("report_date",Json.MAPPER.valueToTree(date.format(DateTimeFormatter.BASIC_ISO_DATE)));
                return Collections.unmodifiableMap(copy);
            }).toList();
            case DIVIDEND -> rows.stream().map(row -> {
                var copy=new LinkedHashMap<>(row);
                var endDate=copy.get("end_date");
                if(endDate==null || endDate.isNull() || !endDate.isTextual() || endDate.asText().isBlank())
                    copy.put("end_date",Json.MAPPER.valueToTree("unknown"));
                return Collections.unmodifiableMap(copy);
            }).toList();
            case FUTURES_HOLDING -> rows.stream().map(row -> {
                String symbol=requiredText(row,"symbol"),day=requiredText(row,"trade_date");
                if(!expectedCodes.contains(symbol)||!day.equals(date.format(DateTimeFormatter.BASIC_ISO_DATE)))
                    throw new IllegalArgumentException("Futures holding escaped its frozen product/date scope");
                var copy=new LinkedHashMap<>(row);copy.putIfAbsent("exchange",Json.MAPPER.nullNode());
                return Collections.unmodifiableMap(copy);
            }).toList();
            case FUTURES_MAPPING -> rows.stream().map(row -> {
                String code=requiredText(row,"ts_code"),day=requiredText(row,"trade_date"),mapped=requiredText(row,"mapping_ts_code");
                if(!expectedCodes.contains(code) || !day.equals(date.format(DateTimeFormatter.BASIC_ISO_DATE)))
                    throw new IllegalArgumentException("Futures mapping escaped its frozen contract/date scope");
                if(!mapped.matches("[A-Z]{1,3}[0-9]{3,4}\\.(CFX|SHF|DCE|CZC|INE|GFEX)"))
                    throw new IllegalArgumentException("Invalid mapped futures month contract");
                return Collections.unmodifiableMap(new LinkedHashMap<>(row));
            }).toList();
            case INDEX_MARKET -> prepareIndexRows(rows,providerCode);
            case BOND_YIELD -> prepareBondRows(rows,date);
            case SUSPENSION -> prepareSuspensions(contract,rows,date,expectedCodes);
        };
    }

    private static List<Map<String,JsonNode>> prepareIndexRows(List<Map<String,JsonNode>> rows, String providerCode) {
        if(providerCode==null || !providerCode.endsWith(".SI")) return rows;
        var normalized=new ArrayList<Map<String,JsonNode>>(rows.size());
        for(var row:rows) {
            if(!row.containsKey("pct_change") || row.containsKey("pct_chg")) throw new IllegalArgumentException("申万日线涨跌幅字段漂移");
            var copy=new LinkedHashMap<>(row);copy.put("pct_chg",copy.remove("pct_change"));
            copy.putIfAbsent("pre_close",Json.MAPPER.nullNode());normalized.add(copy);
        }
        return List.copyOf(normalized);
    }

    private static List<Map<String,JsonNode>> prepareSuspensions(SourceContract contract,
            List<Map<String,JsonNode>> rows, LocalDate date, Set<String> expectedCodes) {
        if(rows.size()>=contract.pageSize()) throw new IllegalArgumentException("Suspension source reached unpaged cap");
        var byCode=new LinkedHashMap<String,Map<String,JsonNode>>();
        for(var row:rows) {
            if(!row.keySet().containsAll(contract.providerContract().fields())) throw new IllegalArgumentException("Suspension source schema drift");
            var day=row.get("trade_date");var type=row.get("suspend_type");var code=row.get("ts_code");
            if(!day.isTextual() || !day.asText().equals(date.format(DateTimeFormatter.BASIC_ISO_DATE)))
                throw new IllegalArgumentException("Suspension date mismatch");
            if(!type.isTextual() || !Set.of("S","R").contains(type.asText())) throw new IllegalArgumentException("Unknown suspension event type");
            if(type.asText().equals("R")) continue;
            if(!code.isTextual() || !expectedCodes.contains(code.asText().trim())) throw new IllegalArgumentException("Suspension outside expected universe");
            String normalizedCode=code.asText().trim();
            byCode.put(normalizedCode,Map.of("ts_code",Json.MAPPER.valueToTree(normalizedCode),"trade_date",day,
                    "is_suspended",Json.MAPPER.valueToTree(1L)));
        }
        return List.copyOf(byCode.values());
    }

    private static List<Map<String,JsonNode>> prepareBondRows(List<Map<String,JsonNode>> rows, LocalDate date) {
        Map<String,String> curves=Map.of("中债国债收益率曲线","gov",
                "中债中短期票据收益率曲线(AAA)","aaa_mtn","中债商业银行普通债收益率曲线(AAA)","aaa_bank");
        Map<String,String> tenors=Map.of("raw_3m","3M","raw_6m","6M","raw_1y","1Y","raw_3y","3Y",
                "raw_5y","5Y","raw_7y","7Y","raw_10y","10Y","raw_30y","30Y");
        var output=new ArrayList<Map<String,JsonNode>>();var rawKeys=new HashSet<String>();
        for(var raw:rows) {
            if(!raw.keySet().containsAll(List.of("raw_trade_date","raw_curve_name","raw_3m","raw_6m","raw_1y","raw_3y","raw_5y","raw_7y","raw_10y","raw_30y"))) throw new IllegalArgumentException("ChinaBond schema drift");
            String day=requiredText(raw,"raw_trade_date"),name=requiredText(raw,"raw_curve_name");
            if(!day.equals(date.toString())) throw new IllegalArgumentException("ChinaBond returned a different logical date");
            if(!rawKeys.add(day+"|"+name)) throw new IllegalArgumentException("Duplicate ChinaBond curve/date row");
            String curve=curves.get(name);if(curve==null) continue;
            for(var tenor:tenors.entrySet()) {
                var value=raw.get(tenor.getKey());if(value==null) throw new IllegalArgumentException("Missing ChinaBond tenor field");
                if(value.isNull()||value.asText().isBlank()||Set.of("-","--","—").contains(value.asText().trim())) continue;
                double number;
                if(value.isNumber()) number=value.doubleValue();
                else if(value.isTextual()&&value.asText().trim().matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) number=Double.parseDouble(value.asText().trim());
                else throw new IllegalArgumentException("Invalid ChinaBond yield value");
                if(!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite ChinaBond yield");
                var row=new LinkedHashMap<String,JsonNode>();row.put("trade_date",Json.MAPPER.valueToTree(date.format(DateTimeFormatter.BASIC_ISO_DATE)));
                row.put("curve_name",Json.MAPPER.valueToTree(name));row.put("curve_code",Json.MAPPER.valueToTree(curve));
                row.put("tenor",Json.MAPPER.valueToTree(tenor.getValue()));row.put("yield_value",Json.MAPPER.valueToTree(number));
                row.put("source",Json.MAPPER.valueToTree("akshare"));output.add(row);
            }
        }
        return List.copyOf(output);
    }

    private static String requiredText(Map<String,JsonNode> row,String key) {
        var value=row.get(key);if(value==null||!value.isTextual()||value.asText().isBlank()) throw new IllegalArgumentException("Missing ChinaBond field "+key);
        return value.asText().trim();
    }
}
