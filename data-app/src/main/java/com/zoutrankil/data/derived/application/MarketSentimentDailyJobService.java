package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;




import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.MarketSentimentDailyRow;
import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.client.dto.TushareRequest;

import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;
import static com.zoutrankil.data.domain.policy.MarketSentimentDailyCalculation.*;

/** Native Java calculation, used by the persistent main-strategy batch and explicit recovery. */
@Service
public final class MarketSentimentDailyJobService implements DatasetImplementation,SyncJobOwner {
    public static final String JOB_ID="data.market_sentiment_daily";
    public static final List<String> SOURCES=List.of("stk_factor","daily_basic","stk_limit","stk_suspend","stk_st_daily","margin_detail","moneyflow","daily");
    public record Plan(FrozenRequest request,String targetId,LocalDate warmupFrom,List<String> dependencies,
                       String sourcePin,MarketSentimentDailyTargetSnapshot targetBefore) {}
    public record MaterializationResult(SyncJobRunner.Result result,int historyDates,long sourceRawRows,
                                        String sourceFingerprint,String fullTargetFingerprint,String evidence) {}
    private final MarketSentimentDailySourceReadPort storage;private final MarketSentimentDailyTarget target;private final Path ledgerPath;private final String table;private final TushareClient sourceClient;
    public MarketSentimentDailyJobService(MarketSentimentDailySourceReadPort storage,MarketSentimentDailyTarget target,TushareClient sourceClient,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger){
        this.storage=Objects.requireNonNull(storage);this.target=Objects.requireNonNull(target);
        ledgerPath=Path.of(ledger).toAbsolutePath().normalize();target.requireTarget();this.table=target.table();this.sourceClient=Objects.requireNonNull(sourceClient);
    }
    @Override public String datasetId(){return "market_sentiment_daily";}
    @Override public Set<Mode> supportedSyncModes(){return jobDefinition().supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(jobDefinition());}
    @Override public DatasetDefinition definition(){
        var columns=new ArrayList<DatasetDefinition.Column>();var components=MarketSentimentDailyRow.class.getRecordComponents();
        for(int i=0;i<components.length;i++){String name=MarketSentimentDailyRows.COLUMNS.get(i);Class<?> type=components[i].getType();
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
        return new SyncJobDefinition(JOB_ID,3,"market_sentiment_daily",1,"market_sentiment_daily_owner",Set.of(Mode.MATERIALIZE),Mode.MATERIALIZE,
                parameters,"questdb.materialize","market_sentiment_daily.range366","questdb.full_key_values",
                new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(1)),Duration.ofHours(2),new Budget(366,1,1,366,1024*1024),0,List.of(),Frequency.DAILY,ZoneId.of("Asia/Shanghai"),true,true);
    }
    public Plan plan(LocalDate from,LocalDate to,LocalDate logicalDate)throws Exception{return plan(from,to,logicalDate,Mode.MATERIALIZE);}
    public Plan plan(LocalDate from,LocalDate to,LocalDate logicalDate,Mode mode)throws Exception{
        if(mode!=Mode.MATERIALIZE)throw new IllegalArgumentException("Native sentiment owner currently supports MATERIALIZE only; interrupted publication uses its explicit recovery command");
        if(from==null||to==null||logicalDate==null||from.isAfter(to)||to.isAfter(logicalDate)||ChronoUnit.DAYS.between(from,to)>=366)
            throw new IllegalArgumentException("Explicit bounded from/to <= logicalDate, at most 366 calendar days required");
        requireNoPendingPublication();var port=target.newWriter();var before=port.formalSnapshot();
        if(!before.wal())throw new IllegalStateException("Current sentiment contract requires its MONTH WAL formal table");
        String pin=sourcePin();LocalDate warmup=from.minusDays(1400);
        var request=jobDefinition().freeze(mode,Map.of("target_id",before.targetId(),"source_pin",pin,"target_hash",before.fingerprint(),"warmup_from",warmup),from,to,logicalDate);
        return new Plan(request,before.targetId(),warmup,SOURCES,pin,before);
    }
    public MaterializationResult run(Plan plan)throws Exception{
        if(plan==null||!plan.request.definition().equals(jobDefinition())||!plan.sourcePin.equals(plan.request.parameters().get("source_pin"))
                ||!plan.targetId.equals(plan.request.parameters().get("target_id"))||!plan.warmupFrom.equals(plan.request.from().minusDays(1400)))
            throw new IllegalArgumentException("Frozen native sentiment plan required");
        String run="market-sentiment-"+UUID.randomUUID();var ledger=new SyncRunLedger(ledgerPath);var port=target.newWriter();
        var adapter=new Adapter(plan,run,port);var runner=new SyncJobRunner<MarketSentimentDailyRow,Instant>(ledger,new DatasetIntervalLock(ledgerPath));
        var result=runner.run(run,null,plan.targetId,plan.request,adapter,()->Thread.currentThread().isInterrupted());
        return new MaterializationResult(result,adapter.historyDates,adapter.rawRows,adapter.sourceFingerprint,adapter.finalFingerprint,adapter.evidence==null?null:adapter.evidence.toString());
    }
    private final class Adapter implements SyncJobRunner.Adapter<MarketSentimentDailyRow,Instant>{
        final Plan plan;final String run;final MarketSentimentDailyWriteSession port;int historyDates;long rawRows;
        String sourceFingerprint,finalFingerprint;Path evidence;boolean journaled;
        Adapter(Plan plan,String run,MarketSentimentDailyWriteSession port){this.plan=plan;this.run=run;this.port=port;}
        public void preflight(FrozenRequest request)throws Exception{requireNoPendingPublication();port.requireSame(plan.targetBefore);if(!sourcePin().equals(plan.sourcePin))throw new IllegalStateException("Source changed since planning");}
        public DatasetIntervalLock.Scope conflictScope(FrozenRequest request){return DatasetIntervalLock.Scope.allDates(datasetId());}
        public VerifiedBatchExecutor.Codec<MarketSentimentDailyRow,Instant> codec(){return MarketSentimentDailyWriteSession.CODEC;}
        public VerifiedBatchExecutor.Port<MarketSentimentDailyRow,Instant> port(){return port;}
        public boolean recoveryRequired(String ignored)throws Exception{
            if(!journaled)return false;new ReferencePublicationJournal(ledgerPath,datasetId()).forRun(run);return true;
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
            var expected=new ArrayList<MarketSentimentDailyRow>(plan.targetBefore.rows().stream().filter(r->MarketSentimentDailyRows.outside(r,request.from(),request.to())).toList());
            expected.addAll(source.rows);expected.sort(Comparator.comparing(MarketSentimentDailyRow::tradeDate));
            if(!stage.fingerprint().equals(MarketSentimentDailyRows.digest(expected)))throw new IllegalStateException("Complete sentiment stage differs from expected replacement");
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
                check(cancelled);target.rename(table,backup);port.nativePort().awaitPublished(backup,plan.targetBefore.tableId(),plan.targetBefore.directory(),cancelled);entry=journal.advance(entry,ReferencePublicationJournal.State.OLD_MOVED);
                check(cancelled);target.rename(port.stage(),table);port.nativePort().awaitPublished(table,stage.tableId(),stage.directory(),cancelled);entry=journal.advance(entry,ReferencePublicationJournal.State.PUBLISHED);
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
            var rows=new ArrayList<Stock>();
            rawRows[0]+=storage.readPanelMonth(lower,upper,()->check(cancelled),new MarketSentimentDailySourceReadPort.PanelConsumer(){
                @Override public void completeDay(LocalDate date,Set<String> codes,int basic,int limits){
                    finishDay(date,rows,codes,basic,limits,expected,seen,days,gaps);rows.clear();
                }
                @Override public void accept(MarketSentimentDailySourceData.PanelRow row){
                    LocalDate current=row.date();String code=row.code();
                    double close=row.close(),previous=row.previous(),amount=row.amount(),turnover=row.turnover(),circ=row.circ(),mv=row.mv(),pb=row.pb(),up=row.up(),down=row.down();
                    boolean suspended=row.suspended(),st=row.st();
                    String raw=current+"|"+code+"|"+close+"|"+previous+"|"+amount+"|"+turnover+"|"+circ+"|"+mv+"|"+pb+"|"+up+"|"+down+"|"+suspended+"|"+st+"\n";sourceHash.update(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if(!suspended&&!st)rows.add(history.computeIfAbsent(code,k->new StockHistory()).prepare(code,close,previous,amount,turnover,circ,mv,pb,up,down));
                }
            });lower=upper;
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
            output.add(MarketSentimentDailyRows.row(values));}
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
            var factors=sourceClient.request(new TushareRequest(MarketSentimentDailyRows.FACTOR_SOURCE_ENDPOINT,Map.of("ts_code",code,"start_date",basicDate,"end_date",basicDate),List.of("ts_code","trade_date"),10000));
            if(!factors.rows().isEmpty())throw new IllegalStateException("Provider has factor rows absent locally: "+date+"/"+code);
            return Map.of("tradeDate",date,"tsCode",code,"listingDate",basicDate,"listingEndpoint","stock_basic","listingResponse",listing.rows(),
                    "factorEndpoint",MarketSentimentDailyRows.FACTOR_SOURCE_ENDPOINT,"factorResponse",factors.rows(),"factorProbeRows",0,"decision","provider_empty_on_verified_listing_day");
        }catch(java.io.IOException failure){throw new IllegalStateException("Cannot establish provider evidence for factor gap: "+date+"/"+code,failure);}
    }
    private Map<LocalDate,Set<String>> expectedDates(LocalDate from,LocalDate to){return storage.expectedDates(from,to);}
    private List<Map<String,Object>> enrich(List<Day> days,LocalDate from,LocalDate requestedFrom,LocalDate to,MessageDigest hash)throws Exception{
        var marginData=storage.margins(from,to);var margins=marginData.margins();var exchanges=marginData.exchanges();
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
        var flows=storage.flows(from,to);
        for(var d:days){var margin=margins.get(d.date);if(margin!=null&&!excluded.contains(d.date)){d.put("margin_buy_sell_ratio",divide(margin[1],margin[2]));d.put("margin_buy_amount_ratio",divide(margin[1],d.get("total_amount")*1000));d.put("margin_balance_change_5d",changes.get(d.date));}
            else{d.put("margin_buy_sell_ratio",Double.NaN);d.put("margin_buy_amount_ratio",Double.NaN);d.put("margin_balance_change_5d",Double.NaN);}
            var flow=flows.get(d.date);if(flow!=null){d.put("moneyflow_net_amount_ratio",divide(flow[0],d.get("total_amount")/10));d.put("moneyflow_large_net_ratio",divide(flow[1],d.get("total_amount")/10));}
            hash.update((d.date+"|"+Arrays.toString(margin)+"|"+Arrays.toString(flow)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        hash.update(canonicalBytes(availability));return List.copyOf(availability);
    }
    private String sourcePin()throws Exception{return storage.sourcePin(SOURCES);}
    private void requireNoPendingPublication()throws Exception{target.requireNoPendingPublication(ledgerPath);}
    public LedgerReadModels.Entry status(String run)throws Exception{return LedgerReadModels.entry(SyncRunLedger.openReadOnly(ledgerPath).get(run));}
    /** Complete a previously verified stage only with explicit proof that its former writer stopped. */
    public MarketSentimentDailyTargetSnapshot finishInterrupted(String runId,boolean writerStopped)throws Exception{
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
        boolean unfinishedOwner=false;
        for(var current:List.of(ledger.get(attempts.getFirst().id()),ledger.get(runId))){
            if(!Set.of(SyncRunState.RUNNING,SyncRunState.ACKNOWLEDGED,SyncRunState.IN_DOUBT,SyncRunState.VERIFIED).contains(current.state()))
                throw new IllegalStateException("Recovery ledger state cannot complete: "+current.state());
            unfinishedOwner|=current.state()!=SyncRunState.VERIFIED;
        }
        var port=target.newWriter();var locks=new DatasetIntervalLock(ledgerPath);var lease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates(datasetId()));
        if(unfinishedOwner||entry.state()!=ReferencePublicationJournal.State.VERIFIED){if(lease==null)throw new IllegalStateException("Uncertain publication lease missing");journal.requireLease(lease,true);}
        if(entry.state()!=ReferencePublicationJournal.State.VERIFIED){
            boolean targetOld=identityMatches(intent.target(),intent.originalId()),targetNew=identityMatches(intent.target(),intent.replacementId()),
                    backupOld=identityMatches(intent.backup(),intent.originalId()),stageNew=identityMatches(intent.stage(),intent.replacementId());
            if(!(targetOld&&stageNew&&!tableExists(intent.backup())||!tableExists(intent.target())&&backupOld&&stageNew||targetNew&&backupOld&&!tableExists(intent.stage())))
                throw new IllegalStateException("Recovery layout conflicts with durable publication identities");
            var old=port.snapshot(targetOld?intent.target():intent.backup());var replacement=port.snapshot(targetNew?intent.target():intent.stage());
            if(!old.fingerprint().equals(intent.beforeFingerprint())||!replacement.fingerprint().equals(intent.afterFingerprint()))throw new IllegalStateException("Recovery content fingerprint differs");
            if(entry.state()!=ReferencePublicationJournal.State.IN_DOUBT)entry=journal.advance(entry,ReferencePublicationJournal.State.IN_DOUBT);
            entry=journal.advance(entry,ReferencePublicationJournal.State.RESUMING);
            try{if(targetOld){target.rename(intent.target(),intent.backup());port.nativePort().awaitPublished(intent.backup(),intent.originalId(),intent.originalDirectory(),()->false);}
                if(!targetNew){target.rename(intent.stage(),intent.target());port.nativePort().awaitPublished(intent.target(),intent.replacementId(),replacement.directory(),()->false);}
                entry=journal.advance(entry,ReferencePublicationJournal.State.PUBLISHED);
                var actual=port.formalSnapshot();var backup=port.snapshot(intent.backup());
                if(actual.tableId()!=intent.replacementId()||backup.tableId()!=intent.originalId()||!actual.fingerprint().equals(intent.afterFingerprint())||!backup.fingerprint().equals(intent.beforeFingerprint()))
                    throw new IllegalStateException("Recovered formal or backup differs");entry=journal.advance(entry,ReferencePublicationJournal.State.VERIFIED);
            }catch(Exception failure){var current=journal.forRun(runId);if(current.state()!=ReferencePublicationJournal.State.VERIFIED&&current.state()!=ReferencePublicationJournal.State.IN_DOUBT)journal.advance(current,ReferencePublicationJournal.State.IN_DOUBT);throw failure;}
        }
        var actual=port.formalSnapshot();if(actual.tableId()!=intent.replacementId()||!actual.fingerprint().equals(intent.afterFingerprint()))throw new IllegalStateException("Verified publication drifted");
        int rows=source.path("rows").size();
        requirePublicationEvidence(sourceEvidence.resolveSibling("publication.json"),Map.of("published",true,"backup",intent.backup(),"formalRows",actual.rows().size(),
                "windowRows",rows,"fullTargetFingerprint",actual.fingerprint(),"sourceFingerprint",scope.path("sourceFingerprint").asText(),"sourceEvidence",sourceEvidence.toString()));
        String proof=JobDefinitionJson.mapper().writeValueAsString(Map.of("sourceComplete",true,"returnedRows",rows,"publication",intent.id(),
                "verification",Map.of("passed",true,"writerStopped",true,"expectedRows",rows,"actualRows",rows,"matchedRows",rows,"duplicateKeys",0,"missingKeys",0,"mismatchedRows",0,"sourceFingerprint",scope.path("sourceFingerprint").asText(),"readbackEvidence",intent.id())));
        for(var item:List.of(attempts.getFirst(),ledger.get(runId))){var current=ledger.get(item.id());if(current.state()==SyncRunState.VERIFIED)continue;
            if(current.state()==SyncRunState.RUNNING||current.state()==SyncRunState.ACKNOWLEDGED){ledger.transition(current.id(),current.revision(),SyncRunState.IN_DOUBT,"{\"publicationRecovery\":true}");current=ledger.get(current.id());}
            if(current.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("Recovery ledger state cannot complete: "+current.state());ledger.transition(current.id(),current.revision(),SyncRunState.VERIFIED,proof);}
        if(lease!=null){if(!lease.inDoubt()){locks.retainInDoubt(lease);lease=locks.findOwned(runId,lease.scope());}locks.releaseAfterReconciliation(lease,true,true);}
        return actual;
    }
    private boolean tableExists(String name){return target.tableExists(name);}
    private boolean identityMatches(String name,long id){return target.identityMatches(name,id);}
    private static String fileHash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static byte[] canonicalBytes(Object value)throws Exception{return JobDefinitionJson.canonicalMapper().writeValueAsBytes(value);}
    private static void requirePublicationEvidence(Path path,Object evidence)throws Exception{
        if(Files.exists(path)){
            if(!Files.isRegularFile(path)||!JobDefinitionJson.mapper().valueToTree(evidence).equals(JobDefinitionJson.mapper().readTree(path.toFile())))
                throw new IllegalStateException("Recovery publication evidence differs");
        }else writeEvidence(path,evidence);
    }
    private static void writeEvidence(Path path,Object evidence)throws Exception{byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(evidence);
        try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Sentiment calculation cancelled");}
    private static <T> Set<T> difference(Set<T> expected,Set<T> seen){var values=new HashSet<>(expected);values.removeAll(seen);return values;}
}
