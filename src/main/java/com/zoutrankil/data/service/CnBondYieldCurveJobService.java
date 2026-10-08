package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.batch.ChinabondYieldSource;
import com.zoutrankil.batch.SourceContract;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.CnBondYieldCurveRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.repository.CnBondYieldCurveWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Bounded formal ChinaBond owner using the existing verified WebFlux source and native mapping.
 * It certifies SSE open dates within the frozen window. It never derives a checkpoint from MAX(date).
 */
@Service
public final class CnBondYieldCurveJobService implements DatasetImplementation,SyncJobOwner {
    public static final String DATASET="cn_bond_yield_curve",JOB_ID="data.cn_bond_yield_curve";
    private static final SourceContract SOURCE=SourceContract.load(DATASET);
    private static final DateTimeFormatter BASIC=DateTimeFormatter.BASIC_ISO_DATE;
    public record Plan(FrozenRequest request,String targetId,String targetWindowHash,List<LocalDate> tradeDates,String calendarHash) {
        public Plan{Objects.requireNonNull(request);Objects.requireNonNull(targetId);Objects.requireNonNull(targetWindowHash);Objects.requireNonNull(calendarHash);tradeDates=List.copyOf(tradeDates);}
    }
    private record Calendar(List<LocalDate> openDates,String hash){}
    private final JdbcTemplate jdbc;private final QuestDB questdb;private final ChinabondYieldSource source;private final Path ledgerPath;
    public CnBondYieldCurveJobService(JdbcTemplate jdbc,@Lazy QuestDB questdb,ChinabondYieldSource source,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String ledger){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(60);
        this.questdb=Objects.requireNonNull(questdb);this.source=Objects.requireNonNull(source);this.ledgerPath=Path.of(ledger).toAbsolutePath().normalize();
    }
    public String tableName(){return DATASET;}
    public String datasetId(){return DATASET;}
    public DatasetDefinition definition(){return CnBondYieldCurveWritePort.definition();}
    public Set<Mode> supportedSyncModes(){return Set.of(Mode.BACKFILL);}
    public List<SyncJobDefinition> syncJobDefinitions(){return List.of(jobDefinition());}
    public static SyncJobDefinition jobDefinition(){
        return new SyncJobDefinition(JOB_ID,1,DATASET,1,"cn_bond_yield_curve_owner",Set.of(Mode.BACKFILL),Mode.BACKFILL,
                Map.of("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                        "targetWindowHash",new Parameter(ParameterType.STRING,true,64,1,Set.of()),
                        "calendarHash",new Parameter(ParameterType.STRING,true,64,1,Set.of()),
                        "trade_dates",new Parameter(ParameterType.STRING,true,4000,1,Set.of())),
                "chinabond.shared","cn_bond_yield_curve.range31","questdb.full_key_values",
                new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(1)),Duration.ofMinutes(30),
                new Budget(31,31,31,31000,1024*1024),0,List.of(),Frequency.MANUAL,DailySyncEndDate.ZONE,true,false);
    }
    public Plan plan(LocalDate from,LocalDate to,LocalDate logicalDate)throws Exception{
        requireWindow(from,to,logicalDate);var calendar=calendar(from,to);String target=CnBondYieldCurveWritePort.targetId(jdbc);
        var port=new CnBondYieldCurveWritePort(jdbc,questdb,DATASET,target);String before=CnBondYieldCurveWritePort.fingerprint(port.readWindow(from,to));
        var request=jobDefinition().freeze(Mode.BACKFILL,Map.of("targetId",target,"targetWindowHash",before,"calendarHash",calendar.hash,
                "trade_dates",encodeDates(calendar.openDates)),from,to,logicalDate);
        return new Plan(request,target,before,calendar.openDates,calendar.hash);
    }
    public SyncJobRunner.Result run(LocalDate from,LocalDate to,LocalDate logicalDate)throws Exception{return run(plan(from,to,logicalDate));}
    public SyncJobRunner.Result run(Plan plan)throws Exception{
        Objects.requireNonNull(plan);requireWindow(plan.request.from(),plan.request.to(),plan.request.logicalDate());
        if(!plan.request.definition().equals(jobDefinition())||plan.request.mode()!=Mode.BACKFILL
                ||!plan.targetId.equals(plan.request.parameters().get("targetId"))
                ||!plan.targetWindowHash.equals(plan.request.parameters().get("targetWindowHash"))
                ||!plan.calendarHash.equals(plan.request.parameters().get("calendarHash"))
                ||!encodeDates(plan.tradeDates).equals(plan.request.parameters().get("trade_dates")))
            throw new IllegalArgumentException("Exact frozen ChinaBond formal plan required");
        String run="cn-bond-yield-"+UUID.randomUUID();var port=new CnBondYieldCurveWritePort(jdbc,questdb,DATASET,plan.targetId);
        var runner=new SyncJobRunner<CnBondYieldCurveRow,CnBondYieldCurveKey>(new SyncRunLedger(ledgerPath),new DatasetIntervalLock(ledgerPath));
        return runner.run(run,null,plan.targetId,plan.request,new Adapter(plan,run,port),()->Thread.currentThread().isInterrupted());
    }
    public SyncRunLedger.Entry status(String run)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(run);}
    public List<SyncRunLedger.Entry> entries(String run,String after,int limit)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).entries(run,after,limit);}
    public boolean cancel(String run)throws Exception{return new SyncRunLedger(ledgerPath).requestCancellation(run);}
    private final class Adapter implements SyncJobRunner.Adapter<CnBondYieldCurveRow,CnBondYieldCurveKey>{
        private final Plan plan;private final Path evidence;private final CnBondYieldCurveWritePort port;
        Adapter(Plan plan,String run,CnBondYieldCurveWritePort port){this.plan=plan;this.evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(run);this.port=port;}
        public void preflight(FrozenRequest request)throws Exception{
            port.preflight();var current=calendar(request.from(),request.to());
            if(!current.hash.equals(plan.calendarHash)||!current.openDates.equals(plan.tradeDates))throw new IllegalStateException("ChinaBond authoritative calendar changed after planning");
            if(!CnBondYieldCurveWritePort.fingerprint(port.readWindow(request.from(),request.to())).equals(plan.targetWindowHash))throw new IllegalStateException("ChinaBond formal window changed after planning");
        }
        public VerifiedBatchExecutor.Codec<CnBondYieldCurveRow,CnBondYieldCurveKey> codec(){return CnBondYieldCurveWritePort.CODEC;}
        public VerifiedBatchExecutor.Port<CnBondYieldCurveRow,CnBondYieldCurveKey> port(){return port;}
        public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<CnBondYieldCurveRow> consumer,BooleanSupplier cancelled)throws Exception{
            Files.createDirectories(evidence);var receipts=new ArrayList<Map<String,Object>>();int total=0;
            for(var date:plan.tradeDates){
                check(cancelled);String day=date.format(BASIC);var parameters=Map.<String,Object>of("start_date",day,"end_date",day);
                List<Map<String,JsonNode>> raw=List.of();
                try{
                    var page=source.fetcher().fetch(parameters);raw=page.rows();check(cancelled);
                    if(raw.isEmpty()||raw.size()>=SOURCE.pageSize()||raw.size()>SOURCE.maxRows())throw new IllegalStateException("SOURCE_INCOMPLETE: ChinaBond empty or source-cap response on "+date);
                    var mapped=SOURCE.prepareRows(raw,date,Set.of());var typed=new ArrayList<CnBondYieldCurveRow>();var keys=new HashSet<CnBondYieldCurveKey>();var curves=new HashSet<String>();
                    for(var row:mapped){
                        if(!row.keySet().equals(Set.of("trade_date","curve_name","curve_code","tenor","yield_value","source")))throw new IllegalArgumentException("ChinaBond normalized source field drift");
                        String returned=text(row,"trade_date");if(!day.equals(returned))throw new IllegalArgumentException("ChinaBond returned a different business date");
                        JsonNode yield=row.get("yield_value");if(yield==null||!yield.isNumber()||!Double.isFinite(yield.doubleValue()))throw new IllegalArgumentException("Finite ChinaBond yield required");
                        var value=new CnBondYieldCurveRow(date.atStartOfDay().toInstant(ZoneOffset.UTC),text(row,"curve_name"),text(row,"curve_code"),text(row,"tenor"),yield.doubleValue(),text(row,"source"));
                        if(!keys.add(CnBondYieldCurveWritePort.key(value)))throw new IllegalArgumentException("Duplicate ChinaBond curve-point source key");typed.add(value);curves.add(value.curveCode());
                    }
                    if(!curves.containsAll(Set.of("gov","aaa_mtn","aaa_bank"))||!keys.contains(new CnBondYieldCurveKey(date,"gov","10Y")))
                        throw new IllegalStateException("SOURCE_INCOMPLETE: expected ChinaBond curves or gov/10Y missing on "+date);
                    typed.sort(Comparator.comparing(CnBondYieldCurveWritePort::key));port.requireCompatibleDate(date,typed);
                    var body=new LinkedHashMap<String,Object>();body.put("producer","java.cn_bond_yield_curve");body.put("endpoint",SOURCE.endpoint());body.put("contractVersion",SOURCE.version());
                    body.put("providerDocumentation",SOURCE.sourceDocumentation());body.put("parameters",parameters);body.put("tradeDate",date);body.put("sourceVersion",page.sourceVersion());
                    body.put("rawRows",raw);body.put("rawRowCount",raw.size());body.put("rows",typed);body.put("sourceComplete",true);
                    body.put("legacySourceLabel","akshare retained by existing native SourceContract mapping; HTTP provider is ChinaBond");
                    byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(body);if(bytes.length>SOURCE.maxBytes())throw new IllegalStateException("ChinaBond receipt exceeds existing 8 MiB source bound");
                    String sha=sha(bytes);Path receipt=evidence.resolve("chinabond-"+day+"-"+sha+".json");Files.write(receipt,bytes,StandardOpenOption.CREATE_NEW);
                    consumer.accept(new SyncJobRunner.Page<>(typed,sha,receipt.toString(),day));check(cancelled);
                    if(!CnBondYieldCurveWritePort.fingerprint(typed).equals(CnBondYieldCurveWritePort.fingerprint(port.readWindow(date,date))))
                        throw new IllegalStateException("ChinaBond complete date full-key/all-six-field readback differs from source");
                    total=Math.addExact(total,typed.size());receipts.add(Map.of("tradeDate",date,"sourceFingerprint",sha,"responseEvidence",receipt.toString(),"rows",typed.size(),"curves",curves.stream().sorted().toList()));
                }catch(Exception failure){
                    try{Path incomplete=evidence.resolve("chinabond-unverified-"+day+"-"+UUID.randomUUID()+".json");
                        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(Map.of("endpoint",SOURCE.endpoint(),"parameters",parameters,"sourceComplete",false,"rawRows",raw,"errorType",failure.getClass().getSimpleName()));
                        if(bytes.length<=SOURCE.maxBytes())Files.write(incomplete,bytes,StandardOpenOption.CREATE_NEW);
                    }catch(Exception diagnostic){failure.addSuppressed(diagnostic);}throw failure;
                }
            }
            if(!calendar(request.from(),request.to()).hash.equals(plan.calendarHash))throw new IllegalStateException("ChinaBond SSE calendar changed before completion");
            Path complete=evidence.resolve("source-complete.json");Files.write(complete,JobDefinitionJson.mapper().writeValueAsBytes(Map.ofEntries(
                    Map.entry("dataset",DATASET),Map.entry("from",request.from()),Map.entry("to",request.to()),Map.entry("logicalDate",request.logicalDate()),
                    Map.entry("scope","SSE open dates; closed-date legacy rows are preserved"),Map.entry("calendarHash",plan.calendarHash),
                    Map.entry("expectedTradeDates",plan.tradeDates),Map.entry("sourceRows",total),Map.entry("sourceReceipts",receipts),Map.entry("sourceComplete",true))),StandardOpenOption.CREATE_NEW);
            return new SyncJobRunner.SourceCompletion(receipts.size(),total,true,complete.toString());
        }
    }
    private Calendar calendar(LocalDate from,LocalDate to)throws Exception{
        var all=new TreeMap<LocalDate,Integer>();
        jdbc.query("SELECT cast(cal_date AS long) AS cal_micros,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date LIMIT 33",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{Object timestamp=rs.getObject("cal_micros"),state=rs.getObject("is_open");
                    if(!(timestamp instanceof Number n)||!(state instanceof Number flag)||flag.intValue()!=0&&flag.intValue()!=1)throw new IllegalStateException("ChinaBond needs typed SSE calendar dates");
                    LocalDate date=TemporalValues.CalendarTimestamp.fromStorageEpoch(n.longValue(),TemporalValues.EpochUnit.MICROS).date();
                    if(date.isBefore(from)||date.isAfter(to)||all.put(date,flag.intValue())!=null)throw new IllegalStateException("SSE calendar duplicate/out-of-window date");
                },micros(from),micros(to.plusDays(1)));
        for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1))if(!all.containsKey(date))throw new IllegalStateException("SSE calendar missing authoritative date "+date);
        List<LocalDate> open=all.entrySet().stream().filter(e->e.getValue()==1).map(Map.Entry::getKey).toList();
        return new Calendar(open,sha(JobDefinitionJson.mapper().writeValueAsBytes(all)));
    }
    private static void requireWindow(LocalDate from,LocalDate to,LocalDate logicalDate){
        if(from==null||to==null||logicalDate==null||from.isAfter(to)||to.isAfter(logicalDate)||ChronoUnit.DAYS.between(from,to)>=31
                ||to.isAfter(DailySyncEndDate.resolve(null,ZonedDateTime.now(DailySyncEndDate.ZONE))))throw new IllegalArgumentException("ChinaBond requires explicit <=31-day completed-source BACKFILL within logicalDate");
    }
    private static String encodeDates(List<LocalDate> dates){return dates.isEmpty()?"NONE":String.join(",",dates.stream().map(d->d.format(BASIC)).toList());}
    private static long micros(LocalDate date){return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);}
    private static String text(Map<String,JsonNode> row,String field){var node=row.get(field);if(node==null||!node.isTextual()||node.asText().isBlank())throw new IllegalArgumentException("ChinaBond text field required: "+field);return node.asText();}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("ChinaBond bounded source cancelled");}
}
