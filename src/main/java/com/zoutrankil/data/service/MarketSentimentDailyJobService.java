package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.MarketSentimentDailyRow;
import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.client.dto.TushareRequest;
import com.zoutrankil.data.repository.MarketSentimentDailyWritePort;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
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
import static com.zoutrankil.data.service.MarketSentimentDailyCalculation.*;

/** Explicit native Java rebuild. Reads existing dependency tables; never shells a Python owner. */
@Service
public final class MarketSentimentDailyJobService implements DatasetImplementation,SyncJobOwner {
    public static final String JOB_ID="data.market_sentiment_daily";
    public static final List<String> SOURCES=List.of("stk_factor","daily_basic","stk_limit","stk_suspend","stk_st_daily","margin_detail","moneyflow","daily");
    public record Plan(FrozenRequest request,String targetId,LocalDate warmupFrom,List<String> dependencies,
                       String sourcePin,MarketSentimentDailyWritePort.Snapshot targetBefore) {}
    public record MaterializationResult(SyncJobRunner.Result result,int historyDates,long sourceRawRows,
                                        String sourceFingerprint,String fullTargetFingerprint,String evidence) {}
    private final JdbcTemplate jdbc;private final Path ledgerPath;private final String table;private final TushareClient sourceClient;
    public MarketSentimentDailyJobService(JdbcTemplate jdbc,TushareClient sourceClient,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger,
            @Value("${app.sync.market-sentiment-table:market_sentiment_daily}") String table){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(120);this.jdbc.setFetchSize(2048);
        ledgerPath=Path.of(ledger).toAbsolutePath().normalize();MarketSentimentDailyWritePort.requireTarget(table);this.table=table;this.sourceClient=Objects.requireNonNull(sourceClient);
    }
    @Override public String datasetId(){return "market_sentiment_daily";}
    @Override public Set<Mode> supportedSyncModes(){return jobDefinition().supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(jobDefinition());}
    @Override public DatasetDefinition definition(){
        var columns=new ArrayList<DatasetDefinition.Column>();var components=MarketSentimentDailyRow.class.getRecordComponents();
        for(int i=0;i<components.length;i++){String name=MarketSentimentDailyWritePort.COLUMNS.get(i);Class<?> type=components[i].getType();
            var storage=type==Instant.class?DatasetDefinition.StorageType.TIMESTAMP:type==Double.class?DatasetDefinition.StorageType.DOUBLE:
                    type==Boolean.class?DatasetDefinition.StorageType.BOOLEAN:DatasetDefinition.StorageType.SYMBOL;
            var temporal=type!=Instant.class?null:new DatasetDefinition.TemporalContract(i==0?DatasetDefinition.TemporalKind.BUSINESS_DATE:DatasetDefinition.TemporalKind.INSTANT,
                    "epochMicros",i==0?"calendar":"UTC","MICROS",i==0?"Trading date encoded at exact UTC midnight":"Java calculation observation instant");
            columns.add(new DatasetDefinition.Column("derived:"+name,name,name,storage,i!=0,"Native v2 rule/PCA field "+name,temporal));}
        return new DatasetDefinition(datasetId(),1,"derived.questdb","java.market_sentiment_daily",table,DatasetDefinition.ObjectKind.TABLE,
                columns,List.of("trade_date"),List.of(),"trade_date",DatasetDefinition.Partition.MONTH,true,
                Set.of(DatasetDefinition.Capability.READ,DatasetDefinition.Capability.WAL_REPLACE),List.of("stk_factor","daily_basic","stk_limit","stk_suspend","stk_st_daily","margin_detail","moneyflow"),
                "53 legacy fields; date-keyed bounded full-row window replacement retaining outside rows and backup. 1400-day warmup; shifted 756/120 rolling scores; full-window winsorization/PCA are inherited rebuild semantics, not a historical point-in-time PCA promise.");
    }
    public static SyncJobDefinition jobDefinition(){
        var parameters=new LinkedHashMap<String,Parameter>();
        parameters.put("target_id",new Parameter(ParameterType.STRING,true,128,1,Set.of()));
        parameters.put("source_pin",new Parameter(ParameterType.STRING,true,64,1,Set.of()));
        parameters.put("target_hash",new Parameter(ParameterType.STRING,true,64,1,Set.of()));
        parameters.put("warmup_from",new Parameter(ParameterType.DATE,true,10,1,Set.of()));
        return new SyncJobDefinition(JOB_ID,2,"market_sentiment_daily",1,"market_sentiment_daily_owner",Set.of(Mode.MATERIALIZE),Mode.MATERIALIZE,
                parameters,"questdb.materialize","market_sentiment_daily.range366","questdb.full_key_values",
                new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(1)),Duration.ofHours(2),new Budget(366,1,1,366,1024*1024),0,List.of(),Frequency.MANUAL,ZoneId.of("Asia/Shanghai"),true,false);
    }
    public Plan plan(LocalDate from,LocalDate to,LocalDate logicalDate)throws Exception{return plan(from,to,logicalDate,Mode.MATERIALIZE);}
    public Plan plan(LocalDate from,LocalDate to,LocalDate logicalDate,Mode mode)throws Exception{
        if(mode!=Mode.MATERIALIZE)throw new IllegalArgumentException("Native sentiment owner currently supports MATERIALIZE only; interrupted publication uses its explicit recovery command");
        if(from==null||to==null||logicalDate==null||from.isAfter(to)||to.isAfter(logicalDate)||ChronoUnit.DAYS.between(from,to)>=366)
            throw new IllegalArgumentException("Explicit bounded from/to <= logicalDate, at most 366 calendar days required");
        requireNoPendingPublication();var port=new MarketSentimentDailyWritePort(jdbc,table);var before=port.formalSnapshot();
        if(!before.wal())throw new IllegalStateException("Current sentiment contract requires its MONTH WAL formal table");
        String pin=sourcePin();LocalDate warmup=from.minusDays(1400);
        var request=jobDefinition().freeze(mode,Map.of("target_id",before.targetId(),"source_pin",pin,"target_hash",before.fingerprint(),"warmup_from",warmup),from,to,logicalDate);
        return new Plan(request,before.targetId(),warmup,SOURCES,pin,before);
    }
    public MaterializationResult run(Plan plan)throws Exception{
        if(plan==null||!plan.request.definition().equals(jobDefinition())||!plan.sourcePin.equals(plan.request.parameters().get("source_pin"))
                ||!plan.targetId.equals(plan.request.parameters().get("target_id"))||!plan.warmupFrom.equals(plan.request.from().minusDays(1400)))
            throw new IllegalArgumentException("Frozen native sentiment plan required");
        String run="market-sentiment-"+UUID.randomUUID();var ledger=new SyncRunLedger(ledgerPath);var port=new MarketSentimentDailyWritePort(jdbc,table);
        var adapter=new Adapter(plan,run,port);var runner=new SyncJobRunner<MarketSentimentDailyRow,Instant>(ledger,new DatasetIntervalLock(ledgerPath));
        var result=runner.run(run,null,plan.targetId,plan.request,adapter,()->Thread.currentThread().isInterrupted());
        return new MaterializationResult(result,adapter.historyDates,adapter.rawRows,adapter.sourceFingerprint,adapter.finalFingerprint,adapter.evidence==null?null:adapter.evidence.toString());
    }
    private final class Adapter implements SyncJobRunner.Adapter<MarketSentimentDailyRow,Instant>{
        final Plan plan;final String run;final MarketSentimentDailyWritePort port;int historyDates;long rawRows;
        String sourceFingerprint,finalFingerprint;Path evidence;boolean journaled;
        Adapter(Plan plan,String run,MarketSentimentDailyWritePort port){this.plan=plan;this.run=run;this.port=port;}
        public void preflight(FrozenRequest request)throws Exception{requireNoPendingPublication();port.requireSame(plan.targetBefore);if(!sourcePin().equals(plan.sourcePin))throw new IllegalStateException("Source changed since planning");}
        public DatasetIntervalLock.Scope conflictScope(FrozenRequest request){return DatasetIntervalLock.Scope.allDates(datasetId());}
        public VerifiedBatchExecutor.Codec<MarketSentimentDailyRow,Instant> codec(){return MarketSentimentDailyWritePort.CODEC;}
        public VerifiedBatchExecutor.Port<MarketSentimentDailyRow,Instant> port(){return port;}
        public boolean recoveryRequired(String ignored)throws Exception{
            if(!journaled)return false;return new ReferencePublicationJournal(ledgerPath,datasetId()).forRun(run).state()!=ReferencePublicationJournal.State.VERIFIED;
        }
        public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<MarketSentimentDailyRow> consumer,BooleanSupplier cancelled)throws Exception{
            check(cancelled);var source=calculate(plan,cancelled);historyDates=source.historyDates;rawRows=source.rawRows;sourceFingerprint=source.fingerprint;
            if(source.rows.isEmpty())throw new IllegalStateException("Requested sentiment window has no completed source dates");
            evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(run).resolve("source.json");Files.createDirectories(evidence.getParent());
            writeEvidence(evidence,Map.ofEntries(Map.entry("producer","java.market_sentiment_daily"),Map.entry("modelVersion",MODEL_VERSION),Map.entry("warmupFrom",plan.warmupFrom),
                    Map.entry("from",request.from()),Map.entry("to",request.to()),Map.entry("historyDates",historyDates),Map.entry("sourceRawRows",rawRows),
                    Map.entry("sourceFingerprint",sourceFingerprint),Map.entry("dailyOnlyMissingFactor",source.gaps),Map.entry("marginAvailability",source.marginAvailability),Map.entry("rows",source.rows)));
            if(!sourcePin().equals(plan.sourcePin))throw new IllegalStateException("Dependency source changed during calculation");
            port.prepare(plan.targetBefore,request.from(),request.to());
            consumer.accept(new SyncJobRunner.Page<>(source.rows,sourceFingerprint,evidence.toString(),null));
            check(cancelled);var stage=port.snapshot(port.stage());
            var expected=new ArrayList<MarketSentimentDailyRow>(plan.targetBefore.rows().stream().filter(r->MarketSentimentDailyWritePort.outside(r,request.from(),request.to())).toList());
            expected.addAll(source.rows);expected.sort(Comparator.comparing(MarketSentimentDailyRow::tradeDate));
            if(!stage.fingerprint().equals(MarketSentimentDailyWritePort.digest(expected)))throw new IllegalStateException("Complete sentiment stage differs from expected replacement");
            if(!sourcePin().equals(plan.sourcePin))throw new IllegalStateException("Dependency source changed before publication");
            port.requireSame(plan.targetBefore);
            var journal=new ReferencePublicationJournal(ledgerPath,datasetId());var locks=new DatasetIntervalLock(ledgerPath);
            var lease=locks.findOwned(run,DatasetIntervalLock.Scope.allDates(datasetId()));if(lease==null)throw new IllegalStateException("Whole sentiment dataset lease missing");journal.requireLease(lease,false);
            String backup="java_d121_market_sentiment_daily_backup_"+UUID.randomUUID().toString().replace("-","");
            var intent=new ReferencePublicationJournal.Intent("market-sentiment-publication-"+UUID.randomUUID(),datasetId(),run,table,backup,port.stage(),plan.targetId,
                    plan.targetBefore.tableId(),plan.targetBefore.directory(),stage.tableId(),plan.targetBefore.fingerprint(),stage.fingerprint(),
                    JobDefinitionJson.mapper().writeValueAsString(Map.of("from",request.from(),"to",request.to(),"sourceFingerprint",sourceFingerprint,"sourceEvidence",evidence.toString(),"sourceEvidenceSha256",fileHash(evidence))));
            var entry=journal.create(intent);journaled=true;
            try{
                check(cancelled);jdbc.execute("RENAME TABLE \""+table+"\" TO \""+backup+"\"");entry=journal.advance(entry,ReferencePublicationJournal.State.OLD_MOVED);
                check(cancelled);jdbc.execute("RENAME TABLE \""+port.stage()+"\" TO \""+table+"\"");entry=journal.advance(entry,ReferencePublicationJournal.State.PUBLISHED);
                var published=port.formalSnapshot();var retained=port.snapshot(backup);
                if(published.tableId()!=stage.tableId()||!published.fingerprint().equals(stage.fingerprint())||retained.tableId()!=plan.targetBefore.tableId()
                        ||!retained.fingerprint().equals(plan.targetBefore.fingerprint()))throw new IllegalStateException("Published sentiment or retained backup differs");
                finalFingerprint=published.fingerprint();journal.advance(entry,ReferencePublicationJournal.State.VERIFIED);
                writeEvidence(evidence.resolveSibling("publication.json"),Map.of("published",true,"backup",backup,"formalRows",published.rows().size(),
                        "windowRows",source.rows.size(),"fullTargetFingerprint",finalFingerprint,"sourceFingerprint",sourceFingerprint,"sourceEvidence",evidence.toString()));
            }catch(Exception failure){try{var current=journal.forRun(run);if(current.state()!=ReferencePublicationJournal.State.VERIFIED&&current.state()!=ReferencePublicationJournal.State.IN_DOUBT)journal.advance(current,ReferencePublicationJournal.State.IN_DOUBT);}catch(Exception journalFailure){failure.addSuppressed(journalFailure);}throw failure;}
            return new SyncJobRunner.SourceCompletion(1,source.rows.size(),true,evidence.resolveSibling("publication.json").toString());
        }
    }
    private record Computed(List<MarketSentimentDailyRow> rows,int historyDates,long rawRows,String fingerprint,List<Map<String,Object>> gaps,List<Map<String,Object>> marginAvailability){}
    private Computed calculate(Plan plan,BooleanSupplier cancelled)throws Exception{
        var days=new ArrayList<Day>();var history=new HashMap<String,StockHistory>();long[] rawRows={0};
        var expected=expectedDates(plan.request.from(),plan.request.to());var seen=new HashSet<LocalDate>();
        var gaps=new ArrayList<Map<String,Object>>();
        MessageDigest sourceHash=MessageDigest.getInstance("SHA-256");
        for(LocalDate lower=plan.warmupFrom;!lower.isAfter(plan.request.to());){
            check(cancelled);LocalDate upper=lower.withDayOfMonth(1).plusMonths(1);if(upper.isAfter(plan.request.to().plusDays(1)))upper=plan.request.to().plusDays(1);
            LocalDate[] date={null};var rows=new ArrayList<Stock>();var rawCodes=new HashSet<String>();int[] basicCount={0},limitCount={0},batchRows={0};
            String sql="SELECT cast(sf.trade_date AS long) AS trade_micros,sf.ts_code,sf.close,sf.pre_close,sf.amount,db.turnover_rate,db.circ_mv,db.total_mv,db.pb,sl.up_limit,sl.down_limit,ss.is_suspended,st.is_st,db.ts_code AS basic_code,sl.ts_code AS limit_code"
                    +" FROM "+bounded("stk_factor","trade_date,ts_code,close,pre_close,amount","trade_date")+" sf"
                    +" LEFT JOIN "+bounded("daily_basic","trade_date,ts_code,turnover_rate,circ_mv,total_mv,pb","trade_date")+" db ON sf.ts_code=db.ts_code AND sf.trade_date=db.trade_date"
                    +" LEFT JOIN "+bounded("stk_limit","trade_date,ts_code,up_limit,down_limit","trade_date")+" sl ON sf.ts_code=sl.ts_code AND sf.trade_date=sl.trade_date"
                    +" LEFT JOIN "+bounded("stk_suspend","timestamp,ts_code,is_suspended","timestamp")+" ss ON sf.ts_code=ss.ts_code AND sf.trade_date=ss.timestamp"
                    +" LEFT JOIN "+bounded("stk_st_daily","timestamp,ts_code,is_st","timestamp")+" st ON sf.ts_code=st.ts_code AND sf.trade_date=st.timestamp"
                    +" ORDER BY sf.trade_date,sf.ts_code LIMIT 200001";
            jdbc.query(sql,(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                if(++batchRows[0]>200000)throw new IllegalStateException("Historical month exceeds 200000-row budget");
                // The shared cancellation supplier consults SQLite; keep it outside the per-row hot path.
                if(batchRows[0]==1 || (batchRows[0]&1023)==0)check(cancelled);rawRows[0]++;
                LocalDate current=date(rs);
                if(date[0]!=null&&!date[0].equals(current)){finishDay(date[0],rows,rawCodes,basicCount[0],limitCount[0],expected,seen,days,gaps);rows.clear();rawCodes.clear();basicCount[0]=limitCount[0]=0;}
                date[0]=current;String code=rs.getString("ts_code");if(code==null||!rawCodes.add(code))throw new IllegalStateException("Duplicate stock panel business key on "+current);
                if(rs.getString("basic_code")!=null)basicCount[0]++;if(rs.getString("limit_code")!=null)limitCount[0]++;
                double close=number(rs,"close"),previous=number(rs,"pre_close"),amount=number(rs,"amount"),turnover=number(rs,"turnover_rate"),circ=number(rs,"circ_mv"),mv=number(rs,"total_mv"),pb=number(rs,"pb"),up=number(rs,"up_limit"),down=number(rs,"down_limit");
                boolean suspended=flag(rs.getObject("is_suspended")),st=flag(rs.getObject("is_st"));
                String raw=current+"|"+code+"|"+close+"|"+previous+"|"+amount+"|"+turnover+"|"+circ+"|"+mv+"|"+pb+"|"+up+"|"+down+"|"+suspended+"|"+st+"\n";sourceHash.update(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if(!suspended&&!st)rows.add(history.computeIfAbsent(code,k->new StockHistory()).prepare(code,close,previous,amount,turnover,circ,mv,pb,up,down));
            },micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper));
            if(date[0]!=null)finishDay(date[0],rows,rawCodes,basicCount[0],limitCount[0],expected,seen,days,gaps);lower=upper;
        }
        if(!seen.equals(expected.keySet()))throw new IllegalStateException("Sentiment source is missing requested trading dates: "+difference(expected.keySet(),seen));
        if(days.size()<120)throw new IllegalStateException("At least 120 historical trading dates required for sentiment rebuild");
        var marginAvailability=enrich(days,plan.warmupFrom,plan.request.from(),plan.request.to(),sourceHash);score(days);
        Instant observed=Instant.now().truncatedTo(ChronoUnit.MICROS);var output=new ArrayList<MarketSentimentDailyRow>();
        for(var day:days)if(!day.date.isBefore(plan.request.from())&&!day.date.isAfter(plan.request.to())){
            var values=new LinkedHashMap<String,Object>();day.numbers.forEach((k,v)->values.put(k,finite(v)?v:null));values.putAll(day.flags);
            values.put("trade_date",day.date.atStartOfDay().toInstant(ZoneOffset.UTC));values.put("month",day.date.toString().substring(0,7).replace("-",""));values.put("sentiment_state",day.state);
            boolean leverage=finite(day.get("leverage_score")),moneyflow=finite(day.get("moneyflow_score"));values.put("data_quality_flag",leverage&&moneyflow?"enhanced":leverage||moneyflow?"partial_enhanced":"core_only");
            values.put("model_version",MODEL_VERSION);values.put("updated_at",observed);if(!finite(day.get("sentiment_score")))throw new IllegalStateException("Requested sentiment score lacks sufficient history on "+day.date);
            output.add(MarketSentimentDailyWritePort.row(values));}
        sourceHash.update(canonicalBytes(gaps));
        return new Computed(List.copyOf(output),days.size(),rawRows[0],HexFormat.of().formatHex(sourceHash.digest()),List.copyOf(gaps),marginAvailability);
    }
    private void finishDay(LocalDate date,List<Stock> rows,Set<String> codes,int basic,int limits,Map<LocalDate,Set<String>> expected,Set<LocalDate> seen,List<Day> days,List<Map<String,Object>> gaps){
        if(expected.containsKey(date)){if(!expected.get(date).containsAll(codes)||basic!=codes.size()||limits!=codes.size())
            throw new IllegalStateException("Incomplete requested stock panel on "+date+": expected="+expected.get(date).size()+", factor="+codes.size()+", basic="+basic+", limits="+limits);
            var missing=new TreeSet<>(expected.get(date));missing.removeAll(codes);
            for(String code:missing){if(gaps.size()>=20)throw new IllegalStateException("Daily/factor discrepancy exceeds 20-record audit bound");gaps.add(proveListingDayGap(date,code));}
            seen.add(date);}
        if(!rows.isEmpty())days.add(aggregate(date,rows));
    }
    /** Listing-day omissions need two independent provider responses, never a code exception list. */
    private Map<String,Object> proveListingDayGap(LocalDate date,String code){
        try{
            String basicDate=date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            var listing=sourceClient.request(new TushareRequest("stock_basic",Map.of("ts_code",code),List.of("ts_code","list_date"),2));
            if(listing.rows().size()!=1||!code.equals(listing.rows().getFirst().get("ts_code").asText())
                    ||!basicDate.equals(listing.rows().getFirst().get("list_date").asText()))
                throw new IllegalStateException("Missing factor is not independently verified as a listing-day stock: "+date+"/"+code);
            var factors=sourceClient.request(new TushareRequest(StockFactorSource.SOURCE_ENDPOINT,Map.of("ts_code",code,"start_date",basicDate,"end_date",basicDate),List.of("ts_code","trade_date"),10000));
            if(!factors.rows().isEmpty())throw new IllegalStateException("Provider has factor rows absent locally: "+date+"/"+code);
            return Map.of("tradeDate",date,"tsCode",code,"listingDate",basicDate,"listingEndpoint","stock_basic","listingResponse",listing.rows(),
                    "factorEndpoint",StockFactorSource.SOURCE_ENDPOINT,"factorResponse",factors.rows(),"factorProbeRows",0,"decision","provider_empty_on_verified_listing_day");
        }catch(java.io.IOException failure){throw new IllegalStateException("Cannot establish provider evidence for factor gap: "+date+"/"+code,failure);}
    }
    private Map<LocalDate,Set<String>> expectedDates(LocalDate from,LocalDate to){
        var result=new TreeMap<LocalDate,Set<String>>();int[] count={0};jdbc.query("SELECT cast(trade_date AS long) AS trade_micros,ts_code FROM daily WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT 2200001",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{if(++count[0]>2200000)throw new IllegalStateException("Expected daily universe exceeds bounded row budget");var date=date(rs);
                    if(!result.computeIfAbsent(date,d->new HashSet<>()).add(rs.getString("ts_code")))throw new IllegalStateException("Duplicate daily source key");},micros(from),micros(to.plusDays(1)));
        if(result.isEmpty())throw new IllegalStateException("No authoritative daily trading dates in requested window");return result;
    }
    private List<Map<String,Object>> enrich(List<Day> days,LocalDate from,LocalDate requestedFrom,LocalDate to,MessageDigest hash)throws Exception{
        var margins=new TreeMap<LocalDate,double[]>();var exchanges=new TreeMap<LocalDate,Map<String,Long>>();
        jdbc.query("SELECT trade_date,cast(trade_date AS long) AS trade_micros,sum(rzye) balance,sum(rzmre) buy,sum(rzche) repay,count() source_rows,"
                +"sum(CASE WHEN ts_code LIKE '%.SH' THEN 1 ELSE 0 END) exchange_sh,"
                +"sum(CASE WHEN ts_code LIKE '%.SZ' THEN 1 ELSE 0 END) exchange_sz,"
                +"sum(CASE WHEN ts_code LIKE '%.BJ' THEN 1 ELSE 0 END) exchange_bj"
                +" FROM margin_detail WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) GROUP BY trade_date,trade_micros ORDER BY trade_date LIMIT 1801",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{
                    LocalDate date=date(rs);long sh=rs.getLong("exchange_sh"),sz=rs.getLong("exchange_sz"),bj=rs.getLong("exchange_bj");
                    if(sh+sz+bj!=rs.getLong("source_rows"))throw new IllegalStateException("Margin source contains unrecognized exchange codes on "+date);
                    if(margins.put(date,new double[]{number(rs,"balance"),number(rs,"buy"),number(rs,"repay")})!=null)throw new IllegalStateException("Duplicate margin aggregate date");
                    exchanges.put(date,Map.of("SH",sh,"SZ",sz,"BJ",bj));if(margins.size()>1800)throw new IllegalStateException("Margin aggregate exceeds bounded date budget");
                },micros(from),micros(to.plusDays(1)));
        var availability=new ArrayList<Map<String,Object>>();var excluded=new HashSet<LocalDate>();
        for(var day:days)if(!day.date.isBefore(requestedFrom)&&!day.date.isAfter(to)){
            // Only preceding observed dates establish scope. Today's incomplete
            // response cannot teach the guard that a whole market stopped existing.
            var preceding=exchanges.headMap(day.date,false).descendingMap().entrySet().stream().limit(20).toList();
            var observations=new TreeMap<String,Integer>();var expectedExchanges=new TreeSet<String>();
            for(String exchange:List.of("SH","SZ","BJ")){int count=(int)preceding.stream().filter(e->e.getValue().get(exchange)>0).count();observations.put(exchange,count);if(count>=3)expectedExchanges.add(exchange);}
            var counts=exchanges.getOrDefault(day.date,Map.of("SH",0L,"SZ",0L,"BJ",0L));
            var missing=new TreeSet<String>();for(String exchange:expectedExchanges)if(counts.get(exchange)==0)missing.add(exchange);
            String reason=!margins.containsKey(day.date)?"no_margin_observation":!missing.isEmpty()?"entire_recent_exchange_missing":"recent_exchange_scope_available";
            boolean admitted=margins.containsKey(day.date)&&missing.isEmpty();if(!admitted)excluded.add(day.date);
            availability.add(Map.ofEntries(Map.entry("tradeDate",day.date),Map.entry("admitted",admitted),Map.entry("counts",counts),
                    Map.entry("expectedExchanges",expectedExchanges),Map.entry("missingExchanges",missing),Map.entry("reason",reason),
                    Map.entry("priorObservedDays",preceding.size()),Map.entry("priorObservedDates",preceding.stream().map(Map.Entry::getKey).toList()),
                    Map.entry("positiveObservationCounts",observations),Map.entry("policy","prior_20_observed_dates_min_3_positive_dates")));
        }
        var marginDates=new ArrayList<>(margins.keySet());var changes=new HashMap<LocalDate,Double>();
        // The reference pandas 2.3 pct_change(5) defaults to fill_method='pad'.
        double[] filledBalance=new double[marginDates.size()];double last=Double.NaN;
        for(int i=0;i<marginDates.size();i++){LocalDate date=marginDates.get(i);double value=excluded.contains(date)?Double.NaN:margins.get(date)[0];if(finite(value))last=value;filledBalance[i]=last;}
        for(int i=0;i<marginDates.size();i++)changes.put(marginDates.get(i),i<5?Double.NaN:divide(filledBalance[i],filledBalance[i-5])-1);
        var flows=new TreeMap<LocalDate,double[]>();jdbc.query("SELECT trade_date,cast(trade_date AS long) AS trade_micros,sum(net_mf_amount) net,sum(buy_lg_amount+buy_elg_amount-sell_lg_amount-sell_elg_amount) large_net FROM moneyflow WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) GROUP BY trade_date,trade_micros ORDER BY trade_date LIMIT 1801",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{flows.put(date(rs),new double[]{number(rs,"net"),number(rs,"large_net")});},micros(from),micros(to.plusDays(1)));
        for(var d:days){var margin=margins.get(d.date);if(margin!=null&&!excluded.contains(d.date)){d.put("margin_buy_sell_ratio",divide(margin[1],margin[2]));d.put("margin_buy_amount_ratio",divide(margin[1],d.get("total_amount")*1000));d.put("margin_balance_change_5d",changes.get(d.date));}
            else{d.put("margin_buy_sell_ratio",Double.NaN);d.put("margin_buy_amount_ratio",Double.NaN);d.put("margin_balance_change_5d",Double.NaN);}
            var flow=flows.get(d.date);if(flow!=null){d.put("moneyflow_net_amount_ratio",divide(flow[0],d.get("total_amount")/10));d.put("moneyflow_large_net_ratio",divide(flow[1],d.get("total_amount")/10));}
            hash.update((d.date+"|"+Arrays.toString(margin)+"|"+Arrays.toString(flow)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        hash.update(canonicalBytes(availability));return List.copyOf(availability);
    }
    private String sourcePin()throws Exception{
        var pins=new ArrayList<Object>();for(String source:SOURCES){var metadata=jdbc.queryForList("SELECT id,directoryName,walEnabled,table_txn,table_row_count FROM tables() WHERE table_name=?",source);
            if(metadata.size()!=1)throw new IllegalStateException("Required source table missing: "+source);var item=new LinkedHashMap<String,Object>(metadata.getFirst());item.put("table",source);
            if(Boolean.TRUE.equals(item.get("walEnabled"))){var frontier=jdbc.queryForList("SELECT suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name=?",source);
                if(frontier.size()!=1)throw new IllegalStateException("Source WAL metadata missing: "+source);var wal=frontier.getFirst();
                if(!com.zoutrankil.data.repository.QuestDbWriteChecks.walSettled(jdbc,source))
                    throw new IllegalStateException("Source WAL is unsettled: "+source);item.put("wal",wal);}
            pins.add(item);}
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes(pins)));
    }
    private void requireNoPendingPublication()throws Exception{
        if(!Files.isRegularFile(ledgerPath))return;
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledgerPath);var exists=db.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='reference_publications'")){try(var r=exists.executeQuery()){if(!r.next()||r.getInt(1)==0)return;}
            try(var query=db.prepareStatement("SELECT run_id FROM reference_publications WHERE dataset='market_sentiment_daily' AND state<>'VERIFIED' LIMIT 1");var r=query.executeQuery()){
                if(r.next())throw new IllegalStateException("Sentiment publication requires explicit reconciliation before another run: "+r.getString(1));}}
    }
    public SyncRunLedger.Entry status(String run)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(run);}
    /** Complete a previously verified stage only with explicit proof that its former writer stopped. */
    public MarketSentimentDailyWritePort.Snapshot finishInterrupted(String runId,boolean writerStopped)throws Exception{
        if(!writerStopped)throw new IllegalArgumentException("Explicit stopped-writer proof required");
        var ledger=new SyncRunLedger(ledgerPath);var run=ledger.getRun(runId);if(!JOB_ID.equals(run.jobId()))throw new IllegalArgumentException("Run belongs to another owner");
        var journal=new ReferencePublicationJournal(ledgerPath,datasetId());var entry=journal.forRun(runId);var intent=entry.intent();
        if(!table.equals(intent.target())||!run.targetId().equals(intent.initialTarget()))throw new IllegalStateException("Recovery target binding differs");
        var scope=JobDefinitionJson.mapper().readTree(intent.scope());Path sourceEvidence=Path.of(scope.path("sourceEvidence").asText()).toAbsolutePath().normalize();
        Path ownedRoot=ledgerPath.getParent().resolve("sync-evidence").resolve(runId).toAbsolutePath().normalize();
        if(!sourceEvidence.startsWith(ownedRoot)||!Files.isRegularFile(sourceEvidence)||Files.size(sourceEvidence)>4*1024*1024
                ||!fileHash(sourceEvidence).equals(scope.path("sourceEvidenceSha256").asText()))throw new IllegalStateException("Recovery source evidence is missing or changed");
        var source=JobDefinitionJson.mapper().readTree(sourceEvidence.toFile());if(!MODEL_VERSION.equals(source.path("modelVersion").asText())
                ||!scope.path("sourceFingerprint").asText().equals(source.path("sourceFingerprint").asText())||!source.path("rows").isArray())throw new IllegalStateException("Recovery source proof differs");
        var children=ledger.entries(runId,null,100);var slices=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        var attempts=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).toList();
        if(slices.size()!=1||slices.getFirst().state()!=SyncRunState.VERIFIED||attempts.size()!=1)throw new IllegalStateException("Recovery requires one fully verified native source slice");
        var port=new MarketSentimentDailyWritePort(jdbc,table);var locks=new DatasetIntervalLock(ledgerPath);var lease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates(datasetId()));
        if(entry.state()!=ReferencePublicationJournal.State.VERIFIED){if(lease==null)throw new IllegalStateException("Uncertain publication lease missing");journal.requireLease(lease,true);
            boolean targetOld=identityMatches(intent.target(),intent.originalId()),targetNew=identityMatches(intent.target(),intent.replacementId()),
                    backupOld=identityMatches(intent.backup(),intent.originalId()),stageNew=identityMatches(intent.stage(),intent.replacementId());
            if(!(targetOld&&stageNew&&!tableExists(intent.backup())||!tableExists(intent.target())&&backupOld&&stageNew||targetNew&&backupOld&&!tableExists(intent.stage())))
                throw new IllegalStateException("Recovery layout conflicts with durable publication identities");
            var old=port.snapshot(targetOld?intent.target():intent.backup());var replacement=port.snapshot(targetNew?intent.target():intent.stage());
            if(!old.fingerprint().equals(intent.beforeFingerprint())||!replacement.fingerprint().equals(intent.afterFingerprint()))throw new IllegalStateException("Recovery content fingerprint differs");
            if(entry.state()!=ReferencePublicationJournal.State.IN_DOUBT)entry=journal.advance(entry,ReferencePublicationJournal.State.IN_DOUBT);
            entry=journal.advance(entry,ReferencePublicationJournal.State.RESUMING);
            try{if(targetOld)jdbc.execute("RENAME TABLE \""+intent.target()+"\" TO \""+intent.backup()+"\"");
                if(!targetNew)jdbc.execute("RENAME TABLE \""+intent.stage()+"\" TO \""+intent.target()+"\"");
                entry=journal.advance(entry,ReferencePublicationJournal.State.PUBLISHED);
                var actual=port.formalSnapshot();var backup=port.snapshot(intent.backup());
                if(actual.tableId()!=intent.replacementId()||backup.tableId()!=intent.originalId()||!actual.fingerprint().equals(intent.afterFingerprint())||!backup.fingerprint().equals(intent.beforeFingerprint()))
                    throw new IllegalStateException("Recovered formal or backup differs");entry=journal.advance(entry,ReferencePublicationJournal.State.VERIFIED);
            }catch(Exception failure){var current=journal.forRun(runId);if(current.state()!=ReferencePublicationJournal.State.VERIFIED&&current.state()!=ReferencePublicationJournal.State.IN_DOUBT)journal.advance(current,ReferencePublicationJournal.State.IN_DOUBT);throw failure;}
        }
        var actual=port.formalSnapshot();if(actual.tableId()!=intent.replacementId()||!actual.fingerprint().equals(intent.afterFingerprint()))throw new IllegalStateException("Verified publication drifted");
        int rows=source.path("rows").size();String proof=JobDefinitionJson.mapper().writeValueAsString(Map.of("sourceComplete",true,"returnedRows",rows,"publication",intent.id(),
                "verification",Map.of("passed",true,"writerStopped",true,"expectedRows",rows,"actualRows",rows,"matchedRows",rows,"duplicateKeys",0,"missingKeys",0,"mismatchedRows",0,"sourceFingerprint",scope.path("sourceFingerprint").asText(),"readbackEvidence",intent.id())));
        for(var item:List.of(attempts.getFirst(),ledger.get(runId))){var current=ledger.get(item.id());if(current.state()==SyncRunState.VERIFIED)continue;
            if(current.state()==SyncRunState.RUNNING||current.state()==SyncRunState.ACKNOWLEDGED){ledger.transition(current.id(),current.revision(),SyncRunState.IN_DOUBT,"{\"publicationRecovery\":true}");current=ledger.get(current.id());}
            if(current.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("Recovery ledger state cannot complete: "+current.state());ledger.transition(current.id(),current.revision(),SyncRunState.VERIFIED,proof);}
        if(lease!=null){if(!lease.inDoubt()){locks.retainInDoubt(lease);lease=locks.findOwned(runId,lease.scope());}locks.releaseAfterReconciliation(lease,true,true);}
        return actual;
    }
    private boolean tableExists(String name){return !jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",name).isEmpty();}
    private boolean identityMatches(String name,long id){var found=jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",name);return found.size()==1&&found.getFirst().get("id") instanceof Number n&&n.longValue()==id;}
    private static String fileHash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static byte[] canonicalBytes(Object value)throws Exception{return JobDefinitionJson.mapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(value);}
    private static void writeEvidence(Path path,Object evidence)throws Exception{byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(evidence);
        try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}}
    private static long micros(LocalDate date){return MarketSentimentDailyWritePort.micros(date.atStartOfDay().toInstant(ZoneOffset.UTC));}
    private static String bounded(String table,String columns,String timestamp){return "(SELECT "+columns+" FROM "+table+" WHERE "+timestamp+">=cast(? AS TIMESTAMP) AND "+timestamp+"<cast(? AS TIMESTAMP))";}
    private static LocalDate date(ResultSet rs)throws SQLException{Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number n))throw new SQLException("Explicit trading date epoch required");
        var carrier=MarketSentimentDailyWritePort.fromMicros(n.longValue());var utc=carrier.atOffset(ZoneOffset.UTC);if(!utc.toLocalTime().equals(LocalTime.MIDNIGHT))throw new SQLException("Trading date is not an exact calendar carrier");return utc.toLocalDate();}
    private static double number(ResultSet rs,String field)throws SQLException{double value=rs.getDouble(field);if(rs.wasNull()||Double.isNaN(value))return Double.NaN;if(!finite(value))throw new SQLException("Nonfinite source value: "+field);return value;}
    private static boolean flag(Object value){return Boolean.TRUE.equals(value)||value instanceof Number n&&n.doubleValue()==1;}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Sentiment calculation cancelled");}
    private static <T> Set<T> difference(Set<T> expected,Set<T> seen){var values=new HashSet<>(expected);values.removeAll(seen);return values;}
}
