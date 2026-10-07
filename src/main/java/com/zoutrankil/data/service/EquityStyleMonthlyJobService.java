package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.EquityStyleMonthlyMapper;
import com.zoutrankil.data.repository.EquityStyleMonthlyWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.DatasetPublicationReadModel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** D103 bounded Java monthly pivot, with actual source and full-prefix target verification. */
@Service
public final class EquityStyleMonthlyJobService implements SyncJobOwner {
    public static final String JOB_ID="data.equity_style_monthly";
    public record Plan(FrozenRequest request,String targetId,EquityStyleMonthlySource.Batch source,String checkpointParent) {
        public Plan(FrozenRequest request,String targetId,EquityStyleMonthlySource.Batch source){this(request,targetId,source,null);}
    }
    public record MaterializationResult(SyncJobRunner.Result result,long sourceRawRows,
            EquityStyleMonthlySource.Batch source,EquityStyleMonthlyTargetSnapshot target,String targetSnapshotError) {}
    public record Status(String runId,SyncRunState state,String targetId,String logicalDate,int verifiedRows,
            int unresolvedSlices,boolean cancellationRequested,EquityStyleMonthlyTargetSnapshot currentTarget,String currentTargetError) {}
    private final Path ledgerPath;
    private final EquityStyleMonthlySource source;
    private final Supplier<EquityStyleMonthlyWritePort> factory;
    private final String targetTable;
    private final Map<String,OwnedPublication> publications=new ConcurrentHashMap<>();
    private record OwnedPublication(Plan plan,EquityStyleMonthlyWritePort writer) {}

