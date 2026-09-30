package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Explicit source-to-physical-column contracts. No schema/semantic guessing at runtime. */
public record SourceContract(String dataset, String version, String endpoint, String dateParameter,
        String sourceDate, String timestampColumn, int pageSize, boolean paged, boolean emptyAllowed,
        int maxRows, int maxBytes, String sourceDocumentation, List<Column> columns, String partitionBy) {
    public record Column(String source, String target, String type, boolean key) {}
    public static final Set<String> SUPPORTED=Set.of("daily","daily_basic","etf_daily","stk_limit","etf_adj","moneyflow","etf_factor","margin_detail","moneyflow_hsgt","stk_suspend","etf_portfolio","stk_factor","stk_st_daily","cn_bond_yield_curve","cyq_perf","index_daily_market","index_daily_basic","exchange_calendar","fina_mainbz","fina_audit","dividend","share_float","shibor","shibor_lpr","hibor","cn_cpi","cn_ppi","cn_pmi","cn_m","cn_gdp","fut_daily","fut_settle","fut_mapping","ft_limit","fut_holding","fut_basic","etf_basic","disclosure_date","ths_index","etf_share","us_tbr");
    public static final Set<String> MONTHLY_AGGREGATES=Set.of("cn_cpi","cn_ppi","cn_pmi","cn_m");
    public static final Set<String> QUARTERLY_AGGREGATES=Set.of("cn_gdp");
    public SourceContract {
        if (!SUPPORTED.contains(dataset) || version==null || version.isBlank()) throw new IllegalArgumentException("Unknown source contract");
        if(partitionBy==null) partitionBy="DAY";
        if(!Set.of("DAY","MONTH","YEAR").contains(partitionBy)) throw new IllegalArgumentException("Unsupported partition granularity");
        columns=List.copyOf(columns);
        if(columns.isEmpty() || maxRows<1 || maxRows>100000 || maxBytes<1 || maxBytes>64*1024*1024 || pageSize<1 || pageSize>maxRows)
            throw new IllegalArgumentException("Bounded source contract required");
        var names=new HashSet<String>(); var sources=new HashSet<String>();
        for(var c:columns) {
            if(!c.target().matches("[a-z0-9][a-z0-9_]*") || !c.source().matches("[a-z0-9][a-z0-9_]*")
                    || !Set.of("TIMESTAMP","TIMESTAMP_NS","SYMBOL","STRING","DOUBLE","LONG","INT").contains(c.type())
                    || !names.add(c.target()) || !sources.add(c.source())) throw new IllegalArgumentException("Invalid column contract");
        }
        if(columns.stream().noneMatch(c -> c.target().equals(timestampColumn) && c.key() && c.type().startsWith("TIMESTAMP")))
            throw new IllegalArgumentException("Designated timestamp must be part of business key");
    }
    public static SourceContract load(String dataset) {
        if(!SUPPORTED.contains(dataset)) throw new IllegalArgumentException("Unregistered source: "+dataset);
        try(var stream=SourceContract.class.getResourceAsStream("/contracts/source/"+dataset+".json")) {
            if(stream==null) throw new IllegalStateException("Missing registered source contract");
            return Json.MAPPER.readValue(stream,SourceContract.class);
        } catch(java.io.IOException error) { throw new IllegalStateException("Unreadable source contract",error); }
    }
    public boolean isMarketAggregate() { return dataset.equals("moneyflow_hsgt") || dataset.equals("cn_bond_yield_curve") || dataset.equals("disclosure_date") || dataset.equals("ths_index") || dataset.equals("etf_share") || Set.of("shibor","shibor_lpr","hibor","us_tbr").contains(dataset) || MONTHLY_AGGREGATES.contains(dataset) || QUARTERLY_AGGREGATES.contains(dataset); }
    public boolean validCode(String code) {
        if(code==null) return false;
        if(dataset.equals("exchange_calendar")) return Set.of("SSE","SZSE").contains(code);
        if(dataset.equals("etf_basic")) return code.equals("E");
        if(dataset.equals("fut_basic")) return Set.of("CFFEX","DCE","CZCE","CZC","SHFE","SHF","INE","GFEX").contains(code)
                || code.matches("[A-Z]{1,3}[0-9]{3,4}\\.(CFX|SHF|DCE|CZC|INE|GFEX)");
        if(dataset.equals("fut_mapping")) return code.matches("[A-Z]{1,3}\\.(CFX|SHF|DCE|CZC|INE|GFEX)");
        if(dataset.equals("fut_holding")) return code.matches("[A-Z]{1,3}");
        if(Set.of("fut_daily","fut_settle","ft_limit","fut_basic").contains(dataset)) return code.matches("[A-Z]{1,3}[0-9]{3,4}\\.(CFX|SHF|DCE|CZC|INE|GFEX)");
        if(dataset.equals("fina_mainbz")) return code.matches("[0-9]{6}\\.(SH|SZ|BJ)");
        if(dataset.equals("dividend")) return code.matches("[0-9]{6}\\.(SH|SZ|BJ)");
        if(dataset.equals("etf_portfolio")) return code.matches("[0-9]{6}\\.(SH|SZ|BJ|OF)");
        if(dataset.equals("index_daily_market")) return code.matches("[0-9]{6}\\.(SH|SZ|BJ|CSI|SI)");
        return code.matches("[0-9]{6}\\.(SH|SZ|BJ)");
    }
    public List<Column> businessColumns() {
        return columns.stream().filter(c -> !(Set.of("etf_portfolio","index_daily_market","dividend","fut_daily","fut_settle","fut_mapping","ft_limit","fut_holding","fut_basic","etf_basic","disclosure_date","ths_index","etf_share").contains(dataset)
                && (c.target().equals("update_time") || Set.of("fut_basic","etf_basic").contains(dataset)&&c.target().equals("timestamp")))).toList();
    }
    public String logicalDateColumn() {
        return columns.stream().filter(c -> c.source().equals(sourceDate)).findFirst().orElseThrow().target();
    }
    public int writeBatchSize() { return dataset.equals("etf_portfolio")?50:1000; }
    public Map<String,Object> physicalRow(Map<String,Object> businessRow,Instant createdAt) {
        if(dataset.equals("fut_basic")) {
            var result=new LinkedHashMap<String,Object>(businessRow);
            result.put("timestamp","1970-01-01T00:00:00Z");
            result.put("update_time",createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
            return Collections.unmodifiableMap(result);
        }
        if(dataset.equals("etf_basic")) {
            var result=new LinkedHashMap<String,Object>(businessRow);
            result.put("timestamp","1970-01-01T00:00:00Z");
            result.put("update_time",createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
            return Collections.unmodifiableMap(result);
        }
        if(dataset.equals("etf_share")) {
            var result=new LinkedHashMap<String,Object>(businessRow);
            result.put("update_time",createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
            return Collections.unmodifiableMap(result);
        }
        if(dataset.equals("disclosure_date") || dataset.equals("ths_index")) {
            var result=new LinkedHashMap<String,Object>(businessRow);
            result.put("update_time",createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
            return Collections.unmodifiableMap(result);
        }
        if(!Set.of("etf_portfolio","index_daily_market","dividend","fut_daily","fut_settle","fut_mapping","ft_limit","fut_holding").contains(dataset)) return businessRow;
        var result=new LinkedHashMap<String,Object>(businessRow);
        result.put("update_time",createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        return Collections.unmodifiableMap(result);
    }
    public Set<String> observedCodes(List<Map<String,Object>> rows) {
        if(isMarketAggregate()) return Set.of();
        String entityColumn=dataset.equals("exchange_calendar")||dataset.equals("fut_basic")?"exchange":dataset.equals("etf_basic")?"market":dataset.equals("fut_holding")?"symbol":"ts_code";
        var codes=new TreeSet<String>();rows.forEach(row -> codes.add(Objects.requireNonNull((String)row.get(entityColumn))));return codes;
    }
    public boolean covers(List<Map<String,Object>> rows,Set<String> expectedCodes) {
        if(Set.of("fut_basic","etf_basic").contains(dataset)) return !expectedCodes.isEmpty() && !rows.isEmpty() && observedCodes(rows).equals(expectedCodes);
        if(dataset.equals("disclosure_date")) return expectedCodes.isEmpty() && rows.size()<=maxRows;
        if(dataset.equals("ths_index")) return expectedCodes.isEmpty() && !rows.isEmpty() && rows.size()<maxRows;
        if(dataset.equals("etf_share")) return expectedCodes.isEmpty() && !rows.isEmpty() && rows.size()<maxRows;
        if(dataset.equals("cyq_perf")) return !expectedCodes.isEmpty() && observedCodes(rows).equals(expectedCodes);
        if(dataset.equals("cn_bond_yield_curve")) return expectedCodes.isEmpty()
                && rows.stream().map(r -> r.get("curve_code")).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("gov","aaa_mtn","aaa_bank"));
        if(Set.of("shibor","shibor_lpr","hibor","us_tbr").contains(dataset)) return expectedCodes.isEmpty() && rows.size()<=1;
        if(QUARTERLY_AGGREGATES.contains(dataset)) return expectedCodes.isEmpty() && rows.size()<=maxRows;
        if(MONTHLY_AGGREGATES.contains(dataset)) return expectedCodes.isEmpty();
        return isMarketAggregate()? expectedCodes.isEmpty() && rows.size()==1 :
                emptyAllowed?expectedCodes.containsAll(observedCodes(rows)):observedCodes(rows).equals(expectedCodes);
    }
    public PageContract pageContract() {
        if(Set.of("fut_basic","etf_basic").contains(dataset)) return providerContract();
        if(Set.of("disclosure_date","ths_index","etf_share").contains(dataset)) return providerContract();
        if(dataset.equals("fina_mainbz")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("period","type","ts_code","limit","offset"),PageContract.Paging.OFFSET,PageContract.Completion.SHORT_PAGE,
                "limit","offset",pageSize,pageSize,20,maxRows,sourceDocumentation);
        if(dataset.equals("dividend")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("ann_date","ts_code"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
        if(dataset.equals("shibor")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("start_date","end_date"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
        if(dataset.equals("shibor_lpr")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("start_date","end_date"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
        if(dataset.equals("hibor")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("start_date","end_date"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
        if(dataset.equals("us_tbr")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("date"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
        if(MONTHLY_AGGREGATES.contains(dataset)) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("m","start_m","end_m"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
        if(QUARTERLY_AGGREGATES.contains(dataset)) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).filter(f -> !f.equals("report_date")).toList(),List.of("quarter"),
                Set.of("q","start_q","end_q"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
        if(dataset.equals("exchange_calendar")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("exchange","start_date","end_date"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
        if(dataset.equals("cyq_perf")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("trade_date","ts_code","limit","offset"),PageContract.Paging.OFFSET,PageContract.Completion.SHORT_PAGE,
                "limit","offset",pageSize,pageSize,100,maxRows,sourceDocumentation);
        if(Set.of("index_daily_market","index_daily_basic").contains(dataset)) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("trade_date","ts_code"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
        if(dataset.equals("cn_bond_yield_curve")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),
                Set.of("start_date","end_date"),PageContract.Paging.NONE,
                PageContract.Completion.SHORT_PAGE,null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
        if(dataset.equals("stk_st_daily")) return new PageContract(endpoint,List.of("ts_code","name","start_date","end_date","ann_date","change_reason"),
                List.of("ts_code","name","start_date"),Set.of("start_date","end_date","ts_code","limit","cursor"),
                PageContract.Paging.CURSOR,PageContract.Completion.EXPLICIT_END,"limit","cursor",pageSize,pageSize,100,maxRows,sourceDocumentation);
        var fields=businessColumns().stream().map(Column::source).toList();
        var keys=columns.stream().filter(Column::key).map(Column::source).toList();
        return new PageContract(endpoint,fields,keys,isMarketAggregate()?Set.of("start_date","end_date"):dataset.equals("fut_holding")?Set.of("trade_date","symbol","exchange"):Set.of(dateParameter,"ts_code","limit","offset","suspend_type"),
                paged?PageContract.Paging.OFFSET:PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                paged?"limit":null,paged?"offset":null,pageSize,pageSize,64,maxRows,sourceDocumentation);
    }
    public PageContract providerContract() {
        return providerContract(null);
    }
    public PageContract providerContract(String code) {
        if(dataset.equals("etf_share")) return new PageContract(endpoint,List.of("ts_code","trade_date","fd_share"),List.of("ts_code","trade_date"),Set.of("trade_date"),
                PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,pageSize,pageSize,1,maxRows,
                sourceDocumentation+"; one full-market trading-date snapshot, no paging, vendor cap must not be reached");
        if(dataset.equals("ths_index")) return new PageContract(endpoint,businessColumns().stream().map(Column::source).toList(),List.of("ts_code"),Set.of(),
                PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,pageSize,pageSize,1,maxRows,
                sourceDocumentation+"; full static snapshot, no request parameters, hard response cap");
        if(dataset.equals("disclosure_date")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),columns.stream().filter(Column::key).map(Column::source).toList(),Set.of("end_date"),
                PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,pageSize,pageSize,1,maxRows,
                sourceDocumentation+"; one exact report quarter per request, no paging, hard response cap");
        if(dataset.equals("etf_basic")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).toList(),List.of("ts_code"),Set.of("market","status"),
                PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,pageSize,pageSize,1,maxRows,
                sourceDocumentation+"; fund_basic returns a bounded static market snapshot without paging");
        if(dataset.equals("fut_basic")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).filter(f -> !f.equals("trade_time_desc")).toList(),List.of("ts_code"),Set.of("exchange","fut_type"),
                PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,pageSize,pageSize,1,maxRows,
                sourceDocumentation+"; exchange is required, fut_type=1 selects standard contracts, response cap is not paged");
        if(dataset.equals("fut_holding")) return new PageContract(endpoint,
                businessColumns().stream().map(Column::source).filter(f -> !f.equals("exchange")).toList(),
                columns.stream().filter(Column::key).map(Column::source).toList(),Set.of("trade_date","symbol","exchange"),
                PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,pageSize,pageSize,1,maxRows,
                sourceDocumentation+"; optional provider exchange column is nullable in the legacy model");
        if(dataset.equals("index_daily_market") && code!=null && code.endsWith(".SI")) {
            var fields=businessColumns().stream().map(Column::source).filter(f -> !f.equals("pre_close"))
                    .map(f -> f.equals("pct_chg")?"pct_change":f).toList();
            return new PageContract("sw_daily",fields,columns.stream().filter(Column::key).map(Column::source).toList(),
                    Set.of("trade_date","ts_code"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                    null,null,pageSize,pageSize,1,maxRows,"legacy index_daily_market sw_daily route; pct_change maps to pct_chg");
        }
        if(dataset.equals("stk_st_daily")) return pageContract();
        if(!dataset.equals("stk_suspend")) return pageContract();
        return new PageContract(endpoint,List.of("ts_code","trade_date","suspend_timing","suspend_type"),
                List.of("ts_code","trade_date"),Set.of("trade_date","ts_code","suspend_type"),
                PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,pageSize,pageSize,1,maxRows,sourceDocumentation);
    }
    /** Raw event projection is done only after the original response has been bounded and archived. */
    public List<Map<String,JsonNode>> prepareRows(List<Map<String,JsonNode>> rows,LocalDate date,Set<String> expectedCodes) {
        return prepareRows(rows,date,expectedCodes,null);
    }
    public List<Map<String,JsonNode>> prepareRows(List<Map<String,JsonNode>> rows,LocalDate date,Set<String> expectedCodes,String providerCode) {
        if(dataset.equals("etf_share")) return rows.stream().map(row -> {
            var copy=new LinkedHashMap<>(row);
            copy.putIfAbsent("fund_type",Json.MAPPER.nullNode());
            copy.putIfAbsent("market",Json.MAPPER.nullNode());
            return Collections.unmodifiableMap(copy);
        }).toList();
        if(dataset.equals("etf_basic")) return rows.stream().map(row -> {
            String market=requiredText(row,"market");
            if(!expectedCodes.contains(market)) throw new IllegalArgumentException("ETF master response escaped its market scope");
            return Collections.unmodifiableMap(new LinkedHashMap<>(row));
        }).toList();
        if(dataset.equals("fut_basic")) return rows.stream().map(row -> {
            String exchange=requiredText(row,"exchange");
            if(!expectedCodes.contains(exchange)) throw new IllegalArgumentException("Futures master response escaped its exchange scope");
            var copy=new LinkedHashMap<>(row);
            copy.putIfAbsent("trade_time_desc",Json.MAPPER.nullNode());
            return Collections.unmodifiableMap(copy);
        }).toList();
        if(dataset.equals("cn_gdp")) return rows.stream().map(row -> {
            var copy=new LinkedHashMap<>(row);var quarter=copy.get("quarter");
            if(quarter==null||!quarter.isTextual()||!quarter.asText().matches("[0-9]{4}Q[1-4]")) throw new IllegalArgumentException("Invalid GDP quarter");
            int year=Integer.parseInt(quarter.asText().substring(0,4)),q=Character.digit(quarter.asText().charAt(5),10);
            LocalDate reportDate=YearMonth.of(year,q*3).atEndOfMonth();
            if(!reportDate.equals(date)) throw new IllegalArgumentException("GDP provider returned a different logical quarter");
            copy.put("report_date",Json.MAPPER.valueToTree(date.format(DateTimeFormatter.BASIC_ISO_DATE)));
            return Collections.unmodifiableMap(copy);
        }).toList();
        if(dataset.equals("dividend")) return rows.stream().map(row -> {
            var copy=new LinkedHashMap<>(row);
            var endDate=copy.get("end_date");
            if(endDate==null || endDate.isNull() || !endDate.isTextual() || endDate.asText().isBlank())
                copy.put("end_date",Json.MAPPER.valueToTree("unknown"));
            return Collections.unmodifiableMap(copy);
        }).toList();
        if(dataset.equals("fut_holding")) return rows.stream().map(row -> {
            String symbol=requiredText(row,"symbol"),day=requiredText(row,"trade_date");
            if(!expectedCodes.contains(symbol)||!day.equals(date.format(DateTimeFormatter.BASIC_ISO_DATE)))
                throw new IllegalArgumentException("Futures holding escaped its frozen product/date scope");
            var copy=new LinkedHashMap<>(row);copy.putIfAbsent("exchange",Json.MAPPER.nullNode());
            return Collections.unmodifiableMap(copy);
        }).toList();
        if(dataset.equals("fut_mapping")) return rows.stream().map(row -> {
            String code=requiredText(row,"ts_code"),day=requiredText(row,"trade_date"),mapped=requiredText(row,"mapping_ts_code");
            if(!expectedCodes.contains(code) || !day.equals(date.format(DateTimeFormatter.BASIC_ISO_DATE)))
                throw new IllegalArgumentException("Futures mapping escaped its frozen contract/date scope");
            if(!mapped.matches("[A-Z]{1,3}[0-9]{3,4}\\.(CFX|SHF|DCE|CZC|INE|GFEX)"))
                throw new IllegalArgumentException("Invalid mapped futures month contract");
            return Collections.unmodifiableMap(new LinkedHashMap<>(row));
        }).toList();
        if(dataset.equals("index_daily_market") && providerCode!=null && providerCode.endsWith(".SI")) {
            var normalized=new ArrayList<Map<String,JsonNode>>(rows.size());
            for(var row:rows) {
                if(!row.containsKey("pct_change") || row.containsKey("pct_chg")) throw new IllegalArgumentException("申万日线涨跌幅字段漂移");
                var copy=new LinkedHashMap<>(row);copy.put("pct_chg",copy.remove("pct_change"));
                copy.putIfAbsent("pre_close",Json.MAPPER.nullNode());normalized.add(copy);
            }
            return List.copyOf(normalized);
        }
        if(dataset.equals("stk_st_daily")) return rows;
        if(dataset.equals("cn_bond_yield_curve")) return prepareBondRows(rows,date);
        if(!dataset.equals("stk_suspend")) return rows;
        if(rows.size()>=pageSize) throw new IllegalArgumentException("Suspension source reached unpaged cap");
        var byCode=new LinkedHashMap<String,Map<String,JsonNode>>();
        for(var row:rows) {
            if(!row.keySet().containsAll(providerContract().fields())) throw new IllegalArgumentException("Suspension source schema drift");
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
    private List<Map<String,JsonNode>> prepareBondRows(List<Map<String,JsonNode>> rows,LocalDate date) {
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
    /** Only the exact, completely empty provider date footer is not a security observation. */
    public boolean ignoredFooter(Map<String,JsonNode> row,LocalDate date) {
        if(!dataset.equals("margin_detail")) return false;
        var code=row.get("ts_code");var sourceDay=row.get("trade_date");
        if(code==null || !code.isTextual() || !code.asText().equals("日期："+date+".BJ")
                || sourceDay==null || !sourceDay.isTextual()
                || !sourceDay.asText().equals(date.format(DateTimeFormatter.BASIC_ISO_DATE))) return false;
        return columns.stream().filter(c -> c.type().equals("DOUBLE"))
                .allMatch(c -> row.containsKey(c.source()) && row.get(c.source()).isNull());
    }
    public Map<String,Object> normalize(Map<String,JsonNode> row,LocalDate date,Set<String> expectedCodes) {
        if(dataset.equals("fina_mainbz") && (!Set.of(3,6,9,12).contains(date.getMonthValue()) || date.getDayOfMonth()!=date.lengthOfMonth()))
            throw new IllegalArgumentException("fina_mainbz report date must be a quarter end");
        if(dataset.equals("disclosure_date") && (!Set.of(3,6,9,12).contains(date.getMonthValue()) || date.getDayOfMonth()!=date.lengthOfMonth()))
            throw new IllegalArgumentException("disclosure_date period must be a quarter end");
        var normalized=new LinkedHashMap<String,Object>();
        for(var column:businessColumns()) {
            JsonNode value=row.get(column.source());
            if(value==null) throw new IllegalArgumentException("Schema drift: missing "+column.source());
            if(value.isNull()) {
                if(column.key()) throw new IllegalArgumentException("Null business key");
                // Frozen legacy moneyflow transform: only volume integers fill source null with zero.
                normalized.put(column.target(),dataset.equals("moneyflow") && column.type().equals("LONG") ? 0L : null); continue;
            }
            Object converted=switch(column.type()) {
                case "TIMESTAMP", "TIMESTAMP_NS" -> {
                    if(MONTHLY_AGGREGATES.contains(dataset) && column.source().equals("month")) {
                        if(!value.isTextual() || !value.asText().matches("[0-9]{6}")) throw new IllegalArgumentException("Exact CPI observation month required");
                        YearMonth month=YearMonth.parse(value.asText(),java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
                        if(!month.equals(YearMonth.from(date))) throw new IllegalArgumentException("CPI returned a different observation month");
                        yield month.atDay(1).toString();
                    }
                    if(!value.isTextual() || !value.asText().matches("[0-9]{8}")) throw new IllegalArgumentException("Exact business date required");
                    LocalDate parsed=LocalDate.parse(value.asText(),DateTimeFormatter.BASIC_ISO_DATE);
                    if(column.source().equals(sourceDate) && !parsed.equals(date)) throw new IllegalArgumentException("Source returned a different logical date");
                    if(!column.source().equals(sourceDate) && parsed.isAfter(date) && !Set.of("share_float","disclosure_date").contains(dataset)) throw new IllegalArgumentException("Report period cannot follow publication date");
                    if(dataset.equals("share_float") && column.source().equals("float_date") && parsed.isBefore(date))
                        throw new IllegalArgumentException("Unlock event date cannot precede its announcement date");
                    yield parsed.toString();
                }
                case "SYMBOL", "STRING" -> {
                    if(!value.isTextual()) throw new IllegalArgumentException("Text type mismatch");
                    if(column.key() && (value.asText().isBlank() || value.asText().length()>128)) throw new IllegalArgumentException("Invalid text key");
                    yield value.asText();
                }
                case "DOUBLE" -> {
                    // This provider returns quoted decimals; the frozen legacy adapter explicitly converts them.
                    double number;
                    if((isMarketAggregate() || dataset.equals("etf_portfolio")) && value.isTextual()
                            && value.asText().trim().matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                        number=Double.parseDouble(value.asText().trim());
                    else if(value.isNumber()) number=value.doubleValue();
                    else throw new IllegalArgumentException("Numeric type mismatch");
                    if(!Double.isFinite(number)) throw new IllegalArgumentException("Finite numeric value required");
                    yield number;
                }
                case "LONG", "INT" -> {
                    if(!value.isIntegralNumber() || !value.canConvertToLong()
                            || column.type().equals("INT")&&!value.canConvertToInt()) throw new IllegalArgumentException("Integer precision mismatch");
                    yield value.longValue();
                }
                default -> throw new IllegalStateException("Unsupported type");
            };
            if(dataset.equals("share_float") && column.source().equals("ann_date")) {
                if(!value.isTextual() || !value.asText().matches("[0-9]{8}")
                        || !LocalDate.parse(value.asText(),DateTimeFormatter.BASIC_ISO_DATE).equals(date))
                    throw new IllegalArgumentException("Share-float announcement date must match its probe date");
            }
            if(dataset.equals("fina_audit") && column.source().equals("end_date")) {
                if(!value.isTextual() || !value.asText().matches("[0-9]{8}")) throw new IllegalArgumentException("Invalid audit report period");
                if(LocalDate.parse(value.asText(),DateTimeFormatter.BASIC_ISO_DATE).isAfter(date))
                    throw new IllegalArgumentException("Audit report period cannot follow its announcement date");
            }
            normalized.put(column.target(),converted);
        }
        if(dataset.equals("exchange_calendar")) {
            String exchange=(String)normalized.get("exchange");
            if(!validCode(exchange) || !expectedCodes.contains(exchange)) throw new IllegalArgumentException("Exchange outside frozen expected universe");
            Object open=normalized.get("is_open");
            if(!Long.valueOf(0).equals(open) && !Long.valueOf(1).equals(open)) throw new IllegalArgumentException("Calendar open flag must be 0 or 1");
            Object previous=normalized.get("pretrade_date");
            if(previous!=null && !LocalDate.parse(previous.toString(),DateTimeFormatter.BASIC_ISO_DATE).isBefore(date))
                throw new IllegalArgumentException("Previous trading day must precede calendar date");
            return Collections.unmodifiableMap(normalized);
        }
        if(dataset.equals("fut_basic")) {
            String code=(String)normalized.get("ts_code"),exchange=(String)normalized.get("exchange");
            if(code==null||!code.matches("[A-Z]{1,3}[0-9]{3,4}\\.(CFX|SHF|DCE|CZC|INE|GFEX)")||!expectedCodes.contains(exchange)) throw new IllegalArgumentException("Futures contract outside frozen exchange snapshot");
            return Collections.unmodifiableMap(normalized);
        }
        if(dataset.equals("disclosure_date")) {
            String code=(String)normalized.get("ts_code"),period=(String)normalized.get("end_date");
            if(code==null||!code.matches("[0-9]{6}\\.(SH|SZ|BJ)")||period==null||!period.equals(date.format(DateTimeFormatter.BASIC_ISO_DATE)))
                throw new IllegalArgumentException("Disclosure row escaped its quarter-end scope");
            for(String field:List.of("ann_date","pre_date","actual_date","modify_date")) {
                JsonNode value=row.get(field);
                if(value!=null&&!value.isNull()&&(!value.isTextual()||!value.asText().isBlank()&&!value.asText().matches("[0-9]{8}")))
                    throw new IllegalArgumentException("Invalid disclosure date field "+field);
            }
            return Collections.unmodifiableMap(normalized);
        }
        if(dataset.equals("etf_basic")) {
            String code=(String)normalized.get("ts_code"),market=(String)normalized.get("market");
            if(code==null||!code.matches("[0-9]{6}\\.(SH|SZ)")||!expectedCodes.contains(market))
                throw new IllegalArgumentException("ETF outside frozen market snapshot");
            return Collections.unmodifiableMap(normalized);
        }
        if(isMarketAggregate()) {
            if(!expectedCodes.isEmpty()) throw new IllegalArgumentException("Aggregate source cannot claim security coverage");
            return Collections.unmodifiableMap(normalized);
        }
        if(dataset.equals("fut_holding")) {
            String symbol=(String)normalized.get("symbol");
            if(!validCode(symbol)||!expectedCodes.contains(symbol)) throw new IllegalArgumentException("Futures holding outside frozen product scope");
            return Collections.unmodifiableMap(normalized);
        }
        String code=(String)normalized.get("ts_code");
        if(!validCode(code) || !expectedCodes.contains(code))
            throw new IllegalArgumentException("Entity outside frozen expected universe");
        if(dataset.equals("fina_mainbz") && !Set.of("P","D").contains(normalized.get("bz_code")))
            throw new IllegalArgumentException("Unsupported main-business segment category");
        if(dataset.equals("stk_suspend") && !Long.valueOf(1).equals(normalized.get("is_suspended")))
            throw new IllegalArgumentException("Suspension snapshot only contains positive flags");
        return Collections.unmodifiableMap(normalized);
    }
    public Map<String,Object> validateNormalized(Map<String,Object> row,LocalDate date,Set<String> expectedCodes) {
        var source=new LinkedHashMap<String,JsonNode>();
        if(!row.keySet().equals(new HashSet<>(businessColumns().stream().map(Column::target).toList())))
            throw new IllegalArgumentException("Frozen schema differs from registered contract");
        for(var column:businessColumns()) {
            Object value=row.get(column.target());
            if(dataset.equals("moneyflow") && column.type().equals("LONG") && value==null)
                throw new IllegalArgumentException("Frozen volume does not match registered null-to-zero transform");
            if(column.type().startsWith("TIMESTAMP") && value!=null) {
                if(MONTHLY_AGGREGATES.contains(dataset) && column.source().equals("month"))
                    value=YearMonth.parse(value.toString().substring(0,7)).format(DateTimeFormatter.ofPattern("yyyyMM"));
                else {
                    LocalDate parsed=LocalDate.parse(value.toString());
                    value=parsed.format(DateTimeFormatter.BASIC_ISO_DATE);
                }
            }
            source.put(column.source(),Json.MAPPER.valueToTree(value));
        }
        return normalize(source,date,expectedCodes);
    }
    public String key(Map<String,Object> row) {
        if(dataset.equals("fut_basic")) {
            Object timestamp=row.get("timestamp");
            if(timestamp!=null&&!Set.of("1970-01-01","1970-01-01T00:00:00Z","1970-01-01T00:00:00").contains(timestamp.toString()))
                throw new IllegalArgumentException("Futures basic technical timestamp must remain the legacy epoch sentinel");
            return Json.write(List.of(Objects.requireNonNull(row.get("ts_code"),"key"),"1970-01-01"));
        }
        if(dataset.equals("ths_index")) return Json.write(List.of(Objects.requireNonNull(row.get("ts_code"),"key")));
        if(dataset.equals("etf_basic")) {
            Object timestamp=row.get("timestamp");
            if(timestamp!=null&&!Set.of("1970-01-01","1970-01-01T00:00:00Z","1970-01-01T00:00:00").contains(timestamp.toString()))
                throw new IllegalArgumentException("ETF basic technical timestamp must remain the legacy epoch sentinel");
            return Json.write(List.of(Objects.requireNonNull(row.get("ts_code"),"key"),"1970-01-01"));
        }
        return Json.write(columns.stream().filter(Column::key).map(c -> Objects.requireNonNull(row.get(c.target()),"key")).toList());
    }
    public String createTableSql(String table) {
        requireTarget(table);
        return "CREATE TABLE IF NOT EXISTS "+table+" ("+String.join(",",columns.stream().map(c -> quoteIdentifier(c.target())+" "+c.type()).toList())
                +") TIMESTAMP("+quoteIdentifier(timestampColumn)+") PARTITION BY "+partitionBy+" WAL DEDUP UPSERT KEYS("
                +String.join(",",columns.stream().filter(Column::key).map(c -> quoteIdentifier(c.target())).toList())+")";
    }
    private static String quoteIdentifier(String value) { return "\""+value.replace("\"","\"\"")+"\""; }
    public static void requireTarget(String table) {
        if(table==null || !table.matches("jdb_test_[a-z0-9_]{1,115}")) throw new IllegalArgumentException("Isolated target required");
    }
}
