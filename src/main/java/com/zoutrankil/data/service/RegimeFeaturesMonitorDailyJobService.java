package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.RegimeFeaturesMonitorDailyRow;
import com.zoutrankil.data.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;
import static com.zoutrankil.data.service.RegimeFeaturesMonitorDailyCalculation.*;
import static com.zoutrankil.data.service.MarketSentimentDailyCalculation.finite;

/** Explicit native Java owner for the existing 21-field proxy-style context table.
 * Reads existing QuestDB dependencies; no Python owner, additional source client,
 * provider request, or formal append. cn_bond_yield_curve is a pinned read-only
 * external table, not an invented registered/synchronized source owner.
 */
@Service
public final class RegimeFeaturesMonitorDailyJobService implements DatasetImplementation,SyncJobOwner {
    public static final String JOB_ID="data.regime_features_monitor_daily";
    public static final String PRODUCER="java.regime_features_monitor_daily";
    private static final String FORMAL="regime_features_monitor_daily",PREFIX="java_regime_features_monitor_daily";
    public static final List<String> SOURCES=List.of("stk_factor","daily_basic","stk_limit","stk_suspend","stk_st_daily","margin_detail","moneyflow_hsgt","cn_bond_yield_curve","exchange_calendar");
    // Only registered owners belong in registry dependencies. The gov curve is
    // still REQUIRED, schema-read, date-bounded, source-pinned, and fingerprinted.
    public static final List<String> REGISTERED_DEPENDENCIES=SOURCES.stream().filter(s->!s.equals("cn_bond_yield_curve")).toList();
    public record Plan(FrozenRequest request,String targetId,LocalDate warmupFrom,List<String> dependencies,String sourcePin,
                       NativeDailyWindowWritePort.Snapshot<RegimeFeaturesMonitorDailyRow> targetBefore) {}
    public record MaterializationResult(SyncJobRunner.Result result,int historyDates,long sourceRawRows,String sourceFingerprint,String fullTargetFingerprint,String evidence) {}
    private record Computed(List<RegimeFeaturesMonitorDailyRow> rows,int historyDates,long panelRows,long valuationRows,String fingerprint,
                            List<Map<String,Object>> panelCoverage,List<Map<String,Object>> marginAvailability,List<LocalDate> expectedDates,
                            List<LocalDate> ignoredGovDates,int northboundDates,int valuationDates,int govDates) {}
    private final JdbcTemplate jdbc;private final Path ledgerPath;private final String table;
    public RegimeFeaturesMonitorDailyJobService(JdbcTemplate jdbc,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger,
            @Value("${app.sync.regime-monitor-table:regime_features_monitor_daily}") String table){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(120);this.jdbc.setFetchSize(2048);
        ledgerPath=Path.of(ledger).toAbsolutePath().normalize();NativeDailyWindowWritePort.requireTarget(table,FORMAL,PREFIX);this.table=table;
    }
    private NativeDailyWindowWritePort<RegimeFeaturesMonitorDailyRow> port(){return new NativeDailyWindowWritePort<>(jdbc,table,FORMAL,PREFIX,RegimeFeaturesMonitorDailyRow.class,COLUMNS);}
    private NativeDailyWindowPublication<RegimeFeaturesMonitorDailyRow> publication(NativeDailyWindowWritePort<RegimeFeaturesMonitorDailyRow> port){return new NativeDailyWindowPublication<>(jdbc,ledgerPath,datasetId(),port);}
    @Override public String datasetId(){return FORMAL;}
    @Override public Set<Mode> supportedSyncModes(){return jobDefinition().supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(jobDefinition());}
    @Override public DatasetDefinition definition(){
        var columns=new ArrayList<DatasetDefinition.Column>();var components=RegimeFeaturesMonitorDailyRow.class.getRecordComponents();
        for(int i=0;i<components.length;i++){String name=COLUMNS.get(i);Class<?> type=components[i].getType();
            var storage=type==Instant.class?DatasetDefinition.StorageType.TIMESTAMP:type==Double.class?DatasetDefinition.StorageType.DOUBLE:DatasetDefinition.StorageType.SYMBOL;
            var temporal=type!=Instant.class?null:new DatasetDefinition.TemporalContract(i==0?DatasetDefinition.TemporalKind.BUSINESS_DATE:DatasetDefinition.TemporalKind.INSTANT,
                    "epochMicros",i==0?"calendar":"UTC","MICROS",i==0?"Trading date encoded at exact UTC midnight":"Native calculation observation instant");
            columns.add(new DatasetDefinition.Column("derived:"+name,name,name,storage,i!=0,"Legacy proxy-style field "+name,temporal));}
        return new DatasetDefinition(datasetId(),1,"derived.questdb",PRODUCER,table,DatasetDefinition.ObjectKind.TABLE,columns,List.of("trade_date"),List.of(),"trade_date",
                DatasetDefinition.Partition.MONTH,true,Set.of(DatasetDefinition.Capability.READ,DatasetDefinition.Capability.WAL_REPLACE),REGISTERED_DEPENDENCIES,
                "21 existing fields; bounded full-row window replacement retaining outside rows and backup. Warmup=min(month start,from-20 days); qcut size/PB proxy style, monthly compounds, rolling five observations, full warmup-window valuation ranks. Required input-package context fields are finite; optional incomplete-market margin is NULL with separate availability evidence, while the inherited data_quality_flag remains proxy_style. Existing gov/10Y cn_bond_yield_curve is a required pinned read-only external table, not a registered provider owner.");
    }
    public static SyncJobDefinition jobDefinition(){
        var parameters=new LinkedHashMap<String,Parameter>();
        parameters.put("target_id",new Parameter(ParameterType.STRING,true,128,1,Set.of()));parameters.put("source_pin",new Parameter(ParameterType.STRING,true,64,1,Set.of()));
        parameters.put("target_hash",new Parameter(ParameterType.STRING,true,64,1,Set.of()));parameters.put("warmup_from",new Parameter(ParameterType.DATE,true,10,1,Set.of()));
        return new SyncJobDefinition(JOB_ID,2,FORMAL,1,"regime_features_monitor_daily_owner",Set.of(Mode.MATERIALIZE),Mode.MATERIALIZE,
                parameters,"questdb.materialize","regime_features_monitor_daily.range366","questdb.full_key_values",
                new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(1)),Duration.ofMinutes(20),new Budget(366,1,1,366,1024*1024),0,List.of(),Frequency.MANUAL,ZoneId.of("Asia/Shanghai"),true,false);
    }
    public Plan plan(LocalDate from,LocalDate to,LocalDate logicalDate)throws Exception{return plan(from,to,logicalDate,Mode.MATERIALIZE);}
    public Plan plan(LocalDate from,LocalDate to,LocalDate logicalDate,Mode mode)throws Exception{
        if(mode!=Mode.MATERIALIZE)throw new IllegalArgumentException("Native regime-monitor owner supports MATERIALIZE; publication recovery uses the explicit finish command");
        if(from==null||to==null||logicalDate==null||from.isAfter(to)||to.isAfter(logicalDate)||ChronoUnit.DAYS.between(from,to)>=366)
            throw new IllegalArgumentException("Explicit bounded from/to <= logicalDate, at most 366 calendar days required");
        requireNoPendingPublication();var before=port().formalSnapshot();String pin=sourcePin();LocalDate warmup=warmupFrom(from);
        var request=jobDefinition().freeze(mode,Map.of("target_id",before.targetId(),"source_pin",pin,"target_hash",before.fingerprint(),"warmup_from",warmup),from,to,logicalDate);
        return new Plan(request,before.targetId(),warmup,SOURCES,pin,before);
    }
    public MaterializationResult run(Plan plan)throws Exception{
        if(plan==null||!plan.request.definition().equals(jobDefinition())||!plan.sourcePin.equals(plan.request.parameters().get("source_pin"))
                ||!plan.targetId.equals(plan.request.parameters().get("target_id"))||!plan.targetBefore.targetId().equals(plan.targetId)
                ||!plan.targetBefore.fingerprint().equals(plan.request.parameters().get("target_hash"))||!plan.warmupFrom.equals(warmupFrom(plan.request.from())))
            throw new IllegalArgumentException("Frozen native regime-monitor plan required");
        String run="regime-monitor-"+UUID.randomUUID();var write=port();var adapter=new Adapter(plan,run,write);
        var runner=new SyncJobRunner<RegimeFeaturesMonitorDailyRow,Instant>(new SyncRunLedger(ledgerPath),new DatasetIntervalLock(ledgerPath));
        var result=runner.run(run,null,plan.targetId,plan.request,adapter,()->Thread.currentThread().isInterrupted());
        return new MaterializationResult(result,adapter.historyDates,adapter.rawRows,adapter.sourceFingerprint,adapter.finalFingerprint,adapter.evidence==null?null:adapter.evidence.toString());
    }
    private final class Adapter implements SyncJobRunner.Adapter<RegimeFeaturesMonitorDailyRow,Instant>{
        final Plan plan;final String run;final NativeDailyWindowWritePort<RegimeFeaturesMonitorDailyRow> write;int historyDates;long rawRows;
        String sourceFingerprint,finalFingerprint;Path evidence;
        Adapter(Plan plan,String run,NativeDailyWindowWritePort<RegimeFeaturesMonitorDailyRow> write){this.plan=plan;this.run=run;this.write=write;}
        public void preflight(FrozenRequest request)throws Exception{requireNoPendingPublication();write.requireSame(plan.targetBefore);if(!sourcePin().equals(plan.sourcePin))throw new IllegalStateException("Source changed since planning");}
        public DatasetIntervalLock.Scope conflictScope(FrozenRequest request){return DatasetIntervalLock.Scope.allDates(datasetId());}
        public VerifiedBatchExecutor.Codec<RegimeFeaturesMonitorDailyRow,Instant> codec(){return write.codec();}
        public VerifiedBatchExecutor.Port<RegimeFeaturesMonitorDailyRow,Instant> port(){return write;}
        public boolean recoveryRequired(String ignored)throws Exception{return publication(write).findForRun(run).isPresent();}
        public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<RegimeFeaturesMonitorDailyRow> consumer,BooleanSupplier cancelled)throws Exception{
            check(cancelled);var source=calculate(plan,cancelled);historyDates=source.historyDates;rawRows=source.panelRows;sourceFingerprint=source.fingerprint;
            evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(run).resolve("source.json");Files.createDirectories(evidence.getParent());
            writeEvidence(evidence,Map.ofEntries(Map.entry("producer",PRODUCER),Map.entry("algorithmVersion",ALGORITHM_VERSION),Map.entry("warmupFrom",plan.warmupFrom),
                    Map.entry("from",request.from()),Map.entry("to",request.to()),Map.entry("historyDates",historyDates),Map.entry("sourceRawRows",rawRows),Map.entry("valuationRawRows",source.valuationRows),
                    Map.entry("sourceFingerprint",sourceFingerprint),Map.entry("sources",SOURCES),Map.entry("externalReadOnlySources",List.of("cn_bond_yield_curve")),
                    Map.entry("panelCoverage",source.panelCoverage),Map.entry("marginAvailability",source.marginAvailability),Map.entry("expectedSseTradingDates",source.expectedDates),
                    Map.entry("requiredContextFields",REQUIRED_CONTEXT_FIELDS),Map.entry("qualityPolicy","proxy_style describes stock-bucket proxies; optional partial-market margin is null and separately evidenced"),
                    Map.entry("northboundObservedDates",source.northboundDates),Map.entry("valuationObservedDates",source.valuationDates),Map.entry("gov10YObservedDates",source.govDates),
                    Map.entry("ignoredGovNonValuationDates",source.ignoredGovDates),Map.entry("rows",source.rows)));
            if(!sourcePin().equals(plan.sourcePin))throw new IllegalStateException("Dependency source changed during calculation");
            write.prepare(plan.targetBefore,request.from(),request.to());consumer.accept(new SyncJobRunner.Page<>(source.rows,sourceFingerprint,evidence.toString(),null));
            check(cancelled);if(!sourcePin().equals(plan.sourcePin))throw new IllegalStateException("Dependency source changed before publication");
            var locks=new DatasetIntervalLock(ledgerPath);var lease=locks.findOwned(run,DatasetIntervalLock.Scope.allDates(datasetId()));
            if(lease==null)throw new IllegalStateException("Whole regime-monitor dataset lease missing");
            var published=publication(write).publish(run,lease,plan.targetBefore,request.from(),request.to(),source.rows,
                    Map.of("sourceEvidence",evidence.toString(),"sourceEvidenceSha256",fileHash(evidence),"sourceFingerprint",sourceFingerprint),cancelled);
            finalFingerprint=published.published().fingerprint();
            return new SyncJobRunner.SourceCompletion(1,source.rows.size(),true,published.evidence().toString());
        }
    }
    private Computed calculate(Plan plan,BooleanSupplier cancelled)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");var calendar=calendar(plan.warmupFrom,plan.request.to(),hash);
        var expectedOutput=new TreeSet<>(calendar.subSet(plan.request.from(),true,plan.request.to(),true));
        if(expectedOutput.isEmpty())throw new IllegalStateException("Requested window has no authoritative SSE trading dates");
        var stockDays=new ArrayList<Day>();var observedPanelDates=new TreeSet<LocalDate>();var panelCoverage=new ArrayList<Map<String,Object>>();long[] panelRows={0},valuationRows={0};
        var valuations=new TreeMap<LocalDate,Valuation>();
        for(LocalDate lower=plan.warmupFrom;!lower.isAfter(plan.request.to());){
            check(cancelled);LocalDate upper=lower.withDayOfMonth(1).plusMonths(1);if(upper.isAfter(plan.request.to().plusDays(1)))upper=plan.request.to().plusDays(1);
            LocalDate[] date={null};var stocks=new ArrayList<Stock>();var codes=new HashSet<String>();int[] basicCount={0},limitCount={0},count={0};
            String sql="SELECT cast(sf.trade_date AS long) AS trade_micros,sf.ts_code,sf.close,sf.pre_close,sf.amount,db.turnover_rate,db.total_mv,db.pb,db.pe_ttm,sl.up_limit,sl.down_limit,ss.is_suspended,st.is_st,db.ts_code AS basic_code,sl.ts_code AS limit_code"
                    +" FROM "+bounded("stk_factor","trade_date,ts_code,close,pre_close,amount","trade_date")+" sf"
                    +" LEFT JOIN "+bounded("daily_basic","trade_date,ts_code,turnover_rate,total_mv,pb,pe_ttm","trade_date")+" db ON sf.ts_code=db.ts_code AND sf.trade_date=db.trade_date"
                    +" LEFT JOIN "+bounded("stk_limit","trade_date,ts_code,up_limit,down_limit","trade_date")+" sl ON sf.ts_code=sl.ts_code AND sf.trade_date=sl.trade_date"
                    +" LEFT JOIN "+bounded("stk_suspend","timestamp,ts_code,is_suspended","timestamp")+" ss ON sf.ts_code=ss.ts_code AND sf.trade_date=ss.timestamp"
                    +" LEFT JOIN "+bounded("stk_st_daily","timestamp,ts_code,is_st","timestamp")+" st ON sf.ts_code=st.ts_code AND sf.trade_date=st.timestamp"
                    +" ORDER BY sf.trade_date,sf.ts_code LIMIT 200001";
            jdbc.query(sql,(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                if(++count[0]>200000)throw new IllegalStateException("Stock source month exceeds 200000-row budget");if(count[0]==1||(count[0]&1023)==0)check(cancelled);panelRows[0]++;
                LocalDate current=date(rs);if(!calendar.contains(current))throw new IllegalStateException("Stock panel contains non-SSE-trading date "+current);
                if(date[0]!=null&&!date[0].equals(current)){finishPanelDay(date[0],stocks,codes,basicCount[0],limitCount[0],stockDays,observedPanelDates,panelCoverage);stocks.clear();codes.clear();basicCount[0]=limitCount[0]=0;}
                date[0]=current;String code=rs.getString("ts_code");if(code==null||!codes.add(code))throw new IllegalStateException("Duplicate stock panel business key on "+current);
                if(rs.getString("basic_code")!=null)basicCount[0]++;if(rs.getString("limit_code")!=null)limitCount[0]++;
                var stock=new Stock(code,number(rs,"close"),number(rs,"pre_close"),number(rs,"amount"),number(rs,"turnover_rate"),number(rs,"total_mv"),number(rs,"pb"),number(rs,"pe_ttm"),number(rs,"up_limit"),number(rs,"down_limit"));
                boolean suspended=flag(rs.getObject("is_suspended")),st=flag(rs.getObject("is_st"));hashLine(hash,current+"|"+stock+"|"+suspended+"|"+st);
                if(!suspended&&!st)stocks.add(stock);
            },micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper));
            if(date[0]!=null)finishPanelDay(date[0],stocks,codes,basicCount[0],limitCount[0],stockDays,observedPanelDates,panelCoverage);
            readValuations(lower,upper,calendar,valuations,valuationRows,hash,cancelled);lower=upper;
        }
        if(!observedPanelDates.equals(calendar))throw new IllegalStateException("Warmup/output panel missing SSE trading dates: "+difference(calendar,observedPanelDates));
        styleAndBreadth(stockDays);check(cancelled);
        var north=readNorthbound(plan.warmupFrom,plan.request.to(),hash);var margin=readMargins(plan.warmupFrom,plan.request.to(),hash);
        var availability=marginAvailability(margin,hash);var excluded=new HashSet<LocalDate>();for(var item:availability)if(Boolean.FALSE.equals(item.get("admitted")))excluded.add((LocalDate)item.get("tradeDate"));
        var bonds=readGov(plan.warmupFrom,plan.request.to(),hash);
        var merged=merge(stockDays,northbound(north),margin(margin.balances,excluded),valuation(valuations,bonds));
        Instant observed=Instant.now().truncatedTo(ChronoUnit.MICROS);var output=new ArrayList<RegimeFeaturesMonitorDailyRow>();var outputDates=new TreeSet<LocalDate>();
        for(var day:merged.values())if(!day.date.isBefore(plan.request.from())&&!day.date.isAfter(plan.request.to())){
            if(!expectedOutput.contains(day.date))throw new IllegalStateException("Derived product contains a non-trading output date "+day.date);
            for(String field:REQUIRED_CONTEXT_FIELDS)if(!finite(day.get(field)))throw new IllegalStateException("Required input-package context field is unavailable on "+day.date+": "+field);
            output.add(row(day,observed));outputDates.add(day.date);
        }
        if(!outputDates.equals(expectedOutput))throw new IllegalStateException("Missing requested derived output dates: "+difference(expectedOutput,outputDates));
        var ignoredGov=bonds.keySet().stream().filter(d->!valuations.containsKey(d)).toList();
        return new Computed(List.copyOf(output),stockDays.size(),panelRows[0],valuationRows[0],HexFormat.of().formatHex(hash.digest()),List.copyOf(panelCoverage),List.copyOf(availability),
                List.copyOf(expectedOutput),ignoredGov,north.size(),valuations.size(),bonds.size());
    }
    private void finishPanelDay(LocalDate date,List<Stock> stocks,Set<String> codes,int basic,int limits,List<Day> days,Set<LocalDate> observed,List<Map<String,Object>> evidence){
        if(codes.isEmpty()||basic!=codes.size()||limits!=codes.size()||stocks.isEmpty()||!observed.add(date))throw new IllegalStateException("Incomplete/duplicate stock panel on "+date+": factor="+codes.size()+", basic="+basic+", limits="+limits+", tradable="+stocks.size());
        days.add(aggregate(date,stocks));evidence.add(Map.of("tradeDate",date,"factorRows",codes.size(),"basicMatches",basic,"limitMatches",limits,"tradableRows",stocks.size()));
    }
    private void readValuations(LocalDate from,LocalDate upper,Set<LocalDate> calendar,Map<LocalDate,Valuation> result,long[] rawRows,MessageDigest hash,BooleanSupplier cancelled){
        LocalDate[] date={null};var codes=new HashSet<String>();var pe=new ArrayList<Double>();var pb=new ArrayList<Double>();int[] count={0};
        jdbc.query("SELECT cast(trade_date AS long) AS trade_micros,ts_code,pe_ttm,pb FROM daily_basic WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT 200001",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{
                    if(++count[0]>200000)throw new IllegalStateException("Valuation source month exceeds 200000-row budget");if(count[0]==1||(count[0]&1023)==0)check(cancelled);rawRows[0]++;
                    LocalDate current=date(rs);if(!calendar.contains(current))throw new IllegalStateException("Valuation contains a non-SSE-trading date "+current);
                    if(date[0]!=null&&!date[0].equals(current)){finishValuation(date[0],pe,pb,result);pe.clear();pb.clear();codes.clear();}date[0]=current;
                    String code=rs.getString("ts_code");if(code==null||!codes.add(code))throw new IllegalStateException("Duplicate daily_basic valuation key on "+current);
                    double p=number(rs,"pe_ttm"),b=number(rs,"pb");hashLine(hash,"valuation|"+current+"|"+code+"|"+p+"|"+b);
                    // The reference first keeps (pe>0 OR pb>0); a date with no
                    // such rows does not enter its rank/ffill valuation grid.
                    if(p>0||b>0){pe.add(p);pb.add(b);}
                },micros(from),micros(upper));
        if(date[0]!=null)finishValuation(date[0],pe,pb,result);
    }
    private static void finishValuation(LocalDate date,List<Double> pe,List<Double> pb,Map<LocalDate,Valuation> result){
        if(pe.isEmpty())return;var medians=positiveMedians(pe.stream().mapToDouble(Double::doubleValue).toArray(),pb.stream().mapToDouble(Double::doubleValue).toArray());
        if(result.put(date,medians)!=null)throw new IllegalStateException("Duplicate valuation aggregate date "+date);
    }
    private NavigableSet<LocalDate> calendar(LocalDate from,LocalDate to,MessageDigest hash){
        var all=new TreeSet<LocalDate>();var open=new TreeSet<LocalDate>();int[] rows={0};
        jdbc.query("SELECT cast(cal_date AS long) AS trade_micros,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date LIMIT 401",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{if(++rows[0]>400)throw new IllegalStateException("SSE calendar exceeds 400-date budget");var date=date(rs);
                    if(!all.add(date))throw new IllegalStateException("Duplicate SSE calendar key "+date);Object value=rs.getObject("is_open");
                    if(!(value instanceof Number n)||n.intValue()!=0&&n.intValue()!=1)throw new IllegalStateException("Invalid SSE is_open on "+date);
                    if(n.intValue()==1)open.add(date);hashLine(hash,"calendar|"+date+"|"+n.intValue());},micros(from),micros(to.plusDays(1)));
        for(var date=from;!date.isAfter(to);date=date.plusDays(1))if(!all.contains(date))throw new IllegalStateException("Missing authoritative calendar coverage on "+date);
        return open;
    }
    private SortedMap<LocalDate,Double> readNorthbound(LocalDate from,LocalDate to,MessageDigest hash){
        var rows=new TreeMap<LocalDate,Double>();jdbc.query("SELECT cast(trade_date AS long) AS trade_micros,north_money FROM moneyflow_hsgt WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT 401",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{var date=date(rs);double value=number(rs,"north_money");if(rows.put(date,value)!=null)throw new IllegalStateException("Duplicate northbound date "+date);
                    if(rows.size()>400)throw new IllegalStateException("Northbound exceeds 400-date budget");hashLine(hash,"northbound|"+date+"|"+value);},micros(from),micros(to.plusDays(1)));return rows;
    }
    private record Margins(SortedMap<LocalDate,Double> balances,NavigableMap<LocalDate,Map<String,Long>> exchanges){}
    private Margins readMargins(LocalDate from,LocalDate to,MessageDigest hash){
        var duplicates=jdbc.queryForList("SELECT * FROM (SELECT trade_date,ts_code,count() AS n FROM margin_detail WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) GROUP BY trade_date,ts_code) WHERE n>1 LIMIT 1",micros(from),micros(to.plusDays(1)));
        if(!duplicates.isEmpty())throw new IllegalStateException("Duplicate margin_detail business key");
        var balances=new TreeMap<LocalDate,Double>();var exchanges=new TreeMap<LocalDate,Map<String,Long>>();
        jdbc.query("SELECT trade_date,cast(trade_date AS long) AS trade_micros,sum(rzye) balance,count() source_rows,sum(CASE WHEN ts_code LIKE '%.SH' THEN 1 ELSE 0 END) exchange_sh,sum(CASE WHEN ts_code LIKE '%.SZ' THEN 1 ELSE 0 END) exchange_sz,sum(CASE WHEN ts_code LIKE '%.BJ' THEN 1 ELSE 0 END) exchange_bj FROM margin_detail WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) GROUP BY trade_date,trade_micros ORDER BY trade_date LIMIT 401",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{var date=date(rs);double balance=number(rs,"balance");long sh=rs.getLong("exchange_sh"),sz=rs.getLong("exchange_sz"),bj=rs.getLong("exchange_bj");
                    if(sh+sz+bj!=rs.getLong("source_rows"))throw new IllegalStateException("Unrecognized margin exchange code on "+date);
                    if(balances.put(date,balance)!=null)throw new IllegalStateException("Duplicate margin aggregate date "+date);if(balances.size()>400)throw new IllegalStateException("Margin exceeds 400-date budget");
                    exchanges.put(date,Map.of("SH",sh,"SZ",sz,"BJ",bj));hashLine(hash,"margin|"+date+"|"+balance+"|"+sh+"|"+sz+"|"+bj);},micros(from),micros(to.plusDays(1)));
        return new Margins(balances,exchanges);
    }
    private static List<Map<String,Object>> marginAvailability(Margins margins,MessageDigest hash)throws Exception{
        var result=new ArrayList<Map<String,Object>>();for(var entry:margins.exchanges.entrySet()){
            var previous=margins.exchanges.headMap(entry.getKey(),false).descendingMap().entrySet().stream().limit(20).toList();
            var positive=new TreeMap<String,Integer>();var expected=new TreeSet<String>();var missing=new TreeSet<String>();
            for(String exchange:List.of("SH","SZ","BJ")){int n=(int)previous.stream().filter(e->e.getValue().get(exchange)>0).count();positive.put(exchange,n);if(n>=3)expected.add(exchange);}
            for(String exchange:expected)if(entry.getValue().get(exchange)==0)missing.add(exchange);
            result.add(Map.ofEntries(Map.entry("tradeDate",entry.getKey()),Map.entry("counts",entry.getValue()),Map.entry("expectedExchanges",expected),Map.entry("missingExchanges",missing),
                    Map.entry("positiveObservationCounts",positive),Map.entry("priorObservedDays",previous.size()),Map.entry("priorObservedDates",previous.stream().map(Map.Entry::getKey).toList()),
                    Map.entry("admitted",missing.isEmpty()),Map.entry("reason",missing.isEmpty()?"recent_exchange_scope_available":"entire_recent_exchange_missing"),
                    Map.entry("policy","prior_20_observed_dates_min_3_positive_dates")));
        }
        hash.update(canonicalBytes(result));return result;
    }
    private Map<LocalDate,Double> readGov(LocalDate from,LocalDate to,MessageDigest hash){
        var rows=new TreeMap<LocalDate,Double>();jdbc.query("SELECT cast(trade_date AS long) AS trade_micros,yield_value FROM cn_bond_yield_curve WHERE curve_code='gov' AND tenor='10Y' AND trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT 401",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{var date=date(rs);double yield=number(rs,"yield_value");if(rows.put(date,yield)!=null)throw new IllegalStateException("Duplicate gov/10Y source key on "+date);
                    if(rows.size()>400)throw new IllegalStateException("Gov curve exceeds 400-date budget");hashLine(hash,"gov10Y|"+date+"|"+yield);},micros(from),micros(to.plusDays(1)));return rows;
    }
    private String sourcePin()throws Exception{
        var pins=new ArrayList<Object>();for(String source:SOURCES){var metadata=jdbc.queryForList("SELECT id,directoryName,walEnabled,table_txn,table_row_count FROM tables() WHERE table_name=?",source);
            if(metadata.size()!=1)throw new IllegalStateException("Required existing source table missing: "+source);var item=new LinkedHashMap<String,Object>(metadata.getFirst());item.put("table",source);
            if(Boolean.TRUE.equals(item.get("walEnabled"))){var frontier=jdbc.queryForList("SELECT suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name=?",source);
                if(frontier.size()!=1||!QuestDbWriteChecks.walSettled(jdbc,source))throw new IllegalStateException("Source WAL is unsettled: "+source);item.put("wal",frontier.getFirst());}
            pins.add(item);}
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes(pins)));
    }
    private void requireNoPendingPublication()throws Exception{publication(port()).requireNoPendingPublication();}
    public SyncRunLedger.Entry status(String run)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(run);}
    public NativeDailyWindowWritePort.Snapshot<RegimeFeaturesMonitorDailyRow> finishInterrupted(String runId,boolean writerStopped)throws Exception{
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);if(!JOB_ID.equals(run.jobId()))throw new IllegalArgumentException("Run belongs to another owner");
        return publication(port()).finishInterrupted(runId,writerStopped,PRODUCER,null);
    }
    private static String bounded(String table,String columns,String timestamp){return "(SELECT "+columns+" FROM "+table+" WHERE "+timestamp+">=cast(? AS TIMESTAMP) AND "+timestamp+"<cast(? AS TIMESTAMP))";}
    private static long micros(LocalDate date){return MarketSentimentDailyWritePort.micros(date.atStartOfDay().toInstant(ZoneOffset.UTC));}
    private static LocalDate date(ResultSet rs)throws SQLException{Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number n))throw new SQLException("Explicit source epoch microseconds required");
        var utc=MarketSentimentDailyWritePort.fromMicros(n.longValue()).atOffset(ZoneOffset.UTC);if(!utc.toLocalTime().equals(LocalTime.MIDNIGHT))throw new SQLException("Source business date is not UTC midnight");return utc.toLocalDate();}
    private static double number(ResultSet rs,String field)throws SQLException{double value=rs.getDouble(field);if(rs.wasNull()||Double.isNaN(value))return Double.NaN;if(!finite(value))throw new SQLException("Nonfinite source field "+field);return value;}
    private static boolean flag(Object value){return Boolean.TRUE.equals(value)||value instanceof Number n&&n.doubleValue()==1;}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Native regime-monitor calculation cancelled");}
    private static <T> Set<T> difference(Set<T> expected,Set<T> seen){var result=new HashSet<>(expected);result.removeAll(seen);return result;}
    private static void hashLine(MessageDigest hash,String line){hash.update((line+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static byte[] canonicalBytes(Object value)throws Exception{return JobDefinitionJson.mapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(value);}
    private static String fileHash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static void writeEvidence(Path path,Object value)throws Exception{byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(value);
        try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}}
}
