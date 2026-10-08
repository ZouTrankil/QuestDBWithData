package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.PageContract;
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
    // Source month labels contain only numeric ISO fields, independent of the default locale.
    static final DateTimeFormatter BASIC_MONTH=DateTimeFormatter.ofPattern("yyyyMM",Locale.ROOT);
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
            var loaded=Json.MAPPER.readValue(stream,SourceContract.class);
            return dataset.equals("daily")?DailySourceContractAdapter.bind(loaded):loaded;
        } catch(java.io.IOException error) { throw new IllegalStateException("Unreadable source contract",error); }
    }
    private SourceStrategy strategy() { return SourceStrategies.require(dataset); }

    public boolean isMarketAggregate() { return strategy().coverage().isMarketAggregate(this); }
    public boolean validCode(String code) { return strategy().coverage().validCode(this,code); }
    public List<Column> businessColumns() { return strategy().storage().businessColumns(this); }
    public String logicalDateColumn() { return strategy().storage().logicalDateColumn(this); }
    public int writeBatchSize() { return strategy().storage().writeBatchSize(this); }
    public Map<String,Object> physicalRow(Map<String,Object> businessRow,Instant createdAt) {
        return strategy().storage().physicalRow(this,businessRow,createdAt);
    }
    public Set<String> observedCodes(List<Map<String,Object>> rows) {
        return strategy().coverage().observedCodes(this,rows);
    }
    public boolean covers(List<Map<String,Object>> rows,Set<String> expectedCodes) {
        return strategy().coverage().covers(this,rows,expectedCodes);
    }
    public PageContract pageContract() { return strategy().request().pageContract(this); }
    public PageContract providerContract() { return providerContract(null); }
    public PageContract providerContract(String code) { return strategy().request().providerContract(this,code); }
    public List<Map<String,JsonNode>> prepareRows(List<Map<String,JsonNode>> rows,LocalDate date,Set<String> expectedCodes) {
        return prepareRows(rows,date,expectedCodes,null);
    }
    public List<Map<String,JsonNode>> prepareRows(List<Map<String,JsonNode>> rows,LocalDate date,Set<String> expectedCodes,String providerCode) {
        return strategy().rows().prepareRows(this,rows,date,expectedCodes,providerCode);
    }
    public boolean ignoredFooter(Map<String,JsonNode> row,LocalDate date) {
        return strategy().rows().ignoredFooter(this,row,date);
    }
    public Map<String,Object> normalize(Map<String,JsonNode> row,LocalDate date,Set<String> expectedCodes) {
        return strategy().rows().normalize(this,row,date,expectedCodes);
    }
    public Map<String,Object> validateNormalized(Map<String,Object> row,LocalDate date,Set<String> expectedCodes) {
        return strategy().rows().validateNormalized(this,row,date,expectedCodes);
    }
    public String key(Map<String,Object> row) { return strategy().storage().key(this,row); }
    public String createTableSql(String table) { return strategy().storage().createTableSql(this,table); }
    public static void requireTarget(String table) {
        if(table==null || !table.matches("jdb_test_[a-z0-9_]{1,115}")) throw new IllegalArgumentException("Isolated target required");
    }
}
