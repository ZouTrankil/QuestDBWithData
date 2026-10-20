package com.zoutrankil.data.strategy.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import com.zoutrankil.data.derived.storage.BseLegacyFactorAliases;
import com.zoutrankil.data.strategy.score.FrozenScalarValues;
import com.zoutrankil.data.strategy.score.L2StockScoreProducer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** 完整读取有界来源，并核验表结构、WAL 身份稳定性和交易日历证据。 */
public final class QuestDbStrategyInputs {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(QuestDbStrategyInputs.class);
    public record Snapshot(LocalDate start,LocalDate end,List<LocalDate> signalDates,
            Map<String,List<Map<String,Object>>> tables,Map<String,String> sourceVersions,
            Map<String,Object> proof) {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<String> TABLES=List.of("mv_backtest_daily","daily","daily_basic","stock_detail_info","l2_daily_features","etf_basic","etf_factor","etf_daily","etf_portfolio","market_sentiment_daily","regime_features_monitor_daily","exchange_calendar");
    private static final List<String> L2_SCORE_COLUMNS=List.of("ts","symbol","clean_amount","q2_count","q2_accumulation","q2_accumulation_ratio","active_buy_ratio","gmm_main_force_net_inflow","obi_top5_std","orderbook_replenish_speed","depth_slope","continuous_auction_ratio","close_auction_net_inflow","open_close_vol_ratio","q3_count","q4_count","q4_distribution","q4_distribution_ratio","active_sell_ratio","large_active_sell_amount","depth_top5_mean","ofi_persistence","ofi_std","mfi_escort","mean_obi_top5","main_funds_buy_amount","daily_amount","amount","turnover_rate");
    private final JdbcTemplate jdbc;
    private final long rowBudget;
    private final String coverageFile,coverageSha256;
    private final ThreadLocal<ReadBudget> activeRead=new ThreadLocal<>();
    private static final class ReadBudget {
        final long maximum;long rows;
        final Map<LocalDate,LocalDate> dates=new HashMap<>();final Map<String,String> symbols=new HashMap<>();
        ReadBudget(long maximum){this.maximum=maximum;}
        void accept(){if(++rows>maximum)throw new IllegalStateException("Aggregate strategy source row budget exceeded before materialization");}
        Object canonical(String column,Object value){
            if(value instanceof LocalDate day&&dates.size()<16_384)return dates.computeIfAbsent(day,key->key);
            if(value instanceof String text&&Set.of("ts_code","symbol","basic_present").contains(column)&&symbols.size()<32_768)return symbols.computeIfAbsent(text,key->key);
            return value;
        }
    }
    public QuestDbStrategyInputs(JdbcTemplate jdbc,long rowBudget) { this(jdbc,rowBudget,"",""); }
    public QuestDbStrategyInputs(JdbcTemplate jdbc,long rowBudget,String coverageFile,String coverageSha256) {
        this.coverageFile=coverageFile;this.coverageSha256=coverageSha256;
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setFetchSize(2048);this.jdbc.setQueryTimeout(120);
        if(rowBudget<10_000||rowBudget>5_000_000)throw new IllegalArgumentException("Strategy source budget must be 10000..5000000");this.rowBudget=rowBudget;
    }
    public Snapshot read(LocalDate start,LocalDate end,int warmupDays)throws Exception {
        return read(start,end,warmupDays,new StrategySourceCoverage(coverageFile,coverageSha256));
    }
    public Snapshot read(LocalDate start,LocalDate end,int warmupDays,StrategySourceCoverage coverage)throws Exception {
        if(activeRead.get()!=null)throw new IllegalStateException("Nested source snapshot read is forbidden");
        Objects.requireNonNull(coverage);
        activeRead.set(new ReadBudget(Math.multiplyExact(rowBudget,3)));
        try{return readSnapshot(start,end,warmupDays,coverage);}finally{activeRead.remove();}
    }
    private Snapshot readSnapshot(LocalDate start,LocalDate end,int warmupDays,StrategySourceCoverage coverage)throws Exception {
        if(start==null||end==null||end.isBefore(start)||end.toEpochDay()-start.toEpochDay()>730||warmupDays<460||warmupDays>730)throw new IllegalArgumentException("Bounded signal span and >=460 calendar-day warmup required");
        LocalDate warmup=start.minusDays(warmupDays);
        var before=clocks();var backtestPort=new BacktestDailyMaterializedViewPort(jdbc);Object backtest=backtestPort.sourceState();
        List<LocalDate> dates=calendar(start,end),warmupGrid=calendar(warmup,end);
        if(dates.isEmpty()||!dates.getLast().equals(end))throw new IllegalStateException("Score target must be an open calendar date");
        var data=new LinkedHashMap<String,List<Map<String,Object>>>();
        calendar(start,end.plusDays(14));
        data.put("calendar",readSql("SELECT exchange,cal_date,is_open,pretrade_date FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date",new Object[]{micros(start),micros(end.plusDays(15))},"calendar",1500));
        data.put("market_context",readRows("SELECT * FROM regime_features_monitor_daily WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date",start,end,"market_context"));
        data.put("stock_daily",readRows("""
            SELECT b.trade_date,b.ts_code,b.close AS price,b.open AS open_price,b.close AS raw_close,
                   b.amount,b.up_limit,b.down_limit,b.is_suspended,b.is_st,
                   d.pb,d.turnover_rate,d.total_mv,d.ts_code AS basic_present
            FROM mv_backtest_daily b LEFT JOIN daily_basic d ON b.trade_date=d.trade_date AND b.ts_code=d.ts_code
            WHERE b.trade_date>=cast(? AS TIMESTAMP) AND b.trade_date<cast(? AS TIMESTAMP)
            ORDER BY b.trade_date,b.ts_code
            """,warmup,end,"stock_daily"));
        var stockScopes=new LinkedHashMap<LocalDate,BseLegacyFactorAliases.Scope>();
        for(LocalDate lower=warmup;!lower.isAfter(end);){
            LocalDate upper=lower.withDayOfMonth(1).plusMonths(1);if(upper.isAfter(end.plusDays(1)))upper=end.plusDays(1);
            stockScopes.put(lower.withDayOfMonth(1),BseLegacyFactorAliases.prepare(jdbc,lower,upper,()->activeRead.get().accept()));
            lower=upper;
        }
        var canonicalStocks=canonicalStockRows(data.get("stock_daily"),stockScopes);
        data.put("stock_daily",canonicalStocks.rows());
        validateStockRows(data.get("stock_daily"));
        data.put("l2_raw",readRows("SELECT "+l2Projection(before)+" FROM l2_daily_features WHERE ts>=cast(? AS TIMESTAMP) AND ts<cast(? AS TIMESTAMP) ORDER BY ts,symbol",start,end,"l2_raw"));
        data.put("stock_metadata",readStatic("SELECT ts_code,name,industry,market,list_date,delist_date,list_status FROM stock_detail_info ORDER BY ts_code","stock_metadata",10_000));
        var quotedKeys=marketKeys(data.get("stock_daily"),"trade_date","ts_code");
        var missingSymbols=new TreeSet<String>();
        for(var row:data.get("l2_raw")){
            String symbol=Objects.toString(row.get("symbol"),"");
            if(L2StockScoreProducer.tradable(symbol)&&!quotedKeys.contains(new MarketKey(date(row.get("ts")),symbol)))missingSymbols.add(symbol);
        }
        List<Map<String,Object>> observed=List.of();
        if(!missingSymbols.isEmpty()){
            for(String symbol:missingSymbols)if(!symbol.matches("[0-9]{6}\\.(SH|SZ)"))throw new IllegalStateException("Malformed L2 stock scope symbol");
            String symbols=missingSymbols.stream().map(symbol->"'"+symbol+"'").collect(java.util.stream.Collectors.joining(","));
            observed=readRows("SELECT trade_date,ts_code FROM daily WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) AND ts_code IN ("+symbols+") ORDER BY trade_date,ts_code",start,end,"l2_observed_market_keys");
        }
        var joinAudit=verifyMarketJoin(quotedKeys,data.get("l2_raw"),data.get("stock_metadata"),observed);
        data.put("etf_reference",readStatic("SELECT * FROM etf_basic ORDER BY ts_code","etf_reference",10_000));
        data.put("etf_factor",readRows("SELECT * FROM etf_factor WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code",warmup,end,"etf_factor"));
        data.put("etf_daily",readRows("SELECT * FROM etf_daily WHERE timestamp>=cast(? AS TIMESTAMP) AND timestamp<cast(? AS TIMESTAMP) ORDER BY timestamp,ts_code",warmup.minusDays(5),end,"etf_daily"));
        // 保留区间前最后一次披露和区间内全部公告，精确时点筛选留给评分器。
        var portfolios=new ArrayList<Map<String,Object>>(readSql("""
            SELECT * FROM (SELECT p.* FROM etf_portfolio p
            JOIN (SELECT ts_code,max(ann_date) AS a FROM etf_portfolio WHERE ann_date<cast(? AS TIMESTAMP) GROUP BY ts_code) prior
            ON p.ts_code=prior.ts_code AND p.ann_date=prior.a)
            ORDER BY p.ts_code,p.ann_date,p.end_date,p.symbol
            """,new Object[]{micros(start)},"etf_portfolio",rowBudget));
        portfolios.addAll(readRows("SELECT * FROM etf_portfolio WHERE ann_date>=cast(? AS TIMESTAMP) AND ann_date<cast(? AS TIMESTAMP) ORDER BY ann_date,ts_code,end_date,symbol",start,end,"etf_portfolio",rowBudget-portfolios.size()));
        portfolios.sort(Comparator.comparing((Map<String,Object> row)->Objects.toString(row.get("ts_code"),""))
                .thenComparing(row->date(row.get("ann_date"))).thenComparing(row->date(row.get("end_date")))
                .thenComparing(row->Objects.toString(row.get("symbol"),"")));
        data.put("etf_portfolio",List.copyOf(portfolios));
        data.put("sentiment",readRows("SELECT * FROM market_sentiment_daily WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date",start,end,"sentiment"));
        requireGrid(data.get("stock_daily"),"trade_date",warmupGrid,"stock daily warmup");
        // L2 是稀疏输入，缺行与合法空范围由独立覆盖清单区分。
        requireGrid(data.get("etf_factor"),"trade_date",warmupGrid,"ETF factor warmup");
        requireGrid(data.get("etf_daily"),"timestamp",warmupGrid,"ETF daily warmup",warmup);
        requireGrid(data.get("sentiment"),"trade_date",dates,"market sentiment");
        requireGrid(data.get("market_context"),"trade_date",dates,"market regime context");
        unique(data.get("stock_daily"),List.of("trade_date","ts_code"),"stock_daily");
        unique(data.get("l2_raw"),List.of("ts","symbol"),"l2_raw");
        unique(data.get("etf_factor"),List.of("trade_date","ts_code"),"etf_factor");
        unique(data.get("etf_daily"),List.of("timestamp","ts_code"),"etf_daily");
        unique(data.get("etf_portfolio"),List.of("ann_date","ts_code","end_date","symbol"),"etf_portfolio");
        unique(data.get("sentiment"),List.of("trade_date"),"sentiment");
        unique(data.get("market_context"),List.of("trade_date"),"market_context");
        if(!before.equals(clocks())||!backtest.equals(backtestPort.sourceState()))throw new IllegalStateException("Strategy sources changed during the complete read");
        var versions=new LinkedHashMap<String,String>();
        var proofs=new LinkedHashMap<String,Object>();proofs.put("producer","java.main_strategy.inputs");proofs.put("start",start.toString());proofs.put("end",end.toString());proofs.put("warmupStart",warmup.toString());proofs.put("signalDates",dates.stream().map(LocalDate::toString).toList());proofs.put("sourceClocks",before);proofs.put("backtestState",backtest);
        proofs.put("stockDailyBasicPolicy",Map.of("missingOnlyForExplicitlySuspendedRows",true,"suspendedRowsWithoutDailyBasic",data.get("stock_daily").stream().filter(row->row.get("basic_present")==null).count(),
                "missingLimitsOnlyForExplicitlySuspendedRows",true,"suspendedRowsWithoutLimits",data.get("stock_daily").stream().filter(row->row.get("up_limit")==null||row.get("down_limit")==null).count()));
        proofs.put("canonicalStockUniverse",Map.of("identity",BseLegacyFactorAliases.identity(),"excludedDuplicateLegacyRows",canonicalStocks.excluded(),
                "monthAudits",stockScopes.values().stream().map(BseLegacyFactorAliases.Scope::evidence).toList(),"rawRowsDeleted",false));
        proofs.put("l2MarketJoinPolicy",Map.of("policy","original_python_nullable_market_left_join_v1",
                "nullableKeys",joinAudit.size(),"nullableKeySha256",hashRows(joinAudit),"requiredObservedQuoteKeysMissing",0,
                "requiredQuotedStockMetadataKeysMissing",0,"rawDailyProbeRows",observed.size(),"pricesOrSuspensionFlagsInvented",false));
        proofs.put("coverage",coverage.verify(start,end,warmup,data,before));
        var history=coverage.history();
        data.put("stock_metadata",history.metadata(data.get("l2_raw"),data.get("calendar")));
        data.put("market_context",history.context(data.get("market_context"),data.get("calendar")));
        history.revalidate();proofs.put("pointInTimeSha256",history.sha256());
        var rows=new LinkedHashMap<String,Object>();
        for(var e:data.entrySet()){String hash=hashRows(e.getValue());versions.put(e.getKey(),hash);rows.put(e.getKey(),Map.of("rows",e.getValue().size(),"sha256",hash));LOG.info("Verified complete strategy source digest: {}; rows={}",e.getKey(),e.getValue().size());}
        proofs.put("sources",rows);proofs.put("sourceComplete",true);
        return new Snapshot(start,end,List.copyOf(dates),Collections.unmodifiableMap(data),Map.copyOf(versions),Collections.unmodifiableMap(proofs));
    }
    public Map<String,Object> clocks()throws Exception {
        var result=new TreeMap<String,Object>();
        for(String table:TABLES) {
            var rows=jdbc.queryForList("SELECT t.id,t.directoryName,t.walEnabled,t.table_txn,t.table_suspended,w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize FROM tables() t LEFT JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name=?",table);
            if(rows.size()!=1)throw new IllegalStateException("Exact strategy table absent: "+table);
            var card=new TreeMap<String,Object>(rows.getFirst());
            if(Boolean.FALSE.equals(card.get("walEnabled")))pinOrdinaryReference(table,card);
            verifyClock(table,card);
            card.put("schema",jdbc.queryForList("SELECT \"column\",\"type\" FROM table_columns('"+table+"') LIMIT 257"));result.put(table,card);
        }return result;
    }
    private void pinOrdinaryReference(String table,Map<String,Object> card)throws Exception {
        if(!"stock_detail_info".equals(table))throw new IllegalStateException("Unregistered ordinary strategy reference: "+table);
        // tables().table_txn is an optional tracker metric, not the current reader
        // transaction. Bind a fresh, bounded committed read and its content instead.
        Timestamp anchor=jdbc.queryForObject("SELECT now()",Timestamp.class);
        if(anchor==null)throw new IllegalStateException("Database clock unavailable before reference read");
        var reference=readStatic("SELECT * FROM stock_detail_info ORDER BY ts_code","stock_reference_clock",10_000);
        unique(reference,List.of("ts_code"),"stock_reference_clock");
        long micros=Math.addExact(Math.multiplyExact(anchor.toInstant().getEpochSecond(),1_000_000L),anchor.toInstant().getNano()/1_000);
        var readers=jdbc.queryForList("SELECT current_txn FROM reader_pool() WHERE table_name=? AND last_access_timestamp>=cast(? AS TIMESTAMP)",table,micros);
        var versions=new TreeSet<Long>();
        for(var row:readers){if(!(row.get("current_txn") instanceof Number txn)||txn.longValue()<0)throw new IllegalStateException("Ordinary reference reader has no committed transaction: "+table);versions.add(txn.longValue());}
        if(versions.size()!=1)throw new IllegalStateException("Fresh ordinary reference readers disagree or are unavailable: "+table);
        var after=jdbc.queryForList("SELECT id,directoryName,walEnabled,table_suspended FROM tables() WHERE table_name=?",table);
        if(after.size()!=1)throw new IllegalStateException("Ordinary reference disappeared during read: "+table);
        for(String field:List.of("id","directoryName","walEnabled","table_suspended"))if(!Objects.equals(card.get(field),after.getFirst().get(field)))throw new IllegalStateException("Ordinary reference identity changed during read: "+table);
        card.remove("table_txn");
        card.put("readerTxn",versions.first());card.put("referenceRows",reference.size());card.put("referenceSha256",hashRows(reference));
        card.put("referenceTransactionPolicy","fresh_committed_sql_reader_and_content_v1");
    }
    static void verifyClock(String table,Map<String,Object> r) {
        if(!(r.get("id") instanceof Number n)||n.longValue()<0||Objects.toString(r.get("directoryName"),"").isBlank())throw new IllegalStateException("Physical strategy source identity absent: "+table);
        if(Boolean.TRUE.equals(r.get("walEnabled"))){
            if(!Boolean.FALSE.equals(r.get("suspended"))||!(r.get("writerTxn") instanceof Number a)||!(r.get("sequencerTxn") instanceof Number b)||a.longValue()!=b.longValue()||!(r.get("bufferedTxnSize") instanceof Number pending)||pending.longValue()!=0)throw new IllegalStateException("Strategy source WAL unsettled: "+table);
        }else if(!Boolean.FALSE.equals(r.get("walEnabled"))||!Boolean.FALSE.equals(r.get("table_suspended"))
                ||!(r.get("readerTxn") instanceof Number txn)||txn.longValue()<0
                ||!(r.get("referenceRows") instanceof Number count)||count.longValue()<1||count.longValue()>10_000
                ||!Objects.toString(r.get("referenceSha256"),"").matches("[a-f0-9]{64}")
                ||!"fresh_committed_sql_reader_and_content_v1".equals(r.get("referenceTransactionPolicy"))
                ||r.get("writerTxn")!=null||r.get("sequencerTxn")!=null)throw new IllegalStateException("Non-WAL source lacks a fresh committed reader/content identity: "+table);
    }
    private List<LocalDate> calendar(LocalDate from,LocalDate to)throws Exception {
        long days=to.toEpochDay()-from.toEpochDay()+1;if(days<1||days>1500)throw new IllegalArgumentException("Bounded natural day calendar required");
        var raw=readSql("SELECT cal_date,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date",new Object[]{micros(from),micros(to.plusDays(1))},"calendar",1500);
        if(raw.size()!=days)throw new IllegalStateException("Calendar has missing natural days: "+from+".."+to);
        var result=new ArrayList<LocalDate>();LocalDate current=from;
        for(var row:raw){LocalDate d=date(row.get("cal_date"));if(!d.equals(current))throw new IllegalStateException("Calendar dates duplicate or incomplete");Object open=row.get("is_open");if(!(open instanceof Number n)||n.intValue()<0||n.intValue()>1)throw new IllegalStateException("Typed open flag required");if(n.intValue()==1)result.add(d);current=current.plusDays(1);}return result;
    }
    record DateRange(LocalDate from,LocalDate exclusiveEnd) {}
    static List<DateRange> monthRanges(LocalDate from,LocalDate inclusiveEnd) {
        if(from==null||inclusiveEnd==null||inclusiveEnd.isBefore(from))throw new IllegalArgumentException("Increasing source date interval required");
        var ranges=new ArrayList<DateRange>();LocalDate stop=inclusiveEnd.plusDays(1);
        for(LocalDate lower=from;lower.isBefore(stop);){
            LocalDate upper=lower.withDayOfMonth(1).plusMonths(1);if(upper.isAfter(stop))upper=stop;
            ranges.add(new DateRange(lower,upper));lower=upper;
        }
        return List.copyOf(ranges);
    }
    private List<Map<String,Object>> readRows(String sql,LocalDate from,LocalDate to,String name)throws Exception {
        return readRows(sql,from,to,name,rowBudget);
    }
    private List<Map<String,Object>> readRows(String sql,LocalDate from,LocalDate to,String name,long budget)throws Exception {
        // PG may buffer an entire result even with fetchSize. Bound that buffer while
        // preserving the complete ordered date range and the original total row limit.
        var rows=new ArrayList<Map<String,Object>>();
        for(var range:monthRanges(from,to)){
            rows.addAll(readSql(sql,new Object[]{micros(range.from()),micros(range.exclusiveEnd())},name,budget-rows.size()));
            LOG.info("Read complete strategy source chunk: {} {}..{}; cumulative rows={}",name,range.from(),range.exclusiveEnd(),rows.size());
        }
        return List.copyOf(rows);
    }
    private List<Map<String,Object>> readStatic(String sql,String name,long budget)throws Exception{return readSql(sql,new Object[0],name,budget);}
    private List<Map<String,Object>> readSql(String sql,Object[] params,String name,long budget)throws Exception {
        var rows=new ArrayList<Map<String,Object>>();var layout=new FrozenScalarValues.Layout[1];
        jdbc.query(sql+" LIMIT "+(budget+1),(RowCallbackHandler)rs->{
            if(rows.size()>=budget)throw new IllegalStateException("Complete source exceeds row budget: "+name);
            ReadBudget aggregate=activeRead.get();if(aggregate!=null)aggregate.accept();
            ResultSetMetaData metadata=rs.getMetaData();
            if(layout[0]==null){var columns=new ArrayList<String>();for(int i=1;i<=metadata.getColumnCount();i++)columns.add(metadata.getColumnLabel(i));layout[0]=FrozenScalarValues.layout(columns);}
            var values=new Object[metadata.getColumnCount()];
            for(int i=1;i<=metadata.getColumnCount();i++) {String column=metadata.getColumnLabel(i);Object value=rs.getObject(i);if(value instanceof Timestamp ts){var instant=ts.toInstant();if(Set.of("trade_date","ts","timestamp","ann_date","end_date","cal_date","pretrade_date").contains(column)){if(!instant.atZone(ZoneOffset.UTC).toLocalTime().equals(LocalTime.MIDNIGHT))throw new IllegalStateException("Business date must be UTC midnight: "+name+"."+column);value=instant.atZone(ZoneOffset.UTC).toLocalDate();}else value=instant.toString();}else if(value instanceof java.sql.Date d)value=d.toLocalDate();else if(value instanceof Double d&&Double.isNaN(d))value=null;else if(value instanceof Float f&&Float.isNaN(f))value=null;else if(value instanceof Number n&&!Double.isFinite(n.doubleValue()))throw new IllegalStateException("Infinite source number: "+name);values[i-1]=value;}
            if(aggregate!=null)for(int i=0;i<values.length;i++)values[i]=aggregate.canonical(metadata.getColumnLabel(i+1),values[i]);
            rows.add(FrozenScalarValues.row(layout[0],values));
        },params);return List.copyOf(rows);
    }
    public void revalidateCoverage()throws Exception { new StrategySourceCoverage(coverageFile,coverageSha256); }
    public Object backtestState(){return new BacktestDailyMaterializedViewPort(jdbc).sourceState();}
    record MarketKey(LocalDate day,String symbol) {}
    static Set<MarketKey> marketKeys(List<Map<String,Object>> rows,String day,String symbol){
        var keys=new HashSet<MarketKey>();for(var row:rows)keys.add(new MarketKey(date(row.get(day)),Objects.toString(row.get(symbol),"")));return keys;
    }
    static List<Map<String,Object>> verifyMarketJoin(Set<MarketKey> quoted,List<Map<String,Object>> l2,
            List<Map<String,Object>> metadata,List<Map<String,Object>> observed) {
        var stockMetadata=new HashMap<String,Map<String,Object>>();for(var row:metadata)stockMetadata.put(Objects.toString(row.get("ts_code"),""),row);
        var actualQuotes=marketKeys(observed,"trade_date","ts_code");var nullable=new ArrayList<Map<String,Object>>();
        for(var row:l2){
            String symbol=Objects.toString(row.get("symbol"),"");if(!L2StockScoreProducer.tradable(symbol))continue;
            LocalDate day=date(row.get("ts"));var key=new MarketKey(day,symbol);var meta=stockMetadata.get(symbol);
            if(quoted.contains(key)){
                if(meta==null)throw new IllegalStateException("Stock metadata is missing for an observed quoted L2 key: "+key);
            }else{
                if(actualQuotes.contains(key))throw new IllegalStateException("Canonical market price is missing for an observed stock daily key: "+key);
                String reason="NO_OBSERVED_STOCK_DAILY";
                if(meta==null)reason="NO_STOCK_METADATA_OR_DAILY";
                else{
                    LocalDate listed=optionalListingDate(meta.get("list_date")),delisted=optionalListingDate(meta.get("delist_date"));
                    if(listed!=null&&day.isBefore(listed))reason="PRE_LISTING";
                    else if("D".equals(meta.get("list_status"))&&delisted!=null&&!day.isBefore(delisted))reason="POST_DELISTING";
                }
                nullable.add(Map.of("trade_date",day,"symbol",symbol,"reason",reason));
            }
        }
        return List.copyOf(nullable);
    }
    private static LocalDate optionalListingDate(Object value){
        String text=Objects.toString(value,"");
        if(!text.matches("[0-9]{8}"))return null;
        return LocalDate.parse(text,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }
    record CanonicalStockRows(List<Map<String,Object>> rows,int excluded) {
        CanonicalStockRows {rows=List.copyOf(rows);}
    }
    /** Keep every canonical security; a legacy copy needs both a proven raw alias and its exact MV counterpart. */
    static CanonicalStockRows canonicalStockRows(List<Map<String,Object>> rows,Map<LocalDate,BseLegacyFactorAliases.Scope> scopes) {
        var canonical=new HashMap<BseLegacyFactorAliases.Key,Map<String,Object>>();
        for(var row:rows){String symbol=Objects.toString(row.get("ts_code"),"");if(symbol.startsWith("920")&&symbol.endsWith(".BJ")){
            var key=new BseLegacyFactorAliases.Key(date(row.get("trade_date")),symbol);
            if(canonical.put(key,row)!=null)throw new IllegalStateException("Duplicate canonical BSE backtest key: "+key);
        }}
        var retained=new ArrayList<Map<String,Object>>();int excluded=0;
        for(var row:rows){LocalDate day=date(row.get("trade_date"));String symbol=Objects.toString(row.get("ts_code"),"");
            var scope=Objects.requireNonNull(scopes.get(day.withDayOfMonth(1)),"Missing monthly stock alias proof");
            if(scope.excludes(day,symbol)){
                String replacement=BseLegacyFactorAliases.canonicalSymbol(symbol);
                var counterpart=canonical.get(new BseLegacyFactorAliases.Key(day,Objects.requireNonNull(replacement,"Unmapped alias exclusion")));
                if(counterpart==null)throw new IllegalStateException("Canonical backtest counterpart absent: "+day+"/"+symbol);
                for(String field:List.of("price","open_price","amount"))if(!Objects.equals(row.get(field),counterpart.get(field)))
                    throw new IllegalStateException("BSE backtest alias values differ: "+day+"/"+symbol+"/"+field);
                excluded++;
            }else retained.add(row);
        }
        return new CanonicalStockRows(retained,excluded);
    }
    static void validateStockRows(List<Map<String,Object>> rows) {
        for(var row:rows) {
            boolean suspended=row.get("is_suspended") instanceof Number flag&&flag.doubleValue()==1.0;
            // The canonical MV carries the last observed price through a proven suspension.
            // daily_basic has no same-day row for those nontrading stocks; keep null auxiliary
            // values and the suspension flag, which excludes them from executable scores.
            if(row.get("basic_present")==null&&!suspended)throw new IllegalStateException("daily_basic lacks a stock business key: "+row.get("ts_code")+" "+row.get("trade_date"));
            for(String field:List.of("price","open_price","amount"))if(!(row.get(field) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalStateException("Stock critical value missing: "+field);
            for(String field:List.of("up_limit","down_limit")){
                Object value=row.get(field);if(suspended&&value==null)continue;
                if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalStateException("Stock limit value missing: "+field);
            }
        }
    }
    static String l2Projection(Map<String,Object> clocks) {
        if(!(clocks.get("l2_daily_features") instanceof Map<?,?> card)||!(card.get("schema") instanceof List<?> schema))throw new IllegalStateException("Pinned L2 schema required");
        var names=new HashSet<String>();for(Object column:schema)if(column instanceof Map<?,?> row)names.add(Objects.toString(row.get("column"),""));
        if(!names.containsAll(List.of("ts","symbol","clean_amount","q2_count","q2_accumulation","q3_count","q4_count","main_funds_buy_amount")))throw new IllegalStateException("Required formal L2 scoring schema is missing");
        return String.join(",",L2_SCORE_COLUMNS.stream().filter(names::contains).toList());
    }
    private static void requireGrid(List<Map<String,Object>> rows,String column,List<LocalDate> expected,String name)throws Exception {requireGrid(rows,column,expected,name,expected.getFirst());}
    private static void requireGrid(List<Map<String,Object>> rows,String column,List<LocalDate> expected,String name,LocalDate lower)throws Exception {
        var actual=new TreeSet<LocalDate>();for(var row:rows){LocalDate d=date(row.get(column));if(!d.isBefore(lower))actual.add(d);}
        if(!actual.equals(new TreeSet<>(expected)))throw new IllegalStateException("Source open-day grid incomplete: "+name+"; expected "+expected.size()+", observed "+actual.size());
    }
    private static void unique(List<Map<String,Object>> rows,List<String> keys,String name) {var found=new HashSet<List<Object>>();for(var row:rows){var key=new ArrayList<Object>();for(String k:keys){Object v=row.get(k);if(v==null)throw new IllegalStateException("Null source key: "+name+"."+k);key.add(v);}if(!found.add(key))throw new IllegalStateException("Duplicate source key: "+name+" "+key);}}
    static String hashRows(List<Map<String,Object>> rows)throws Exception {
        var md=MessageDigest.getInstance("SHA-256");var buffer=new java.io.ByteArrayOutputStream(1024);
        var output=new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),md);byte[] length=new byte[4];
        for(var row:rows){
            buffer.reset();
            var columns=row instanceof FrozenScalarValues frozen?frozen.sortedColumns():row.keySet().stream().sorted().toList();
            try(var json=JSON.getFactory().createGenerator(buffer)){
                json.writeStartObject();for(String column:columns){json.writeFieldName(column);writeScalar(json,row.get(column));}json.writeEndObject();
            }
            int size=buffer.size();for(int i=0;i<4;i++)length[i]=(byte)(size>>>(24-i*8));md.update(length);buffer.writeTo(output);
        }
        return HexFormat.of().formatHex(md.digest());
    }
    private static void writeScalar(com.fasterxml.jackson.core.JsonGenerator json,Object value)throws java.io.IOException {
        if(value==null)json.writeNull();else if(value instanceof String text)json.writeString(text);
        else if(value instanceof LocalDate day)json.writeString(day.toString());else if(value instanceof Double number)json.writeNumber(number);
        else if(value instanceof Float number)json.writeNumber(number);else if(value instanceof Long number)json.writeNumber(number);
        else if(value instanceof Integer number)json.writeNumber(number);else if(value instanceof Short number)json.writeNumber(number.intValue());
        else if(value instanceof Byte number)json.writeNumber(number.intValue());else if(value instanceof Boolean flag)json.writeBoolean(flag);
        else if(value instanceof java.math.BigDecimal number)json.writeNumber(number);else if(value instanceof java.math.BigInteger number)json.writeNumber(number);
        else json.writeObject(value);
    }
    public static LocalDate date(Object value) {if(value instanceof LocalDate d)return d;if(value instanceof Timestamp ts)return ts.toInstant().atZone(ZoneOffset.UTC).toLocalDate();if(value==null)throw new IllegalStateException("Typed source date is null");return LocalDate.parse(value.toString().substring(0,10));}
    private static long micros(LocalDate day){return Math.multiplyExact(day.toEpochDay(),86_400_000_000L);}
}
