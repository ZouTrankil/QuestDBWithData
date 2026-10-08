package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;


import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.RegimeFeaturesMonitorDailyRow;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.derived.domain.RegimeFeaturesMonitorDailySourceData.Margins;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;
import static com.zoutrankil.data.domain.policy.RegimeFeaturesMonitorDailyCalculation.*;
import static com.zoutrankil.data.domain.policy.MarketSentimentDailyCalculation.finite;

/** Native Java owner for the existing 21-field proxy-style context table.
 * The persistent batch synchronizes dependencies before invoking this calculation.
 * The calculation reads pinned inputs and publishes a verified date window.
 */
public final class RegimeFeaturesMonitorDailyJobService implements DatasetImplementation,SyncJobOwner {
    public static final String JOB_ID="data.regime_features_monitor_daily";
    public static final String PRODUCER="java.regime_features_monitor_daily";
    private static final String FORMAL="regime_features_monitor_daily",PREFIX="java_regime_features_monitor_daily";
    public static final List<String> SOURCES=List.of("stk_factor","daily_basic","stk_limit","stk_suspend","stk_st_daily","margin_detail","moneyflow_hsgt","cn_bond_yield_curve","exchange_calendar");
    // Every required input now has a registered Java owner, including ChinaBond.
    public static final List<String> REGISTERED_DEPENDENCIES=SOURCES;
    public record Plan(FrozenRequest request,String targetId,LocalDate warmupFrom,List<String> dependencies,String sourcePin,
                       NativeDailyWindowSnapshot<RegimeFeaturesMonitorDailyRow> targetBefore) {}
    public record MaterializationResult(SyncJobRunner.Result result,int historyDates,long sourceRawRows,String sourceFingerprint,String fullTargetFingerprint,String evidence) {}
    private record Computed(List<RegimeFeaturesMonitorDailyRow> rows,int historyDates,long panelRows,long valuationRows,String fingerprint,
                            List<Map<String,Object>> panelCoverage,List<Map<String,Object>> marginAvailability,List<LocalDate> expectedDates,
                            List<LocalDate> ignoredGovDates,int northboundDates,int valuationDates,int govDates) {}
    private final RegimeFeaturesMonitorDailySourceReadPort storage;
    private final RegimeFeaturesMonitorDailyTarget target;
    private final java.util.function.Function<NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow>,NativeDailyWindowPublication<RegimeFeaturesMonitorDailyRow>> publications;
    private final Path ledgerPath;private final String table;
    public RegimeFeaturesMonitorDailyJobService(RegimeFeaturesMonitorDailySourceReadPort storage,RegimeFeaturesMonitorDailyTarget target,
            java.util.function.Function<NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow>,NativeDailyWindowPublication<RegimeFeaturesMonitorDailyRow>> publications,
            String ledger,String table) {
        this.storage=Objects.requireNonNull(storage);this.target=Objects.requireNonNull(target);this.publications=Objects.requireNonNull(publications);
        ledgerPath=Path.of(ledger).toAbsolutePath().normalize();NativeDailyWindowRules.requireTarget(table,FORMAL,PREFIX);this.table=table;
    }
    private NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> port(){return target.writer(table);}
    private NativeDailyWindowPublication<RegimeFeaturesMonitorDailyRow> publication(NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> port){return publications.apply(port);}
    private String sourcePin()throws Exception{return storage.sourcePin();}
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
                "21 existing fields; bounded full-row window replacement retaining outside rows and backup. Warmup=min(month start,from-20 days); qcut size/PB proxy style, monthly compounds, rolling five observations, full warmup-window valuation ranks. Required input-package context fields are finite; optional incomplete-market margin is NULL with separate availability evidence, while the inherited data_quality_flag remains proxy_style. Gov/10Y cn_bond_yield_curve is a required pinned input synchronized by its registered ChinaBond owner before calculation.");
    }
    public static SyncJobDefinition jobDefinition(){
        var parameters=new LinkedHashMap<String,Parameter>();
        parameters.put("target_id",new Parameter(ParameterType.STRING,true,128,1,Set.of()));parameters.put("source_pin",new Parameter(ParameterType.STRING,true,64,1,Set.of()));
        parameters.put("target_hash",new Parameter(ParameterType.STRING,true,64,1,Set.of()));parameters.put("warmup_from",new Parameter(ParameterType.DATE,true,10,1,Set.of()));
        return new SyncJobDefinition(JOB_ID,3,FORMAL,1,"regime_features_monitor_daily_owner",Set.of(Mode.MATERIALIZE),Mode.MATERIALIZE,
                parameters,"questdb.materialize","regime_features_monitor_daily.range366","questdb.full_key_values",
                new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(1)),Duration.ofMinutes(20),new Budget(366,1,1,366,1024*1024),0,List.of(),Frequency.DAILY,ZoneId.of("Asia/Shanghai"),true,true);
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
        final Plan plan;final String run;final NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> write;int historyDates;long rawRows;
        String sourceFingerprint,finalFingerprint;Path evidence;
        Adapter(Plan plan,String run,NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> write){this.plan=plan;this.run=run;this.write=write;}
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
        MessageDigest hash=MessageDigest.getInstance("SHA-256");var calendar=storage.calendar(plan.warmupFrom,plan.request.to(),hash);
        var expectedOutput=new TreeSet<>(calendar.subSet(plan.request.from(),true,plan.request.to(),true));
        if(expectedOutput.isEmpty())throw new IllegalStateException("Requested window has no authoritative SSE trading dates");
        var stockDays=new ArrayList<Day>();var observedPanelDates=new TreeSet<LocalDate>();var panelCoverage=new ArrayList<Map<String,Object>>();long[] panelRows={0},valuationRows={0};
        var valuations=new TreeMap<LocalDate,Valuation>();
        for(LocalDate lower=plan.warmupFrom;!lower.isAfter(plan.request.to());){
            check(cancelled);LocalDate upper=lower.withDayOfMonth(1).plusMonths(1);if(upper.isAfter(plan.request.to().plusDays(1)))upper=plan.request.to().plusDays(1);
            storage.readPanelMonth(lower,upper,calendar,panelRows,hash,cancelled,
                    (date,stocks,codes,basic,limits)->finishPanelDay(date,stocks,codes,basic,limits,stockDays,observedPanelDates,panelCoverage));
            storage.readValuations(lower,upper,calendar,valuations,valuationRows,hash,cancelled);lower=upper;
        }
        if(!observedPanelDates.equals(calendar))throw new IllegalStateException("Warmup/output panel missing SSE trading dates: "+difference(calendar,observedPanelDates));
        styleAndBreadth(stockDays);check(cancelled);
        var north=storage.readNorthbound(plan.warmupFrom,plan.request.to(),hash);var margin=storage.readMargins(plan.warmupFrom,plan.request.to(),hash);
        var availability=marginAvailability(margin,hash);var excluded=new HashSet<LocalDate>();for(var item:availability)if(Boolean.FALSE.equals(item.get("admitted")))excluded.add((LocalDate)item.get("tradeDate"));
        var bonds=storage.readGov(plan.warmupFrom,plan.request.to(),hash);
        var merged=merge(stockDays,northbound(north),margin(margin.balances(),excluded),valuation(valuations,bonds));
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






    private static List<Map<String,Object>> marginAvailability(Margins margins,MessageDigest hash)throws Exception{
        var result=new ArrayList<Map<String,Object>>();for(var entry:margins.exchanges().entrySet()){
            var previous=margins.exchanges().headMap(entry.getKey(),false).descendingMap().entrySet().stream().limit(20).toList();
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


    private void requireNoPendingPublication()throws Exception{publication(port()).requireNoPendingPublication();}
    public LedgerReadModels.Entry status(String run)throws Exception{return LedgerReadModels.entry(SyncRunLedger.openReadOnly(ledgerPath).get(run));}
    public NativeDailyWindowSnapshot<RegimeFeaturesMonitorDailyRow> finishInterrupted(String runId,boolean writerStopped)throws Exception{
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);if(!JOB_ID.equals(run.jobId()))throw new IllegalArgumentException("Run belongs to another owner");
        return publication(port()).finishInterrupted(runId,writerStopped,PRODUCER,null);
    }





    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Native regime-monitor calculation cancelled");}
    private static <T> Set<T> difference(Set<T> expected,Set<T> seen){var result=new HashSet<>(expected);result.removeAll(seen);return result;}
    private static void hashLine(MessageDigest hash,String line){hash.update((line+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static byte[] canonicalBytes(Object value)throws Exception{return JobDefinitionJson.canonicalMapper().writeValueAsBytes(value);}
    private static String fileHash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static void writeEvidence(Path path,Object value)throws Exception{byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(value);
        try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}}
}
