package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;
import static com.zoutrankil.data.repository.BacktestDailyMaterializationPort.*;

/** One canonical owner of the enriched base and public native v_backtest_daily.
 * Monthly runner receipts certify all business rows without buffering a complete historical table.
 */
@Service
public final class BacktestDailyMaterializationJobService implements SyncJobOwner {
    public static final String JOB_ID="data.backtest_daily";
    public record Plan(FrozenRequest request,String targetId,Identity targetBefore,String sourcePin,boolean bootstrap,
                       String oldObjectKind,String oldObjectSql) {}
    public record MaterializationResult(SyncJobRunner.Result result,long sourceRows,long baseRows,long materializedRows,
                                        String sourceFingerprint,String windowFingerprint,String evidence,boolean bootstrap) {}
    public record Published(Identity base,NativeState materialized,String evidence) {}
    private final JdbcTemplate jdbc;private final Path ledgerPath;
    public BacktestDailyMaterializationJobService(JdbcTemplate jdbc,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(1800);ledgerPath=Path.of(ledger).toAbsolutePath().normalize();
    }
    @Override public String datasetId(){return BASE;}
    @Override public Set<Mode> supportedSyncModes(){return jobDefinition().supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(jobDefinition());}
    public static SyncJobDefinition jobDefinition(){
        var p=new LinkedHashMap<String,Parameter>();p.put("source_pin",new Parameter(ParameterType.STRING,true,64,1,Set.of()));p.put("target_id",new Parameter(ParameterType.STRING,true,128,1,Set.of()));
        p.put("target_signature",new Parameter(ParameterType.STRING,true,64,1,Set.of()));p.put("bootstrap",new Parameter(ParameterType.BOOLEAN,true,5,1,Set.of()));
        return new SyncJobDefinition(JOB_ID,1,BASE,1,"backtest_daily_owner",Set.of(Mode.MATERIALIZE),Mode.MATERIALIZE,p,"questdb.materialize","backtest_daily.month_receipts","questdb.full_key_values",
                new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(1)),Duration.ofHours(24),new Budget(36600,1200,1200,1200,1024*1024),0,List.of(),Frequency.DAILY,ZoneId.of("Asia/Shanghai"),true,true);
    }
    public Plan bootstrapPlan(LocalDate logicalDate)throws Exception{return prepare(null,null,logicalDate,true);}
    public Plan plan(LocalDate from,LocalDate to,LocalDate logicalDate)throws Exception{
        if(from==null||to==null||from.isAfter(to)||ChronoUnit.DAYS.between(from,to)>=366)throw new IllegalArgumentException("Explicit from/to up to366 days required");
        // The first run must bootstrap all source history, even if a caller asks only for the last date.
        return prepare(from,to,logicalDate,!hasVerifiedPublication());
    }
    private Plan prepare(LocalDate from,LocalDate to,LocalDate logicalDate,boolean bootstrap)throws Exception{
        Objects.requireNonNull(logicalDate);requireNoPending();var port=new BacktestDailyMaterializationPort(jdbc);var before=port.identity(BASE);String pin=port.sourcePin();
        String kind=port.targetKind(),sql=port.targetSql();if(kind.equals("V")){if(!normalized(SOURCE_SQL).equals(normalized(sql)))throw new IllegalStateException("Existing ordinary backtest SQL differs from canonical source");bootstrap=true;}
        else if(!kind.equals("M")||!normalized(MV_SQL).equals(normalized(sql)))throw new IllegalStateException("Existing public backtest object is not the approved view or native definition");
        if(bootstrap){Bounds all=port.sourceBounds();from=all.from();to=all.to();}if(to.isAfter(logicalDate))throw new IllegalArgumentException("Backtest cutoff exceeds logical date");
        if(!pin.equals(port.sourcePin())||!before.equals(port.identity(BASE)))throw new IllegalStateException("Sources/target changed while planning");
        var request=jobDefinition().freeze(Mode.MATERIALIZE,Map.of("source_pin",pin,"target_id",port.targetId(before),"target_signature",before.fingerprint(),"bootstrap",bootstrap),from,to,logicalDate);
        return new Plan(request,port.targetId(before),before,pin,bootstrap,kind,sql);
    }
    public MaterializationResult run(Plan plan)throws Exception{
        if(plan==null||!jobDefinition().equals(plan.request().definition())||plan.request().mode()!=Mode.MATERIALIZE||!plan.sourcePin().equals(plan.request().parameters().get("source_pin"))
                ||!plan.targetId().equals(plan.request().parameters().get("target_id"))||!plan.targetBefore().fingerprint().equals(plan.request().parameters().get("target_signature"))||!Boolean.valueOf(plan.bootstrap()).equals(plan.request().parameters().get("bootstrap")))throw new IllegalArgumentException("Exact frozen backtest materialization plan required");
        if(!plan.bootstrap()&&ChronoUnit.DAYS.between(plan.request().from(),plan.request().to())>=366)throw new IllegalArgumentException("Normal materialization span exceeds366 days");
        var ledger=new SyncRunLedger(ledgerPath);String run="backtest-materialize-"+UUID.randomUUID();var adapter=new Adapter(plan,run);var runner=new SyncJobRunner<MonthReceipt,LocalDate>(ledger,new DatasetIntervalLock(ledgerPath));
        var result=runner.run(run,null,plan.targetId(),plan.request(),adapter,()->Thread.currentThread().isInterrupted());
        return new MaterializationResult(result,adapter.sourceRows,adapter.baseRows,adapter.nativeRows,plan.sourcePin(),adapter.windowHash,adapter.evidence==null?null:adapter.evidence.toString(),plan.bootstrap());
    }
    private final class Adapter implements SyncJobRunner.Adapter<MonthReceipt,LocalDate>{
        final Plan plan;final String run;final BacktestDailyMaterializationPort port;long sourceRows,baseRows,nativeRows;String windowHash;Path evidence;boolean journaled;
        Adapter(Plan plan,String run){this.plan=plan;this.run=run;port=new BacktestDailyMaterializationPort(jdbc);}
        public DatasetIntervalLock.Scope conflictScope(FrozenRequest request){return DatasetIntervalLock.Scope.allDates(BASE);}
        public void preflight(FrozenRequest request)throws Exception{requireNoPending();port.requireSame(plan.targetBefore());requirePin(plan.sourcePin());if(!port.targetKind().equals(plan.oldObjectKind())||!normalized(port.targetSql()).equals(normalized(plan.oldObjectSql())))throw new IllegalStateException("Public backtest object changed after planning");}
        public VerifiedBatchExecutor.Codec<MonthReceipt,LocalDate> codec(){return CODEC;}
        public VerifiedBatchExecutor.Port<MonthReceipt,LocalDate> port(){return port;}
        public boolean recoveryRequired(String ignored)throws Exception{return journaled&&new ReferencePublicationJournal(ledgerPath,BASE).forRun(run).state()!=ReferencePublicationJournal.State.VERIFIED;}
        public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<MonthReceipt> consumer,BooleanSupplier stopped)throws Exception{
            String stage="java_backtest_daily_stage_"+nonce(),backup="java_backtest_daily_backup_"+nonce(),nativeBackup="java_backtest_daily_native_backup_"+nonce();
            port.freeze(plan.targetBefore(),request.from(),request.to(),plan.bootstrap(),stage,stopped);
            var expected=new ArrayList<MonthReceipt>();var oldMonths=new ArrayList<MonthReceipt>();
            // Read and validate every raw key before CTAS DEDUP could hide any duplicated input.
            for(LocalDate lo=request.from();!lo.isAfter(request.to());){port.check();LocalDate hi=lo.plusMonths(1).withDayOfMonth(1).minusDays(1);if(hi.isAfter(request.to()))hi=request.to();
                expected.add(port.sourceDigest(lo,hi));if(!plan.bootstrap())oldMonths.add(port.digest(BASE,lo,hi,true));lo=hi.plusDays(1);}
            sourceRows=expected.stream().mapToLong(MonthReceipt::rows).sum();if(sourceRows<1||sourceRows>MAX_TOTAL_ROWS)throw new IllegalStateException("Complete nonempty backtest business source required");
            if(plan.bootstrap()){
                Bounds all=port.sourceBounds();if(!all.from().equals(request.from())||!all.to().equals(request.to())||all.rows()>sourceRows)throw new IllegalStateException("Bootstrap does not cover actual entire source history");
                oldMonths.addAll(port.fullDigest(BASE,plan.targetBefore()));
            }
            requirePin(plan.sourcePin());port.requireSame(plan.targetBefore());
            Path root=ledgerPath.getParent().resolve("sync-evidence").resolve(run);evidence=root.resolve("source.json");windowHash=fullHash(expected);
            writeEvidence(evidence,Map.ofEntries(Map.entry("producer","java.backtest_daily.materialization"),Map.entry("from",request.from()),Map.entry("to",request.to()),Map.entry("bootstrap",plan.bootstrap()),
                    Map.entry("sourcePin",plan.sourcePin()),Map.entry("canonicalSql",SOURCE_SQL),Map.entry("nativeSql",MV_SQL),Map.entry("sourceBusinessRows",sourceRows),Map.entry("monthReceipts",expected),
                    Map.entry("oldMonthReceipts",oldMonths),Map.entry("oldObjectKind",plan.oldObjectKind()),Map.entry("oldObjectSql",plan.oldObjectSql()),Map.entry("sourceComplete",true)));
            for(var month:expected){Path file=root.resolve("source-"+month.from()+".json");writeEvidence(file,Map.of("receipt",month,"sourcePin",plan.sourcePin(),"sourceComplete",true,"fields",COLUMNS));
                consumer.accept(new SyncJobRunner.Page<>(List.of(month),sha(new String(CODEC.canonicalBytes(month),java.nio.charset.StandardCharsets.UTF_8)),file.toString(),month.from().toString()));}
            port.verifyReplacement(oldMonths,expected);requirePin(plan.sourcePin());port.requireSame(plan.targetBefore());
            List<Map<String,Object>> outside=port.outsidePartitions(request.from(),request.to());
            if(!plan.bootstrap()){
                port.createWindowBackup(backup);for(var old:oldMonths)if(!old.equals(port.digest(backup,old.from(),old.to(),true)))throw new IllegalStateException("Durable old window backup differs");
            }
            var stageIdentity=port.identity(stage);var scope=new LinkedHashMap<String,Object>();scope.put("from",request.from());scope.put("to",request.to());scope.put("bootstrap",plan.bootstrap());scope.put("sourcePin",plan.sourcePin());
            scope.put("sourceEvidence",evidence.toString());scope.put("sourceEvidenceSha256",fileHash(evidence));scope.put("sourceMonths",expected);scope.put("oldMonths",oldMonths);scope.put("outsidePartitions",outside);
            scope.put("before",plan.targetBefore());scope.put("stageIdentity",stageIdentity);scope.put("oldObjectKind",plan.oldObjectKind());scope.put("oldObjectSql",plan.oldObjectSql());scope.put("nativeBackup",nativeBackup);scope.put("oldObjectId",port.targetObjectId());
            var journal=new ReferencePublicationJournal(ledgerPath,BASE);var lease=new DatasetIntervalLock(ledgerPath).findOwned(run,DatasetIntervalLock.Scope.allDates(BASE));if(lease==null)throw new IllegalStateException("Whole backtest publication lease absent");journal.requireLease(lease,false);
            var intent=new ReferencePublicationJournal.Intent("backtest-publication-"+UUID.randomUUID(),BASE,run,BASE,backup,stage,plan.targetId(),plan.targetBefore().id(),plan.targetBefore().directory(),plan.bootstrap()?stageIdentity.id():plan.targetBefore().id(),fullHash(oldMonths),windowHash,JobDefinitionJson.mapper().writeValueAsString(scope));
            var entry=journal.create(intent);journaled=true;
            try{
                Published finished=publish(entry,lease,port,false,stopped);baseRows=finished.base().rows();nativeRows=baseRows;
            }catch(Exception failure){var current=journal.forRun(run);if(current.state()!=ReferencePublicationJournal.State.VERIFIED&&current.state()!=ReferencePublicationJournal.State.IN_DOUBT)journal.advance(current,ReferencePublicationJournal.State.IN_DOUBT);throw failure;}
            return new SyncJobRunner.SourceCompletion(expected.size(),expected.size(),true,evidence.toString());
        }
    }
    /** Idempotent forward recovery uses retained full-base/window tables and never trusts an elapsed timeout. */
    public Published finishInterrupted(String runId,boolean writerStopped)throws Exception{
        if(!writerStopped)throw new IllegalArgumentException("Explicit stopped-writer proof required for recovery");var journal=new ReferencePublicationJournal(ledgerPath,BASE);var entry=journal.forRun(runId);var locks=new DatasetIntervalLock(ledgerPath);var lease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates(BASE));
        if(lease==null)throw new IllegalStateException("Original retained backtest lease missing");journal.requireLease(lease,true);var port=new BacktestDailyMaterializationPort(jdbc);
        var ledger=new SyncRunLedger(ledgerPath);var original=ledger.getRun(runId);if(!JOB_ID.equals(original.jobId())||original.jobVersion()!=1||!original.targetId().equals(entry.intent().initialTarget()))throw new IllegalStateException("Original backtest run owner/version/target differs");
        var children=new ArrayList<SyncRunLedger.Entry>();String cursor=null;while(true){var page=ledger.entries(runId,cursor,1000);children.addAll(page);if(children.size()>1300)throw new IllegalStateException("Recovery ledger slice budget exceeded");if(page.size()<1000)break;cursor=page.getLast().id();}
        var attempts=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).toList();var slices=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();var scope=JobDefinitionJson.mapper().readTree(entry.intent().scope());int months=scope.path("sourceMonths").size();
        if(attempts.size()!=1||slices.size()!=months||slices.stream().anyMatch(e->e.state()!=SyncRunState.VERIFIED))throw new IllegalStateException("Recovery requires every original month slice VERIFIED and one owning attempt");
        Published result;try{result=publish(entry,lease,port,true,()->Thread.currentThread().isInterrupted());}catch(Exception failure){var current=journal.forRun(runId);if(current.state()!=ReferencePublicationJournal.State.VERIFIED&&current.state()!=ReferencePublicationJournal.State.IN_DOUBT)journal.advance(current,ReferencePublicationJournal.State.IN_DOUBT);throw failure;}
        String proof=JobDefinitionJson.mapper().writeValueAsString(Map.of("sourceComplete",true,"returnedRows",months,"publicationRecovered",true,"baseBusinessRows",result.base().rows(),"verification",Map.of("passed",true,"writerStopped",true,"expectedRows",months,"actualRows",months,"matchedRows",months,"missingKeys",0,"duplicateKeys",0,"mismatchedRows",0,"sourceFingerprint",scope.path("sourcePin").asText(),"readbackEvidence",result.evidence())));
        for(var item:List.of(attempts.getFirst(),ledger.get(runId))){var current=ledger.get(item.id());if(current.state()==SyncRunState.VERIFIED)continue;if(current.state()==SyncRunState.RUNNING||current.state()==SyncRunState.ACKNOWLEDGED){ledger.transition(current.id(),current.revision(),SyncRunState.IN_DOUBT,"{\"publicationRecovery\":true}");current=ledger.get(current.id());}if(current.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("Only original uncertain owner can finish publication");ledger.transition(current.id(),current.revision(),SyncRunState.VERIFIED,proof);}
        var current=locks.findOwned(runId,lease.scope());if(current!=null){if(!current.inDoubt()){locks.retainInDoubt(current);current=locks.findOwned(runId,lease.scope());}locks.releaseAfterReconciliation(current,true,true);}return result;
    }
    private Published publish(ReferencePublicationJournal.Entry entry,DatasetIntervalLock.Lease lease,BacktestDailyMaterializationPort port,boolean recovery,BooleanSupplier stopped)throws Exception{
        var journal=new ReferencePublicationJournal(ledgerPath,BASE);journal.requireLease(lease,recovery);var intent=entry.intent();var json=JobDefinitionJson.mapper();var scope=json.readTree(intent.scope());
        LocalDate from=LocalDate.parse(scope.path("from").asText()),to=LocalDate.parse(scope.path("to").asText());boolean bootstrap=scope.path("bootstrap").asBoolean();String pin=scope.path("sourcePin").asText();requirePin(pin);
        Path source=Path.of(scope.path("sourceEvidence").asText()).toAbsolutePath().normalize();Path owned=ledgerPath.getParent().resolve("sync-evidence").resolve(intent.runId()).toAbsolutePath().normalize();
        if(!source.startsWith(owned)||!fileHash(source).equals(scope.path("sourceEvidenceSha256").asText()))throw new IllegalStateException("Publication source evidence path/SHA changed");
        List<MonthReceipt> expected=json.convertValue(scope.path("sourceMonths"),json.getTypeFactory().constructCollectionType(List.class,MonthReceipt.class));
        List<MonthReceipt> old=json.convertValue(scope.path("oldMonths"),json.getTypeFactory().constructCollectionType(List.class,MonthReceipt.class));
        var before=json.treeToValue(scope.path("before"),Identity.class);port.freeze(before,from,to,bootstrap,intent.stage(),stopped);
        var original=SyncRunLedger.openReadOnly(ledgerPath).getRun(intent.runId());var frozen=json.readTree(original.frozenJson());
        if(!JOB_ID.equals(original.jobId())||original.jobVersion()!=1||!BASE.equals(intent.dataset())||!BASE.equals(intent.target())||!original.targetId().equals(intent.initialTarget())||!port.targetId(before).equals(intent.initialTarget())||!"MATERIALIZE".equals(frozen.path("mode").asText())||!from.toString().equals(frozen.path("from").asText())||!to.toString().equals(frozen.path("to").asText())||!pin.equals(frozen.path("parameters").path("source_pin").asText())||!before.fingerprint().equals(frozen.path("parameters").path("target_signature").asText()))throw new IllegalStateException("Original frozen owner, endpoint and physical target binding required");
        if(!fullHash(expected).equals(intent.afterFingerprint())||!fullHash(old).equals(intent.beforeFingerprint()))throw new IllegalStateException("Publication receipts differ from durable intent");
        if(entry.state()==ReferencePublicationJournal.State.IN_DOUBT)entry=journal.advance(entry,ReferencePublicationJournal.State.RESUMING);
        Path completed=owned.resolve("publication.json");if(Files.exists(completed)){var proof=json.readTree(completed.toFile());if(!json.writeValueAsString(port.identity(BASE)).equals(proof.path("base").toString())||!json.writeValueAsString(port.nativeState(MV)).equals(proof.path("native").toString())||!pin.equals(proof.path("sourcePin").asText())||!fileHash(source).equals(proof.path("sourceEvidenceSha256").asText()))throw new IllegalStateException("Retained completed publication drifted");for(var month:expected)if(!month.equals(port.digest(BASE,month.from(),month.to(),true))||!month.equals(port.digest(MV,month.from(),month.to(),false)))throw new IllegalStateException("Completed publication monthly values drifted");recordFinalProof(intent.id(),intent.runId(),completed);if(entry.state()==ReferencePublicationJournal.State.RESUMING)entry=journal.advance(entry,ReferencePublicationJournal.State.PUBLISHED);if(entry.state()==ReferencePublicationJournal.State.PUBLISHED)entry=journal.advance(entry,ReferencePublicationJournal.State.VERIFIED);if(entry.state()!=ReferencePublicationJournal.State.VERIFIED)throw new IllegalStateException("Completed proof has inconsistent journal phase");return new Published(port.identity(BASE),port.nativeState(MV),completed.toString());}
        var staged=port.optionalIdentity(intent.stage());if(staged.isPresent()){if(!json.writeValueAsString(staged.get()).equals(scope.path("stageIdentity").toString()))throw new IllegalStateException("Original complete source stage identity/version changed");for(var month:expected)if(!month.equals(port.digest(intent.stage(),month.from(),month.to(),true)))throw new IllegalStateException("Original source stage month receipt changed");}
        if(bootstrap){
            var formal=port.optionalIdentity(BASE);var backup=port.optionalIdentity(intent.backup());var stage=port.optionalIdentity(intent.stage());
            if(formal.isPresent()&&formal.get().id()==intent.originalId()&&backup.isEmpty()&&stage.isPresent()&&stage.get().id()==intent.replacementId()){
                if(!fullHash(port.fullDigest(BASE,formal.get())).equals(intent.beforeFingerprint()))throw new IllegalStateException("Full old base differs from its retained evidence");port.rename(BASE,intent.backup());
                if(entry.state()==ReferencePublicationJournal.State.PREPARED)entry=journal.advance(entry,ReferencePublicationJournal.State.OLD_MOVED);
            }
            formal=port.optionalIdentity(BASE);backup=port.optionalIdentity(intent.backup());stage=port.optionalIdentity(intent.stage());
            if(entry.state()==ReferencePublicationJournal.State.PREPARED&&backup.isPresent()&&backup.get().id()==intent.originalId())entry=journal.advance(entry,ReferencePublicationJournal.State.OLD_MOVED);
            if(formal.isEmpty()&&backup.isPresent()&&backup.get().id()==intent.originalId()&&stage.isPresent()&&stage.get().id()==intent.replacementId())port.rename(intent.stage(),BASE);
            formal=port.optionalIdentity(BASE);backup=port.optionalIdentity(intent.backup());if(formal.isEmpty()||formal.get().id()!=intent.replacementId()||backup.isEmpty()||backup.get().id()!=intent.originalId()
                    ||!fullHash(port.fullDigest(intent.backup(),backup.get())).equals(intent.beforeFingerprint()))throw new IllegalStateException("Exact bootstrap physical base/backup binding required");
            // The old ordinary view has no physical business rows; its SQL is fsynced in source evidence.
            String kind=port.targetKind();if(!kind.equals("ABSENT")&&port.targetObjectId()==scope.path("oldObjectId").asLong())
                port.removeOldDefinitionForBootstrap(scope.path("oldObjectKind").asText(),scope.path("oldObjectSql").asText(),scope.path("nativeBackup").asText());
        }else{
            var formal=port.identity(BASE);if(formal.id()!=intent.originalId())throw new IllegalStateException("Window recovery must retain base physical identity");
            var retained=port.identity(intent.backup());for(MonthReceipt receipt:old)if(!receipt.equals(port.digest(intent.backup(),receipt.from(),receipt.to(),true)))throw new IllegalStateException("Retained window backup changed");
            if(entry.state()!=ReferencePublicationJournal.State.VERIFIED){port.replaceWindowInPlace(intent.stage(),from,to);if(entry.state()==ReferencePublicationJournal.State.PREPARED)entry=journal.advance(entry,ReferencePublicationJournal.State.OLD_MOVED);}
            // Compare canonical JSON text: SQLite-decoded small LONG counters may be Integer carriers.
            if(!scope.path("outsidePartitions").toString().equals(json.writeValueAsString(port.outsidePartitions(from,to))))throw new IllegalStateException("Unrequested DAY partitions changed during window publication");
            long oldRows=old.stream().mapToLong(MonthReceipt::rows).sum(),newRows=expected.stream().mapToLong(MonthReceipt::rows).sum();if(port.identity(BASE).rows()!=before.rows()-oldRows+newRows)throw new IllegalStateException("Complete base count differs from exact window replacement");
        }
        if(entry.state()==ReferencePublicationJournal.State.OLD_MOVED||entry.state()==ReferencePublicationJournal.State.RESUMING)entry=journal.advance(entry,ReferencePublicationJournal.State.PUBLISHED);
        if(entry.state()!=ReferencePublicationJournal.State.PUBLISHED&&entry.state()!=ReferencePublicationJournal.State.VERIFIED)throw new IllegalStateException("Unexpected durable backtest publication phase");
        for(MonthReceipt receipt:expected)if(!receipt.equals(port.digest(BASE,receipt.from(),receipt.to(),true)))throw new IllegalStateException("Published base fields/keys differ from original source");
        if(bootstrap&&port.identity(BASE).rows()!=expected.stream().mapToLong(MonthReceipt::rows).sum())throw new IllegalStateException("Bootstrap left missing/extra historical rows");
        requirePin(pin);Path output=owned.resolve("publication.json");
        // Once the immutable final proof exists, recovery verifies it instead of submitting another asynchronous FULL.
        if(Files.exists(output)){var proof=json.readTree(output.toFile());if(!json.writeValueAsString(port.identity(BASE)).equals(proof.path("base").toString())||!json.writeValueAsString(port.nativeState(MV)).equals(proof.path("native").toString())||!pin.equals(proof.path("sourcePin").asText()))throw new IllegalStateException("Retained final publication proof differs from current objects");}
        else {port.installAndVerifyNative(MV,expected,bootstrap||old.stream().mapToLong(MonthReceipt::rows).sum()>0);requirePin(pin);writeEvidence(output,Map.of("producer","java.backtest_daily.materialization","base",port.identity(BASE),"native",port.nativeState(MV),"sourceEvidenceSha256",fileHash(source),"sourcePin",pin,"bootstrap",bootstrap,"sourceBusinessRows",expected.stream().mapToLong(MonthReceipt::rows).sum(),"monthReceipts",expected));}
        var base=port.identity(BASE);var nativeState=port.nativeState(MV);recordFinalProof(intent.id(),intent.runId(),output);requirePin(pin);if(!base.equals(port.identity(BASE))||!nativeState.equals(port.nativeState(MV)))throw new IllegalStateException("Published objects changed before final journal verification");
        if(entry.state()!=ReferencePublicationJournal.State.VERIFIED)journal.advance(entry,ReferencePublicationJournal.State.VERIFIED);return new Published(base,nativeState,output.toString());
    }
    private void requirePin(String expected){if(!expected.matches("[0-9a-f]{64}")||!expected.equals(new BacktestDailyMaterializationPort(jdbc).sourcePin()))throw new IllegalStateException("Complete four-source historical pin changed");}
    public SyncRunLedger.Entry status(String runId)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(runId);}
    private void recordFinalProof(String publication,String run,Path output)throws Exception{try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledgerPath);var s=db.createStatement()){s.execute("PRAGMA synchronous=FULL");s.execute("CREATE TABLE IF NOT EXISTS backtest_publication_proofs (publication_id TEXT PRIMARY KEY REFERENCES reference_publications(id),run_id TEXT NOT NULL,evidence_path TEXT NOT NULL,evidence_sha256 TEXT NOT NULL)");try(var insert=db.prepareStatement("INSERT INTO backtest_publication_proofs VALUES(?,?,?,?) ON CONFLICT(publication_id) DO NOTHING")){insert.setString(1,publication);insert.setString(2,run);insert.setString(3,output.toString());insert.setString(4,fileHash(output));insert.executeUpdate();}try(var q=db.prepareStatement("SELECT run_id,evidence_path,evidence_sha256 FROM backtest_publication_proofs WHERE publication_id=?")){q.setString(1,publication);try(var r=q.executeQuery()){if(!r.next()||!run.equals(r.getString(1))||!output.toString().equals(r.getString(2))||!fileHash(output).equals(r.getString(3))||r.next())throw new IllegalStateException("Immutable final publication attestation differs");}}}}
    private boolean hasVerifiedPublication()throws Exception{return !publicationRows("state='VERIFIED'").isEmpty();}
    private void requireNoPending()throws Exception{if(!publicationRows("state<>'VERIFIED'").isEmpty())throw new IllegalStateException("Backtest has an interrupted publication; use explicit stopped-writer recovery");}
    private List<String> publicationRows(String predicate)throws Exception{
        if(!Files.isRegularFile(ledgerPath))return List.of();try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledgerPath.toUri().toASCIIString()+"?mode=ro");var s=db.createStatement()){
            s.execute("PRAGMA query_only=ON");try(var r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='reference_publications'")){if(!r.next())return List.of();}
            try(var r=s.executeQuery("SELECT id FROM reference_publications WHERE dataset='backtest_daily' AND "+predicate+" LIMIT 1")){var ids=new ArrayList<String>();while(r.next())ids.add(r.getString(1));return ids;}}
    }
    private static String nonce(){return UUID.randomUUID().toString().replace("-","");}
    public static String fileHash(Path path)throws Exception{try(var input=Files.newInputStream(path)){var h=java.security.MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];for(int n;(n=input.read(b))>=0;)h.update(b,0,n);return HexFormat.of().formatHex(h.digest());}}
    private static void writeEvidence(Path path,Object body)throws Exception{Files.createDirectories(path.getParent());byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(body);Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}}
}