    @Autowired
    public EquityStyleMonthlyJobService(JdbcTemplate jdbc,QuestDbProperties properties,
            @Value("${app.sync.equity-style-monthly.source-table:index_monthly}") String sourceTable,
            @Value("${app.sync.equity-style-monthly.target-table:}") String targetTable,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath){
        this(jdbc,properties,sourceTable,targetTable,Path.of(ledgerPath));
    }
    public EquityStyleMonthlyJobService(JdbcTemplate jdbc,QuestDbProperties properties,String sourceTable,String targetTable,Path ledgerPath){
        this.ledgerPath=Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();
        this.source=new EquityStyleMonthlySource(jdbc,sourceTable);
        this.targetTable=targetTable==null?"":targetTable.trim();
        this.factory=()->new EquityStyleMonthlyWritePort(jdbc,properties,this.targetTable);
    }
    EquityStyleMonthlyJobService(Path ledgerPath,EquityStyleMonthlySource source,Supplier<EquityStyleMonthlyWritePort> factory){
        this.ledgerPath=Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();this.source=Objects.requireNonNull(source);
        this.factory=Objects.requireNonNull(factory);this.targetTable=null;
    }
    @Override public String datasetId(){return "equity_style_monthly";}
    @Override public Set<Mode> supportedSyncModes(){return definition().supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(definition());}
    public static SyncJobDefinition definition(){
        var p=new LinkedHashMap<String,Parameter>();
        p.put("source_version",new Parameter(ParameterType.STRING,true,128,1,Set.of()));
        p.put("source_hash",new Parameter(ParameterType.STRING,true,64,1,Set.of()));
        p.put("target_id",new Parameter(ParameterType.STRING,true,128,1,Set.of()));
        p.put("bootstrap_from",new Parameter(ParameterType.DATE,true,10,1,Set.of()));
        p.put("checkpoint_before",new Parameter(ParameterType.DATE,false,10,1,Set.of()));
        p.put("checkpoint_reason",new Parameter(ParameterType.STRING,false,64,1,Set.of("VERIFIED_PREFIX_APPEND","SOURCE_PREFIX_REVISED")));
        p.put("parent_source_version",new Parameter(ParameterType.STRING,false,128,1,Set.of()));
        return new SyncJobDefinition(JOB_ID,1,"equity_style_monthly",1,"equity_style_monthly_owner",
                Set.of(Mode.MATERIALIZE,Mode.INCREMENTAL,Mode.RECONCILE),Mode.INCREMENTAL,p,
                "questdb.materialize","equity_style_monthly.month_window","questdb.full_key_values",
                new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(1)),Duration.ofMinutes(5),
                new Budget(366,1,1,12,1024*1024),31,
                List.of(new JobRef(IndexMonthlySyncJobOwner.DEFINITION.jobId(),IndexMonthlySyncJobOwner.DEFINITION.version())),
                Frequency.MANUAL,ZoneId.of("Asia/Shanghai"),true,false);
    }
    public Plan plan(LocalDate bootstrapFrom,LocalDate toInclusive,LocalDate logicalDate,Mode mode)throws Exception{
        EquityStyleMonthlySource.requireClosedWindow(bootstrapFrom,toInclusive,logicalDate);
        mode=mode==null?definition().defaultMode():mode;if(!definition().supportedModes().contains(mode))throw new IllegalArgumentException("Unsupported D103 mode");
        requireNoPendingPublication();var writer=port();writer.preflight();String target=writer.targetId();
        var current=source.read(bootstrapFrom,toInclusive);
        if(mode==Mode.INCREMENTAL)EquityStyleMonthlySource.requireIncrementalCoverage(current.rows(),bootstrapFrom,toInclusive);
        LocalDate from=bootstrapFrom;
        var parameters=new LinkedHashMap<String,Object>();parameters.put("source_version",current.snapshot().version());
        parameters.put("source_hash",current.rawFingerprint());parameters.put("target_id",target);parameters.put("bootstrap_from",bootstrapFrom);
        Checkpoint cp=mode==Mode.INCREMENTAL?checkpoint(writer,target,bootstrapFrom,toInclusive):null;
        if(cp!=null){
            parameters.put("parent_source_version",cp.sourceVersion());
            if(cp.revised())parameters.put("checkpoint_reason","SOURCE_PREFIX_REVISED");
            else{from=cp.month().atDay(1);parameters.put("checkpoint_before",from);parameters.put("checkpoint_reason","VERIFIED_PREFIX_APPEND");}
        }
        // Prefix validation can perform another source read. Freeze only the same original complete census.
        var after=source.read(bootstrapFrom,toInclusive);
        if(!current.snapshot().equals(after.snapshot())||!current.rawFingerprint().equals(after.rawFingerprint()))throw new IllegalStateException("D103 source changed while selecting checkpoint");
        return new Plan(definition().freeze(mode,parameters,from,toInclusive,logicalDate),target,current,cp==null?null:cp.runId());
    }
    public MaterializationResult run(Plan plan)throws Exception{return execute(plan,null);}
    public MaterializationResult resume(String previousRunId)throws Exception{
        requireNoPendingPublication();var restored=FrozenRunRequest.restore(ledgerPath,previousRunId,definition());
        var anchor=(LocalDate)restored.request().parameters().get("bootstrap_from");
        return execute(new Plan(restored.request(),restored.targetId(),source.read(anchor,restored.request().to())),previousRunId);
    }
    private MaterializationResult execute(Plan plan,String previousRunId)throws Exception{
        Objects.requireNonNull(plan);requireNoPendingPublication();
        if(!definition().equals(plan.request().definition())||!plan.targetId().equals(plan.request().parameters().get("target_id")))throw new IllegalArgumentException("Exact frozen D103 definition/target required");
        var writer=port();var adapter=new EquityStyleMonthlyMaterializeAdapter(source,writer,plan.source());
        var ledger=new SyncRunLedger(ledgerPath);var runner=new SyncJobRunner<EquityStyleMonthly,YearMonth>(ledger,new DatasetIntervalLock(ledgerPath));
        String runId="d103-"+UUID.randomUUID();publications.put(runId,new OwnedPublication(plan,writer));
        var result=previousRunId==null?runner.run(runId,plan.checkpointParent(),plan.targetId(),plan.request(),adapter,()->false)
                :runner.resume(runId,previousRunId,plan.targetId(),plan.request(),adapter,()->false);
        var observed=safeSnapshot(writer);var verified=adapter.verifiedTarget();String error=observed.error();
        if(verified!=null&&observed.snapshot()!=null&&!verified.equals(observed.snapshot()))error="TargetChangedAfterVerification";
        return new MaterializationResult(result,plan.source().rawRows(),plan.source(),verified==null?observed.snapshot():verified,error);
    }
    public EquityStyleMonthlyTargetSnapshot installIsolated()throws Exception{
        requireNoPendingPublication();var writer=port();source.snapshot();writer.createIsolatedTarget();return writer.targetSnapshot();
    }
    public String tableName(){if(targetTable!=null){EquityStyleMonthlyWritePort.requireIsolatedTable(targetTable);return targetTable;}return port().table();}
    public String targetId()throws Exception{return port().targetId();}
    public EquityStyleMonthlyWritePort writePort()throws Exception{requireNoPendingPublication();return port();}
    public EquityStyleMonthlySource source(){return source;}
    public Path ledgerPath(){return ledgerPath;}
    /** Unknown prepared writes and materializations share the same dataset-wide retained exclusion. */
    public void requireNoPendingPublication()throws Exception{
        switch(DatasetPublicationReadModel.read(ledgerPath,datasetId())){
            case LEASE_HELD -> throw new IllegalStateException("D103 dataset has an active or uncertain publication lease");
            case PENDING_PUBLICATION -> throw new IllegalStateException("D103 pending publication requires explicit reconciliation");
            case CLEAR -> { }
        }
    }
    public Status status(String runId)throws Exception{
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=requireRun(ledger,runId);int verified=0,unresolved=0;
        for(var e:children(ledger,runId))if(e.kind()==SyncRunLedger.Kind.SLICE){
            if(e.state()==SyncRunState.VERIFIED)verified+=JobDefinitionJson.mapper().readTree(e.payloadJson()).path("verification").path("matchedRows").asInt();
            else if(e.state()!=SyncRunState.VERIFIED_EMPTY)unresolved++;
        }
        var observed=safeSnapshot();return new Status(runId,ledger.get(runId).state(),run.targetId(),run.logicalDate(),verified,unresolved,ledger.cancellationRequested(runId),observed.snapshot(),observed.error());
    }
    public boolean cancel(String runId)throws Exception{var ledger=new SyncRunLedger(ledgerPath);requireRun(ledger,runId);return ledger.requestCancellation(runId);}
    /** Same-instance stop proof is required. A new JVM cannot invent termination of the original sender. */
    public Status reconcile(String runId)throws Exception{
        var ledger=new SyncRunLedger(ledgerPath);var run=requireRun(ledger,runId);var owned=publications.get(runId);
        if(owned==null||!owned.writer().uncertainSenderStopped())throw new IllegalStateException("Original D103 sender has no same-instance stopped proof");
        if(!Set.of(SyncRunState.IN_DOUBT,SyncRunState.VERIFIED).contains(ledger.get(runId).state()))throw new IllegalStateException("D103 run is not uncertain or awaiting lease completion");
        var plan=owned.plan();if(!SyncRequestIdentity.fingerprint(plan.request(),plan.targetId()).equals(SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId())))throw new IllegalStateException("Original D103 frozen request differs");
        var current=source.read((LocalDate)plan.request().parameters().get("bootstrap_from"),plan.request().to());
        if(!current.snapshot().equals(plan.source().snapshot())||!current.rawFingerprint().equals(plan.source().rawFingerprint()))throw new IllegalStateException("D103 original source changed before reconciliation");
        var before=owned.writer().targetSnapshot();var actual=owned.writer().readActualRange(YearMonth.from((LocalDate)plan.request().parameters().get("bootstrap_from")),YearMonth.from(plan.request().to()));
        if(!before.targetId().equals(run.targetId())||!before.settled()||!EquityStyleMonthlyMaterializeAdapter.exact(current.rows(),actual)||!before.equals(owned.writer().targetSnapshot()))throw new IllegalStateException("D103 complete original prefix does not match stable actual target");
        var output=current.rows().stream().filter(r->!r.month().atDay(1).isBefore(plan.request().from())).toList();
        String fp=EquityStyleMonthlySource.hash(current.fingerprint()+"\n"+plan.request().from()+"\n"+plan.request().to());
        var uncertain=new ArrayList<SyncRunLedger.Entry>();int sliceCount=0;
        for(var child:children(ledger,runId))if(child.kind()==SyncRunLedger.Kind.SLICE){
            sliceCount++;var fetched=fetched(ledger,child.id());
            if(fetched==null||!fp.equals(fetched.path("sourceFingerprint").asText())||fetched.path("returnedRows").asInt(-1)!=output.size())throw new IllegalStateException("Original D103 submitted page is incomplete or changed");
            boolean submitted=ledger.events(child.id(),-1,100).stream().anyMatch(e->e.state()==SyncRunState.SUBMITTED);
            if(!submitted)throw new IllegalStateException("D103 original page was never durably submitted");
            if(child.state()==SyncRunState.IN_DOUBT)uncertain.add(child);else if(child.state()!=SyncRunState.VERIFIED)throw new IllegalStateException("D103 page lacks uncertain/verified delivery proof");
        }
        if(sliceCount!=1||uncertain.size()>1||output.isEmpty())throw new IllegalStateException("D103 requires its one actual uncertain nonempty batch");
        var locks=new DatasetIntervalLock(ledgerPath);var lease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates(datasetId()));
        if(lease==null||!lease.inDoubt())throw new IllegalStateException("D103 uncertain publication lost retained exclusion");
        var finalSource=source.read((LocalDate)plan.request().parameters().get("bootstrap_from"),plan.request().to());
        if(!current.snapshot().equals(finalSource.snapshot())||!current.rawFingerprint().equals(finalSource.rawFingerprint())||!before.equals(owned.writer().targetSnapshot())||!owned.writer().uncertainSenderStopped())throw new IllegalStateException("D103 reconciliation frontier changed");
        String proof=proof(output.size(),fp,"d103-reconcile:"+before.targetId());
        for(var child:uncertain)ledger.transition(child.id(),child.revision(),SyncRunState.VERIFIED,proof);
        for(var child:children(ledger,runId))if(child.kind()==SyncRunLedger.Kind.ATTEMPT&&Set.of(SyncRunState.IN_DOUBT,SyncRunState.RUNNING).contains(child.state()))ledger.transition(child.id(),child.revision(),SyncRunState.VERIFIED,proof);
        var owner=ledger.get(runId);if(owner.state()==SyncRunState.IN_DOUBT)ledger.transition(runId,owner.revision(),SyncRunState.VERIFIED,proof);
        else if(owner.state()!=SyncRunState.VERIFIED)throw new IllegalStateException("D103 owner reconciliation state changed");
        var releasedSource=source.read((LocalDate)plan.request().parameters().get("bootstrap_from"),plan.request().to());
        if(!current.snapshot().equals(releasedSource.snapshot())||!current.rawFingerprint().equals(releasedSource.rawFingerprint())||!before.equals(owned.writer().targetSnapshot())||!owned.writer().uncertainSenderStopped())throw new IllegalStateException("D103 final reconciliation proof changed; exclusion retained");
        locks.releaseAfterReconciliation(lease,true,true);return status(runId);
    }
    private record Checkpoint(String runId,YearMonth month,String sourceVersion,boolean revised) {}
    private Checkpoint checkpoint(EquityStyleMonthlyWritePort writer,String target,LocalDate anchor,LocalDate to)throws Exception{
        if(!Files.isRegularFile(ledgerPath))return null;var ledger=SyncRunLedger.openReadOnly(ledgerPath);
        var candidates=new ArrayList<SyncRunLedger.RunSummary>();String cursor=null;int pages=0;
        while(true){var page=ledger.history(JOB_ID,cursor,1000);if(page.isEmpty())break;candidates.addAll(page);cursor=page.getLast().id();if(++pages>10)throw new IllegalStateException("D103 checkpoint history budget exceeded");}
        candidates.sort(Comparator.comparing(SyncRunLedger.RunSummary::updatedAt).reversed());
        for(var candidate:candidates){
            if(candidate.state()!=SyncRunState.VERIFIED||candidate.jobVersion()!=definition().version()||!target.equals(candidate.targetId()))continue;
            var run=ledger.getRun(candidate.id());var saved=JobDefinitionJson.mapper().readTree(run.frozenJson());
            if(!anchor.toString().equals(saved.path("parameters").path("bootstrap_from").asText()))continue;
            var request=restore(run);if(request.to().isAfter(to))continue;
            var slices=children(ledger,run.id()).stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
            if(slices.size()!=1||slices.getFirst().state()!=SyncRunState.VERIFIED)continue;
            JsonNode fetched=fetched(ledger,slices.getFirst().id());if(fetched==null)continue;
            var e=JobDefinitionJson.mapper().readTree(fetched.path("responseEvidence").asText());
            String originalFp=EquityStyleMonthlySource.hash(EquityStyleMonthlySource.hash(request.parameters().get("source_version")+"\n"+request.parameters().get("source_hash"))+"\n"+request.from()+"\n"+request.to());
            var sliceProof=JobDefinitionJson.mapper().readTree(slices.getFirst().payloadJson()).path("verification");
            if(!originalFp.equals(fetched.path("sourceFingerprint").asText())||!originalFp.equals(sliceProof.path("sourceFingerprint").asText())
                    ||!e.path("sourceVersion").asText().equals(request.parameters().get("source_version"))||!e.path("rawSourceFingerprint").asText().equals(request.parameters().get("source_hash")))throw new IllegalStateException("D103 checkpoint does not bind its original frozen source");
            if(!e.path("complete").asBoolean(false)||!e.path("bootstrapFrom").asText().equals(anchor.toString())||!e.path("toInclusive").asText().equals(request.to().toString()))continue;
            var previous=decodeRows(e.path("fullPrefixExpected"));if(previous.isEmpty())continue;
            try{EquityStyleMonthlySource.requireClosedWindow(anchor,request.to(),request.logicalDate());EquityStyleMonthlySource.requireIncrementalCoverage(previous,anchor,request.to());}catch(IllegalArgumentException|IllegalStateException incomplete){continue;}
            var before=writer.targetSnapshot();var actual=writer.readActualRange(YearMonth.from(anchor),YearMonth.from(request.to()));
            if(!before.targetId().equals(target)||!before.settled()||!EquityStyleMonthlyMaterializeAdapter.exact(previous,actual)||!before.equals(writer.targetSnapshot()))throw new IllegalStateException("D103 checkpoint target prefix has drifted");
            var prefix=source.read(anchor,request.to());
            boolean revised=!prefix.rawFingerprint().equals(e.path("rawSourceFingerprint").asText())||!EquityStyleMonthlyMaterializeAdapter.exact(previous,prefix.rows());
            var previousSnapshot=e.path("sourceSnapshot");
            if(!prefix.snapshot().table().equals(previousSnapshot.path("table").asText())||prefix.snapshot().tableId()!=previousSnapshot.path("tableId").asLong(-1)||!prefix.snapshot().directory().equals(previousSnapshot.path("directory").asText())||!prefix.snapshot().schemaHash().equals(previousSnapshot.path("schemaHash").asText()))revised=true;
            return new Checkpoint(run.id(),previous.getLast().month(),request.parameters().get("source_version").toString(),revised);
        }
        return null;
    }
    private static List<EquityStyleMonthly> decodeRows(JsonNode rows){
        if(!rows.isArray()||rows.size()>12)throw new IllegalStateException("Complete finite monthly checkpoint values required");
        var result=new ArrayList<EquityStyleMonthly>();var mapper=new EquityStyleMonthlyMapper();
        for(var row:rows){var values=new LinkedHashMap<String,Object>();
            for(String field:EquityStyleMonthlyDataset.STORAGE_COLUMNS){var n=row.get(field);if(n==null)throw new IllegalStateException("Incomplete checkpoint row");
                if(field.equals("month"))values.put(field,LocalDate.parse(n.asText()));
                else if(n.isNull())values.put(field,null);else if(n.isFloatingPointNumber()&&Double.isFinite(n.doubleValue()))values.put(field,n.doubleValue());else throw new IllegalStateException("Exact nullable DOUBLE checkpoint required");
            }
            if(row.size()!=30)throw new IllegalStateException("Extra checkpoint columns");result.add(mapper.fromValues(values));
        }
        return List.copyOf(result);
    }
    private FrozenRequest restore(SyncRunLedger.Run run)throws Exception{
        var saved=JobDefinitionJson.mapper().readTree(run.frozenJson());var values=new LinkedHashMap<String,Object>();
        for(var spec:definition().parameters().entrySet()){var n=saved.path("parameters").get(spec.getKey());if(n!=null&&!n.isNull())values.put(spec.getKey(),spec.getValue().type()==ParameterType.DATE?LocalDate.parse(n.asText()):n.asText());}
        var request=definition().freeze(Mode.valueOf(saved.path("mode").asText()),values,LocalDate.parse(saved.path("from").asText()),LocalDate.parse(saved.path("to").asText()),LocalDate.parse(saved.path("logicalDate").asText()));
        if(!SyncRequestIdentity.fingerprint(request,run.targetId()).equals(SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId())))throw new IllegalStateException("D103 frozen definition/version changed");return request;
    }
    private static JsonNode fetched(SyncRunLedger ledger,String slice)throws Exception{JsonNode found=null;for(var event:ledger.events(slice,-1,100))if(event.state()==SyncRunState.FETCHED){if(found!=null)throw new IllegalStateException("Ambiguous fetched D103 evidence");found=JobDefinitionJson.mapper().readTree(event.payloadJson());}return found;}
    private SyncRunLedger.Run requireRun(SyncRunLedger ledger,String id)throws Exception{var run=ledger.getRun(id);if(!JOB_ID.equals(run.jobId())||run.jobVersion()!=definition().version())throw new IllegalArgumentException("Run does not belong to D103");return run;}
    private static List<SyncRunLedger.Entry> children(SyncRunLedger ledger,String id)throws Exception{var result=new ArrayList<SyncRunLedger.Entry>();String cursor=null;while(true){var page=ledger.entries(id,cursor,100);if(page.isEmpty())break;result.addAll(page);if(result.size()>100)throw new IllegalStateException("D103 child budget exceeded");cursor=page.getLast().id();}return result;}
    private static String proof(int n,String fp,String evidence)throws Exception{return JobDefinitionJson.mapper().writeValueAsString(Map.of("verification",Map.of("passed",true,"expectedRows",n,"actualRows",n,"matchedRows",n,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"sourceFingerprint",fp,"readbackEvidence",evidence,"writerStopped",true)));}
    private record Observed(EquityStyleMonthlyTargetSnapshot snapshot,String error){}
    private Observed safeSnapshot(){try{return safeSnapshot(port());}catch(RuntimeException e){return new Observed(null,e.getClass().getSimpleName());}}
    private static Observed safeSnapshot(EquityStyleMonthlyWritePort writer){try{return new Observed(writer.targetSnapshot(),null);}catch(RuntimeException e){return new Observed(null,e.getClass().getSimpleName());}}
    private EquityStyleMonthlyWritePort port(){return Objects.requireNonNull(factory.get(),"D103 isolated writer required");}
}
